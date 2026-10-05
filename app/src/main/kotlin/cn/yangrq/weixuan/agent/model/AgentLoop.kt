package cn.yangrq.weixuan.agent.model

import cn.yangrq.weixuan.agent.runtime.AgentEvent
import cn.yangrq.weixuan.agent.runtime.AgentRunController
import cn.yangrq.weixuan.agent.runtime.AgentTokenUsage
import cn.yangrq.weixuan.agent.roleplay.RoleplayRunContext
import org.json.JSONArray
import org.json.JSONObject

/** doom 检测：相同工具调用签名在最近窗口内达到该次数 → 注入提示。 */
private const val TOOL_REPEAT_WARN = 3

/** doom 检测：达到该次数 → 抑制后续工具调用，强制模型基于现有信息作答。 */
private const val TOOL_REPEAT_STOP = 5

/** doom 检测的滑动窗口（最近多少次工具调用参与判重）。 */
private const val TOOL_SIGNATURE_WINDOW = 12

/**
 * 单次 Agent run 的纯编排循环。
 *
 * 一次 assistant 响应及其完整工具批次构成一个 turn；
 * steering 只在 turn 结束后注入，不能用取消网络或关闭工具资源来模拟。循环不设置本地轮次上限，
 * 由模型自然结束、取消或错误终止。
 */
internal class AgentLoop(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val runController: AgentRunController,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val toolsForRound: (() -> JSONArray)? = null,
    private val modelRetry: AgentModelRetry = AgentModelRetry(),
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
    private val transcript: JSONArray = JSONArray(),
    private val systemCount: Int = 0,
    private val operationId: String = sessionId,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
    private val onTranscript: (List<AgentModelClient.ConversationMessage>) -> Unit = {},
    private val purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
    private val roleplayContext: RoleplayRunContext? = null,
    initialSupplementIndex: Int = 0,
) {
    data class Result(
        val content: String,
        val reasoningContent: String,
        val sensitiveToolCallIds: Set<String>,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private var toolCallValidator = AgentToolCallValidator(tools)
    private val accumulatedReasoning = StringBuilder()
    private val sensitiveToolCallIds = linkedSetOf<String>()
    private var pendingToolImageMessage: JSONObject? = null

    // ── doom 循环检测（借鉴 OkHuman）────────────────────────────────────────────
    // agent 卡在「同参数反复调用同一工具」时，每一轮都是满载推理：毫无进展却持续发热
    // （实测能把电池顶到 47~49°C、系统界面重载）。达到 TOOL_REPEAT_WARN 次注入提示，
    // 达到 TOOL_REPEAT_STOP 次直接抑制后续工具调用、强制模型基于现有信息作答。
    private val recentToolSignatures = ArrayDeque<String>()
    private var toolUseSuppressed = false
    private var doomNoteIndex = 0

    /** find_tools 发现过的工具名（按需加载，见 AgentToolDiscovery；默认关闭时始终为空）。 */
    private val discoveredToolNames = linkedSetOf<String>()
    private val context = AgentContextSession(
        config, messages, systemCount, operationId, provider, runController,
        { sensitiveToolCallIds }, onEvent, onContextSnapshot, { transcript.length() },
        roleplay = roleplayContext != null,
    )
    private var supplementIndex = initialSupplementIndex

    fun contextSnapshot(): AgentContextSnapshot? = context.snapshot()

    private fun appendMessage(message: JSONObject) {
        messages.put(message)
        transcript.put(message)
    }

    private var publishedTranscriptSize = 0

    private fun publishTranscript() {
        if (publishedTranscriptSize == transcript.length()) return
        onTranscript(AgentConversationCodec.transcript(transcript, 0, sensitiveToolCallIds))
        publishedTranscriptSize = transcript.length()
    }

    fun compactOnly(): Result {
        context.compact(tools, force = true)
        return Result("", "", emptySet())
    }

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun sensitiveToolCallIdsSnapshot(): Set<String> = sensitiveToolCallIds.toSet()

    fun run(): Result {
        var round = 1

        while (true) {
            runController.throwIfCancelled()
            if (purpose.allowsTools) appendPendingSteeringMessage()

            val baseTools = if (purpose.allowsTools && !toolUseSuppressed) toolsForRound?.invoke() ?: tools else JSONArray()
            // 工具按需发现（设置开关，默认关）：只下发「核心集 + 已发现工具 + find_tools」，
            // 其余工具靠 find_tools 现查现挂 —— 常驻工具块从 6110 token 降到约 1.5k 量级。
            val roundTools = if (AgentToolDiscovery.isEnabled() && baseTools.length() > 0) {
                AgentToolDiscovery.selectFor(baseTools, discoveredToolNames)
            } else {
                baseTools
            }
            toolCallValidator = AgentToolCallValidator(roundTools)
            publishTranscript()
            context.compact(roundTools)
            var requestMessages = AssistantScreenContextProjection.project(
                roleplayContext?.projectMessages(messages, roundTools) ?: messages,
            )
            var requestEstimate = AgentContextBudget.rawEstimate(requestMessages, roundTools)
            var roundInputTokens: Int? = null
            var overflowAttempts = 0
            val reasoningLengthBeforeRound = accumulatedReasoning.length
            var completedResponse: AgentModelRetry.Result? = null
            val completedRound = try {
                while (true) {
                    try {
                        val response = modelRetry.complete(
                            initialRound = round,
                            request = ProviderRequest(config, requestMessages, roundTools, sessionId, purpose),
                            provider = provider,
                            controller = runController,
                            onEvent = onEvent,
                            onProviderEvent = { attemptRound, providerEvent ->
                                if (!purpose.allowsTools && (providerEvent is ProviderEvent.HostedToolStarted ||
                                        providerEvent is ProviderEvent.BlockStart && providerEvent.kind == AssistantBlockKind.TOOL_CALL)) {
                                    throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "改写回复时模型请求了工具，已停止；原回复未改变。")
                                }
                                if (providerEvent is ProviderEvent.Usage) {
                                roundInputTokens = providerEvent.contextInputTokens ?: roundInputTokens
                            }
                                if (providerEvent is ProviderEvent.BlockDelta &&
                                    providerEvent.kind == AssistantBlockKind.THINKING
                                ) {
                                    accumulatedReasoning.append(providerEvent.delta)
                                }
                                providerEvent.toAgentEvent(attemptRound)?.let(onEvent)
                            },
                            discardAttemptReasoning = { accumulatedReasoning.setLength(reasoningLengthBeforeRound) },
                        )
                        completedResponse = response
                        break
                    } catch (failure: AgentModelFailure) {
                        if (failure.code != "CONTEXT_OVERFLOW" || !failure.recoveryAllowed ||
                            overflowAttempts >= AgentContextBudget.MAX_OVERFLOW_ATTEMPTS) throw failure
                        overflowAttempts++
                        accumulatedReasoning.setLength(reasoningLengthBeforeRound)
                        context.compact(roundTools, force = true)
                        requestMessages = AssistantScreenContextProjection.project(
                            roleplayContext?.projectMessages(messages, roundTools) ?: messages,
                        )
                        requestEstimate = AgentContextBudget.rawEstimate(requestMessages, roundTools)
                        roundInputTokens = null
                        round++
                    }
                }
                checkNotNull(completedResponse)
            } finally {
                // 同一回合的重试仍需原始观察；整个回合结束后才移除截图。
                discardPendingToolImageMessage()
            }
            context.budget.observe(
                roundInputTokens?.let { AgentTokenUsage(inputTokens = it) },
                requestEstimate,
            )
            round = completedRound.round
            val providerResponse = completedRound.response

            runController.throwIfCancelled()
            val assistantMessage = providerResponse.assistantMessage
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            if (!purpose.allowsTools && toolCalls.isNotEmpty()) {
                throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "改写回复时模型请求了工具，已停止；原回复未改变。")
            }
            if (purpose == ProviderRequestPurpose.REPLY_REWRITE && providerResponse.stopReason != AssistantStopReason.END_TURN) {
                throw AgentModelFailure("REPLY_REWRITE_INCOMPLETE", false, "模型未返回完整的改写回复；原回复未改变。")
            }
            val assistantReasoning = assistantMessage.optString("reasoning_content")
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                accumulatedReasoning.append(assistantReasoning)
            }

            appendMessage(
                AgentConversationCodec.assistantHistoryMessage(
                    source = assistantMessage,
                    toolCalls = toolCalls,
                ).put("_eta_message_id", "assistant-$operationId-$round")
            )
            onEvent(
                AgentEvent.AssistantReceived(
                    round = round,
                    contentChars = assistantMessage.optString("content").length,
                    reasoningContent = assistantReasoning,
                    toolNames = toolCalls.map { it.name },
                )
            )

            if (toolCalls.isNotEmpty()) {
                noteToolCallsAndMaybeWarn(toolCalls)
                val outcomes = toolCalls.map { call ->
                    val outcome = when (providerResponse.stopReason) {
                        AssistantStopReason.TOOL_USE ->
                            // find_tools 是「工具目录」虚拟工具：不经过执行器，直接检索并把命中工具登记进
                            // discoveredToolNames，下一轮它们的完整定义就会出现在工具列表里。
                            if (call.name == AgentToolDiscovery.TOOL_NAME) discoveryOutcome(call)
                            else executeTool(round, call)
                        AssistantStopReason.OUTPUT_LIMIT -> rejectedToolOutcome(
                            round, call, "TRUNCATED_TOOL_CALL",
                            "模型输出达到长度上限，工具参数可能不完整；本次调用未执行，请重新提交完整参数。",
                        )
                        else -> rejectedToolOutcome(
                            round, call, "UNEXPECTED_TOOL_CALL",
                            "模型在 ${providerResponse.stopReason.name} 终止状态下返回了工具调用；本批调用未执行，请重新规划。",
                        )
                    }
                    appendMessage(AgentConversationCodec.toolResultMessage(outcome.call, outcome.result))
                    publishTranscript()
                    outcome
                }
                appendToolImages(round, outcomes)
                publishTranscript()
                round += 1
                continue
            }

            publishTranscript()

            // assistant 已自然结束时再检查 steering。这样补充消息不会丢掉刚完成的回答。
            if (purpose.allowsTools && appendPendingSteeringOrSeal()) {
                round += 1
                continue
            }

            val content = assistantMessage.optString("content").trim()
            if (content.isBlank() || content == "null") {
                val finishReason = assistantMessage.optString("finish_reason")
                error("模型接口第 $round 轮返回为空${finishReason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }

            publishTranscript()
            if (purpose.allowsTools) context.compact(roundTools, final = true)
            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
            return Result(
                content = content,
                reasoningContent = reasoningSnapshot(),
                sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
            )
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        val supplement = runController.pollSteeringMessage() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        return true
    }

    private fun appendPendingSteeringOrSeal(): Boolean {
        val supplement = runController.pollSteeringOrSeal() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        return true
    }

    private fun steeringPrompt(supplement: String): String =
        "用户补充指令：$supplement\n\n请基于当前任务上下文继续执行，不要从头重复已经完成或已经验证过的操作。"

    private fun steeringMessage(supplement: String): JSONObject =
        AgentConversationCodec.userTextMessage(steeringPrompt(supplement))
            .put("_eta_message_id", "user-$operationId-supplement-${++supplementIndex}")

    /**
     * doom 循环检测：记录本轮工具调用签名，识别「同参数反复调用同一工具」。
     *
     * 目的是掐掉「卡在循环里持续满载」这种最伤机器的情形 —— 每一轮都是完整的 prefill + decode，
     * 毫无进展却把电池顶到 47~49°C。达到 [TOOL_REPEAT_STOP] 次后置 [toolUseSuppressed]，
     * 下一轮起不再提供工具（`roundTools` 变空集），模型只能基于现有信息作答。
     */
    private fun noteToolCallsAndMaybeWarn(calls: List<AgentModelClient.ToolCall>) {
        var worstRepeat = 0
        var worstName = ""
        for (call in calls) {
            val signature = call.name + "|" + call.argumentsJson
            val repeat = recentToolSignatures.count { it == signature } + 1
            if (repeat > worstRepeat) {
                worstRepeat = repeat
                worstName = call.name
            }
            recentToolSignatures.addLast(signature)
            while (recentToolSignatures.size > TOOL_SIGNATURE_WINDOW) {
                recentToolSignatures.removeFirst()
            }
        }
        if (worstRepeat >= TOOL_REPEAT_STOP) {
            toolUseSuppressed = true
            appendMessage(
                AgentConversationCodec.userTextMessage(
                    "已自动停止工具调用：检测到「$worstName」被以相同参数重复调用 $worstRepeat 次，重复调用不会产生新的信息。" +
                        "请直接基于已获得的结果回答用户；如果确实缺少关键信息，请明确说明缺少什么。"
                ).put("_eta_message_id", "user-$operationId-doom-${++doomNoteIndex}")
            )
            return
        }
        if (worstRepeat == TOOL_REPEAT_WARN) {
            appendMessage(
                AgentConversationCodec.userTextMessage(
                    "提示：你刚刚以相同参数重复调用「$worstName」$worstRepeat 次，结果不会再变化。" +
                        "请换一种方法（换工具、换参数、先读取更多上下文），或者直接给出结论。"
                ).put("_eta_message_id", "user-$operationId-doom-${++doomNoteIndex}")
            )
        }
    }

    /**
     * `find_tools` 的实现：在**完整工具集**里按关键词检索，登记命中工具名（下一轮生效），
     * 并把目录结果作为工具结果回给模型。见 [AgentToolDiscovery]。
     */
    private fun discoveryOutcome(call: AgentModelClient.ToolCall): ToolOutcome {
        val query = runCatching { JSONObject(call.argumentsJson).optString("query") }.getOrDefault("")
        val discovery = AgentToolDiscovery.discover(query, tools)
        discoveredToolNames += discovery.names
        return ToolOutcome(call, AgentModelClient.ToolResult(content = discovery.content))
    }

    private fun executeTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        runController.throwIfCancelled()
        toolCallValidator.validate(toolCall)?.let { validationError ->
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "INVALID_TOOL_ARGUMENTS",
                message = validationError,
            )
        }
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )

        val result = try {
            toolExecutor.execute(toolCall)
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "TOOL_ERROR")
                    .put("message", throwable.message ?: throwable.javaClass.simpleName)
                    .toString(),
            )
        }
        if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
            sensitiveToolCallIds += toolCall.id
        }

        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): ToolOutcome {
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
        )
        if (result.sensitive) sensitiveToolCallIds += toolCall.id
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun emitToolFinished(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        onEvent(
            AgentEvent.ToolFinished(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                resultSummary = traceFormatter.summarizeResult(toolCall.name, result),
                imageCount = result.images.size,
                imageBytes = result.images.sumOf { it.bytes },
                success = traceFormatter.isSuccessResult(result),
            )
        )
    }

    private fun appendToolImages(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // 每个已完成结果立即落盘；图片观察仍统一放在完整工具批次之后。
        val imageOutcomes = outcomes.filter { outcome -> outcome.result.images.isNotEmpty() }
        if (imageOutcomes.isEmpty()) {
            return
        }

        // 工具截图是瞬时观察，不是会话资产。下一次思考消费后立即删除。
        discardPendingToolImageMessage()
        val images = imageOutcomes.flatMap { outcome -> outcome.result.images }
        val toolNames = imageOutcomes
            .map { outcome -> outcome.call.name }
            .distinct()
            .joinToString(", ")
        pendingToolImageMessage = AgentConversationCodec.userMessage(
            text = "Latest observation image(s) returned by tool(s): $toolNames.",
            images = images,
        ).put("_eta_observation", true).also(messages::put)

        imageOutcomes.forEach { outcome ->
            onEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = outcome.result.images.size,
                    imageBytes = outcome.result.images.sumOf { it.bytes },
                )
            )
        }
    }

    private fun discardPendingToolImageMessage() {
        val pending = pendingToolImageMessage ?: return
        pendingToolImageMessage = null
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === pending) {
                messages.remove(index)
                return
            }
        }
    }

    private fun ProviderEvent.toAgentEvent(round: Int): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(round)
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.BlockStart -> AgentEvent.AssistantBlockStart(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
            )
            is ProviderEvent.BlockDelta -> AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                deltaChars = delta.length,
                delta = delta,
            )
            is ProviderEvent.BlockEnd -> AgentEvent.AssistantBlockEnd(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                contentChars = content.length,
                replacementContent = content.takeIf { replaceContent },
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(round = round, usage = usage)
            is ProviderEvent.HostedToolStarted -> AgentEvent.HostedToolStarted(
                round = round,
                toolCallId = id,
                name = name,
            )
            is ProviderEvent.HostedToolFinished -> AgentEvent.HostedToolFinished(
                round = round,
                toolCallId = id,
                name = name,
                success = success,
            )
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

}
