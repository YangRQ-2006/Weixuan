package cn.yangrq.weixuan.agent.model

import cn.yangrq.weixuan.agent.runtime.AgentRunController
import cn.yangrq.weixuan.agent.runtime.AgentTokenUsage
import cn.yangrq.weixuan.data.model.OpenAiEndpointMode
import cn.yangrq.weixuan.data.model.ProviderSourceTypes
import cn.yangrq.weixuan.data.provider.ProviderSourceRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal object OpenAiChatCompletionsProvider : AgentProviderClient {
    private const val MAX_ERROR_CHARS = 600
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override val id: String = "openai_chat_completions"

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false
        )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): ProviderResponse {
        val config = request.effectiveConfig
        require(config.openAiEndpointMode == OpenAiEndpointMode.CHAT_COMPLETIONS) {
            "当前 Provider 未配置为 Chat Completions API"
        }
        val url = ProviderUrls.openAiChatCompletionsUrl(config.baseUrl)
        val headers = okhttp3.Headers.Builder()
            .add("Content-Type", "application/json; charset=utf-8")
            .add("Accept", "text/event-stream")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .also { ProviderRequestHeaders.mergeInto(it, config.baseUrl, config.customHeaders, request.sessionId) }
            .build()

        val requestBody = buildRequestJson(config, request.messages, request.effectiveTools).apply {
            if (!request.purpose.allowsTools) {
                remove("tools")
                remove("tool_choice")
            }
        }
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url(url)
            .headers(headers)
            .post(requestBody)
            .build()

        val call = AgentHttpClient.modelClient.newCall(httpRequest)
        val binding = runController.register { call.cancel() }

        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)

            call.execute().use { response ->
                val code = response.code
                onEvent(ProviderEvent.ResponseHeaders(code))
                runController.throwIfCancelled()

                if (!response.isSuccessful) {
                    val errorBody = response.peekBody(16_384).string()
                    throw AgentModelFailure.http(code, errorBody)
                }

                val assistantMessage = readStreamingAssistantMessage(response.body.byteStream(), runController, onEvent)
                onEvent(ProviderEvent.Completed(assistantMessage.optString("finish_reason").ifBlank { null }))
                return ProviderResponse(assistantMessage)
            }
        } catch (throwable: Throwable) {
            runCatching { runController.throwIfCancelled() }
                .getOrElse { interruption -> throw interruption }
            throw throwable
        } finally {
            binding.close()
        }
    }

    /**
     * 工具 schema 压缩（2026-09-26）：本地模型上下文窗口有限（8192），31 个工具的完整
     * schema（含参数级 description）约 7900 tokens → prefill 极慢。压缩策略：截短工具描述
     * （保留核心语义），移除参数级 description（保留参数名与类型，模型可从名字推断）。
     * 压缩后 ~2500 tokens，工具能力完整保留。
     */
    /**
     * 历史裁剪（本地小窗口优化）：只保留最近 maxCount 条消息，并保证 tool 消息与其
     * 前置 assistant（含 tool_calls）配对完整——否则 llama.cpp 会以 400 拒绝请求。
     * 云端大窗口模型不受影响（消息数未超限时原样返回）。
     */
    private fun trimHistory(messages: JSONArray, maxCount: Int = 5): JSONArray {
        if (messages.length() <= maxCount) return messages
        var start = messages.length() - maxCount
        while (start > 0 && messages.optJSONObject(start)?.optString("role") == "tool") {
            start--
        }
        val out = JSONArray()
        for (i in start until messages.length()) {
            messages.optJSONObject(i)?.let { out.put(it) }
        }
        return out
    }

    /** 核心工具白名单：本地模型窗口/内存紧张时只保留高频工具（工具数 >20 时启用过滤）。 */
    private val coreToolNames = setOf(
        "get_current_context", "launch_app", "search_apps",
        "tap", "tap_element", "input_text", "press_key", "swipe",
        "observe_screen", "wait", "wait_for_text", "run_command",
    )

    private fun compactTools(tools: JSONArray): JSONArray {
        if (tools.length() == 0) return tools
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            val toolName = tool.optJSONObject("function")?.optString("name").orEmpty()
            // 工具数 >20 时启用白名单过滤：本地小窗口模型装不下全部 schema，保留高频工具即可
            if (tools.length() > 20 && toolName !in coreToolNames) continue
            val fn = tool.optJSONObject("function")
            if (fn != null) {
                val desc = fn.optString("description")
                if (desc.length > 48) fn.put("description", desc.take(46) + "…")
                fn.optJSONObject("parameters")?.optJSONObject("properties")?.let { props ->
                    val keys = props.keys()
                    while (keys.hasNext()) {
                        props.optJSONObject(keys.next())?.remove("description")
                    }
                }
            }
            out.put(tool)
        }
        return out
    }

    private fun buildRequestJson(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray
    ): JSONObject {
        val sourceType = ProviderSourceRegistry.resolve(
            providerId = config.providerId,
            sourceType = config.providerSourceType,
            baseUrl = config.baseUrl,
            providerType = config.providerType,
        )
        return JSONObject()
            .put("model", config.model)
            .put("stream", true)
            .put("messages", OpenAiRequestMessages.forChatCompletions(trimHistory(messages)))
            .put("tools", compactTools(tools))
            .put("tool_choice", "auto")
            .also { request ->
                if (sourceType != ProviderSourceTypes.OPENROUTER) {
                    request.put("stream_options", JSONObject().put("include_usage", true))
                }
                mergeExtraBody(request, config.extraBodyJson)
                RequestBodyMerge.mergeCustomBody(request, config.customBody)
                ProviderReasoning.applyOpenAiCompatibleRequest(request, config)
            }
    }

    private fun readStreamingAssistantMessage(
        stream: java.io.InputStream?,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): JSONObject {
        if (stream == null) error("模型接口未返回响应流")
        val content = StringBuilder()
        val reasoningContent = StringBuilder()
        val toolCalls = linkedMapOf<Int, StreamingToolCall>()
        var usage: AgentTokenUsage? = null
        var sawStreamData = false
        var sawDone = false
        var finishReason: String? = null
        var nextContentIndex = 0
        var activeVisibleBlock: StreamingVisibleBlock? = null

        fun finishActiveVisibleBlock() {
            val block = activeVisibleBlock ?: return
            onEvent(
                ProviderEvent.BlockEnd(
                    kind = block.kind,
                    index = block.contentIndex,
                    content = block.content.toString(),
                )
            )
            activeVisibleBlock = null
        }

        fun appendVisibleDelta(kind: AssistantBlockKind, delta: String) {
            if (delta.isEmpty()) return
            var block = activeVisibleBlock
            if (block?.kind != kind) {
                finishActiveVisibleBlock()
                block = StreamingVisibleBlock(
                    kind = kind,
                    contentIndex = nextContentIndex++,
                ).also { created ->
                    activeVisibleBlock = created
                    onEvent(ProviderEvent.BlockStart(kind, created.contentIndex))
                }
            }
            block.content.append(delta)
            onEvent(ProviderEvent.BlockDelta(kind, block.contentIndex, delta))
        }

        readProviderSse(stream, runController) { _, data ->
            sawStreamData = true
            val payload = data.trim()
            if (payload == "[DONE]") {
                sawDone = true
                return@readProviderSse false
            }
            val chunk = JSONObject(payload)
            throwStreamingErrorIfPresent(chunk)
            parseUsage(chunk)?.let { parsedUsage ->
                usage = parsedUsage
                onEvent(ProviderEvent.Usage(parsedUsage))
            }
            val choices = chunk.optJSONArray("choices")
            if (choices == null || choices.length() == 0) return@readProviderSse true
            val choice = choices.optJSONObject(0) ?: return@readProviderSse true
            val reason = choice.optString("finish_reason")
            if (reason.isNotBlank() && reason != "null") {
                finishReason = reason
            }
            if (reason == "error") {
                error("模型接口 SSE 以 error 结束")
            }
            val delta = choice.optJSONObject("delta") ?: JSONObject()
            val reasoningDelta = visibleReasoningDelta(delta)
            if (reasoningDelta.isNotEmpty()) {
                reasoningContent.append(reasoningDelta)
                appendVisibleDelta(AssistantBlockKind.THINKING, reasoningDelta)
            }
            if (delta.has("content") && !delta.isNull("content")) {
                val text = delta.optString("content")
                if (text.isNotEmpty()) {
                    content.append(text)
                    appendVisibleDelta(AssistantBlockKind.TEXT, text)
                }
            }
            val deltaToolCalls = delta.optJSONArray("tool_calls") ?: JSONArray()
            if (deltaToolCalls.length() > 0) finishActiveVisibleBlock()
            for (i in 0 until deltaToolCalls.length()) {
                val item = deltaToolCalls.optJSONObject(i) ?: continue
                val index = item.optInt("index", i)
                val call = toolCalls.getOrPut(index) {
                    StreamingToolCall(
                        index = index,
                        contentIndex = nextContentIndex++,
                    ).also { created ->
                        onEvent(
                            ProviderEvent.BlockStart(
                                kind = AssistantBlockKind.TOOL_CALL,
                                index = created.contentIndex,
                            )
                        )
                    }
                }
                if (item.has("id") && !item.isNull("id")) call.id = item.optString("id")
                if (item.has("type") && !item.isNull("type")) call.type = item.optString("type").ifBlank { "function" }
                val function = item.optJSONObject("function")
                val nameDelta = function?.takeIf { it.has("name") && !it.isNull("name") }?.optString("name").orEmpty()
                val argsDelta = function?.takeIf { it.has("arguments") && !it.isNull("arguments") }?.optString("arguments").orEmpty()
                if (nameDelta.isNotEmpty()) call.name.append(nameDelta)
                if (argsDelta.isNotEmpty()) call.arguments.append(argsDelta)
                if (argsDelta.isNotEmpty()) {
                    onEvent(
                        ProviderEvent.BlockDelta(
                            kind = AssistantBlockKind.TOOL_CALL,
                            index = call.contentIndex,
                            delta = argsDelta,
                        )
                    )
                }
            }
            if (finishReason != null) finishActiveVisibleBlock()
            true
        }

        if (!sawStreamData) throw AgentModelFailure.incompleteStream("模型接口未返回 SSE data chunk")
        if (!sawDone && finishReason == null) throw AgentModelFailure.incompleteStream("模型接口 SSE 流未正常结束")

        finishActiveVisibleBlock()
        toolCalls.values.sortedBy { it.contentIndex }.forEach { call ->
            onEvent(
                ProviderEvent.BlockEnd(
                    kind = AssistantBlockKind.TOOL_CALL,
                    index = call.contentIndex,
                    blockId = call.id,
                    name = call.name.toString().ifBlank { null },
                    content = call.arguments.toString(),
                )
            )
        }

        return JSONObject()
            .put("role", "assistant")
            .put("content", content.toString())
            .put("reasoning_content", reasoningContent.toString())
            .put("finish_reason", finishReason.orEmpty())
            .also { message ->
                usage?.let { message.put("usage", it.toJson()) }
            }
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put(
                        "tool_calls",
                        JSONArray().also { array ->
                            toolCalls.values.sortedBy { it.index }.forEachIndexed { position, call ->
                                array.put(call.toJson(position))
                            }
                        }
                    )
                }
            }
    }

    private data class StreamingToolCall(
        val index: Int,
        val contentIndex: Int,
        var id: String? = null,
        var type: String = "function",
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun toJson(position: Int): JSONObject {
            val functionName = name.toString().trim()
            return JSONObject()
                .put("id", id ?: "tool_call_$position")
                .put("type", type.ifBlank { "function" })
                .put(
                    "function",
                    JSONObject()
                        .put("name", functionName)
                        .put("arguments", arguments.toString())
                )
        }
    }

    private data class StreamingVisibleBlock(
        val kind: AssistantBlockKind,
        val contentIndex: Int,
        val content: StringBuilder = StringBuilder(),
    )

    private fun visibleReasoningDelta(delta: JSONObject): String {
        // 同一分片的纯文本与结构化字段可重复携带相同思考，只消费一种表示。
        for (key in listOf("reasoning_content", "reasoning")) {
            (delta.opt(key) as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val details = delta.optJSONArray("reasoning_details") ?: return ""
        return buildString {
            for (index in 0 until details.length()) {
                val detail = details.optJSONObject(index) ?: continue
                val key = when (detail.optString("type")) {
                    "reasoning.text" -> "text"
                    "reasoning.summary" -> "summary"
                    else -> continue
                }
                (detail.opt(key) as? String)?.let(::append)
            }
        }
    }

    private fun mergeExtraBody(request: JSONObject, extraBodyJson: String) {
        if (extraBodyJson.isBlank()) return
        val extraBody = JSONObject(extraBodyJson)
        extraBody.keys().forEach { key ->
            request.put(key, extraBody.get(key))
        }
    }

    private fun throwStreamingErrorIfPresent(chunk: JSONObject) {
        val streamError = chunk.optJSONObject("error") ?: return
        val code = streamError.opt("code")
            ?.toString()
            ?.takeIf { it.isNotBlank() && it != "null" }
        val errorType = streamError.optJSONObject("metadata")
            ?.optString("error_type")
            ?.takeIf { it.isNotBlank() && it != "null" }
        val context = listOfNotNull(
            code?.let { "code=$it" },
            errorType?.let { "type=$it" },
        ).joinToString(", ")
        val message = streamError.optString("message")
            .ifBlank { "未提供错误信息" }
            .compactError()
        throw AgentModelFailure.stream(
            streamError,
            "模型接口 SSE 返回错误${context.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}：$message",
        )
    }

    private fun parseUsage(chunk: JSONObject): AgentTokenUsage? {
        val usage = chunk.optJSONObject("usage") ?: return null
        return AgentTokenUsage(
            contextTokens = usage.firstInt("total_tokens"),
            inputTokens = usage.firstInt("prompt_tokens", "input_tokens"),
            outputTokens = usage.firstInt("completion_tokens", "output_tokens"),
            reasoningTokens = usage.firstNestedInt(
                "completion_tokens_details",
                "output_tokens_details",
                childKey = "reasoning_tokens"
            ),
            cachedTokens = usage.firstNestedInt(
                "prompt_tokens_details",
                childKey = "cached_tokens"
            ) ?: usage.firstInt("cache_read_input_tokens"),
            tokensPerSecond = usage.optDouble("tokens_per_second").takeIf { it.isFinite() && it > 0 }
                // 自建 llama.cpp runtime 不返回扩展字段，速度在响应根的 timings 里
                ?: chunk.optJSONObject("timings")?.optDouble("predicted_per_second")
                    ?.takeIf { it.isFinite() && it > 0 },
            ttftMs = usage.optDouble("ttft_ms").takeIf { it.isFinite() && it > 0 }
                ?: chunk.optJSONObject("timings")?.optDouble("prompt_ms")
                    ?.takeIf { it.isFinite() && it > 0 },
        ).takeUnless { it.isEmpty }
    }

    private fun JSONObject.firstInt(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val raw = opt(key)
            when (raw) {
                is Number -> return raw.toInt()
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.firstNestedInt(
        vararg parentKeys: String,
        childKey: String
    ): Int? {
        for (parentKey in parentKeys) {
            val parent = optJSONObject(parentKey) ?: continue
            parent.firstInt(childKey)?.let { return it }
        }
        return null
    }

    private fun AgentTokenUsage.toJson(): JSONObject =
        JSONObject().also { json ->
            contextTokens?.let { json.put("total_tokens", it) }
            inputTokens?.let { json.put("input_tokens", it) }
            outputTokens?.let { json.put("output_tokens", it) }
            reasoningTokens?.let { json.put("reasoning_tokens", it) }
            cachedTokens?.let { json.put("cached_tokens", it) }
        }

    private fun String.compactError(): String =
        replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > MAX_ERROR_CHARS) it.take(MAX_ERROR_CHARS) + "..." else it }
}
