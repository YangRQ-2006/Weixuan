package cn.yangrq.weixuan.agent.runtime

import cn.yangrq.weixuan.core.toSafeLogToken

internal sealed interface AgentEvent {
    fun toLogLine(): String

    enum class AssistantBlockKind {
        TEXT,
        THINKING,
        TOOL_CALL,
    }

    data class ContextCompaction(
        val operationId: String,
        val phase: String,
        val tokensBefore: Int,
        val tokensAfter: Int? = null,
        val reasonCode: String = "",
    ) : AgentEvent {
        val displayMessage: String get() = when (phase) {
            PHASE_STARTED -> RUNNING_DETAIL
            PHASE_COMPLETED -> "上下文已压缩：约 ${compactionTokenCount(tokensBefore)} → " +
                "${compactionTokenCount(tokensAfter ?: 0)} tokens"
            else -> "上下文压缩失败，原始上下文已保留。"
        }
        override fun toLogLine(): String =
            "context_compaction phase=${phase.toSafeLogToken()}, before=$tokensBefore, after=$tokensAfter, code=${reasonCode.toSafeLogToken()}"

        companion object {
            const val PHASE_STARTED = "started"
            const val PHASE_COMPLETED = "completed"
            /** 进行中的展示文案同时是 UI 判定运行态的依据，改动必须与 UI 侧同步。 */
            const val RUNNING_DETAIL = "正在压缩上下文…"
        }
    }

    data class RunStarted(
        val initialImages: Int,
        val initialImageBytes: Int,
        val toolCount: Int,
        val terminalTools: Boolean
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_started images=$initialImages, image_bytes=$initialImageBytes, tools=$toolCount, terminal=$terminalTools"
    }

    data class RoundStarted(
        val round: Int,
        val messageCount: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "round_started round=$round, messages=$messageCount"
    }

    data class ModelRetryScheduled(
        val round: Int,
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Int,
        val reasonCode: String,
        /** 服务端返回的原始错误摘要（如"内存不足，暂缓加载…"），空则维持历史文案。 */
        val detail: String = "",
    ) : AgentEvent {
        val displayMessage: String
            get() = when {
                // 2026-10-04：本地模型冷启动加载复用本事件通道（见 LOCAL_MODEL_LOADING_CODE），
                // 但它**不是失败**，必须给出不同文案 —— 否则悬浮窗/小爱/overlay 三处都会把它
                // 渲染成"请求中断、N 秒后重试"。把判断放在这里，一处修好全部渲染点。
                reasonCode == LOCAL_MODEL_LOADING_CODE ->
                    detail.ifBlank { "正在加载本地模型，请稍候…" }
                detail.isBlank() ->
                    "模型请求暂时中断，${delayMs / 1000} 秒后重试（$attempt/$maxAttempts）；此前工具结果已保留。"
                else ->
                    "模型请求暂时中断：$detail，${delayMs / 1000} 秒后重试（$attempt/$maxAttempts）；此前工具结果已保留。"
            }

        override fun toLogLine(): String =
            "model_retry_scheduled round=$round, attempt=$attempt, delay_ms=$delayMs, code=${reasonCode.toSafeLogToken()}"
    }

    companion object {
        /**
         * 「本地模型加载中」提示用的 reasonCode。
         *
         * **为什么复用 [ModelRetryScheduled] 而不是新增事件**：该事件的 wire 序列化（AgentRuntimeWire
         * 双向）与全部 UI 渲染链路（chat projector / overlay / 小爱 / 悬浮窗）都已贯通，新增事件要动
         * 6~7 个文件且收益完全相同。代价是语义上略绕，故由本常量 + `displayMessage` 分支收口。
         *
         * 背景：加载实测受存储带宽限制（2.9GB ÷ ~150MB/s ≈ 20 秒，见
         * `scripts/bench/loadfactor_test.sh`），耗时省不掉，但原先这 17~20 秒**完全静默**，
         * 用户只感到"卡住/等很久"。
         */
        const val LOCAL_MODEL_LOADING_CODE = "LOCAL_MODEL_LOADING"
    }

    data class ProviderRequestStarted(
        val round: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "provider_request_started round=$round"
    }

    data class ProviderResponseStarted(
        val round: Int,
        val httpCode: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "provider_response_started round=$round, http_code=$httpCode"
    }

    data class AssistantBlockStart(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_start round=$round, kind=$kind, index=$index, " +
                "name=${name.toSafeLogToken()}"
    }

    data class AssistantBlockDelta(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val deltaChars: Int,
        val delta: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_delta round=$round, kind=$kind, index=$index, chars=$deltaChars"
    }

    data class AssistantBlockEnd(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
        val contentChars: Int,
        val replacementContent: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_end round=$round, kind=$kind, index=$index, " +
                "name=${name.toSafeLogToken()}, chars=$contentChars"
    }

    data class AssistantReceived(
        val round: Int,
        val contentChars: Int,
        val reasoningContent: String,
        val toolNames: List<String>
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_received round=$round, content_chars=$contentChars, " +
                "reasoning_chars=${reasoningContent.length}, tool_count=${toolNames.size}, " +
                "tools=${toolNames.take(MAX_LOGGED_TOOL_NAMES).map { it.toSafeLogToken() }}"
    }

    data class UsageReceived(
        val round: Int,
        val usage: AgentTokenUsage
    ) : AgentEvent {
        override fun toLogLine(): String =
            "usage_received round=$round, ctx=${usage.contextTokens}, in=${usage.inputTokens}, out=${usage.outputTokens}, reasoning=${usage.reasoningTokens}, cache=${usage.cachedTokens}"
    }

    data class UserSupplementReceived(
        val index: Int,
        val text: String
    ) : AgentEvent {
        override fun toLogLine(): String =
            "user_supplement_received index=$index, chars=${text.length}"
    }

    data class ToolStarted(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val argsPreview: String,
        val command: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_started round=$round, name=${name.toSafeLogToken()}, " +
                "args_chars=${argsPreview.length}, command_chars=${command?.length ?: 0}"
    }

    data class ToolFinished(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val resultSummary: String,
        val imageCount: Int,
        val imageBytes: Int,
        /** 可选：旧版本 Runtime 不发送，消费端缺省时回退到摘要文本判断。 */
        val success: Boolean? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_finished round=$round, name=${name.toSafeLogToken()}, " +
                "${resultSummary.toSafeResultLogFields()}, images=$imageCount, image_bytes=$imageBytes"
    }

    data class HostedToolStarted(
        val round: Int,
        val toolCallId: String,
        val name: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "hosted_tool_started round=$round, name=${name.toSafeLogToken()}"
    }

    data class HostedToolFinished(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val success: Boolean,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "hosted_tool_finished round=$round, name=${name.toSafeLogToken()}, success=$success"
    }

    data class ToolImagesAttached(
        val round: Int,
        val toolName: String,
        val imageCount: Int,
        val imageBytes: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_images_attached round=$round, name=${toolName.toSafeLogToken()}, " +
                "images=$imageCount, image_bytes=$imageBytes"
    }

    data class RunFinished(
        val round: Int,
        val contentChars: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_finished round=$round, content_chars=$contentChars"
    }

    data class RunFailed(
        val reason: String
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_failed reason_chars=${reason.length}"
    }
}

private const val MAX_LOGGED_TOOL_NAMES = 8
private const val RESULT_CODE_MARKER = "code="

private fun compactionTokenCount(value: Int): String =
    java.text.NumberFormat.getIntegerInstance().format(value)

/** 摘要字段分隔符：旧格式用逗号，人文化摘要用间隔号。 */
private val RESULT_FIELD_SEPARATORS = listOf(", ", " · ")

private fun String.toSafeResultLogFields(): String = buildString {
    append("summary_chars=").append(this@toSafeResultLogFields.length)
    extractResultCode()?.let { resultCode ->
        append(", code=").append(resultCode.toSafeLogToken())
    }
}

private fun String.extractResultCode(): String? {
    val fieldStart = when {
        startsWith(RESULT_CODE_MARKER) -> 0
        else -> RESULT_FIELD_SEPARATORS
            .mapNotNull { separator ->
                indexOf(separator + RESULT_CODE_MARKER)
                    .takeIf { it >= 0 }
                    ?.plus(separator.length)
            }
            .minOrNull() ?: return null
    }
    val valueStart = fieldStart + RESULT_CODE_MARKER.length
    val valueEnd = RESULT_FIELD_SEPARATORS
        .mapNotNull { separator ->
            indexOf(separator, startIndex = valueStart).takeIf { it >= 0 }
        }
        .minOrNull() ?: length
    return substring(valueStart, valueEnd)
}
