package cn.yangrq.weixuan.agent.runtime

internal data class AgentTokenUsage(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    /** 本地引擎实测解码速度（tok/s）。 */
    val tokensPerSecond: Double? = null,
    /** 首 token 延迟（毫秒）。 */
    val ttftMs: Double? = null,
) {
    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null &&
            tokensPerSecond == null &&
            ttftMs == null
}
