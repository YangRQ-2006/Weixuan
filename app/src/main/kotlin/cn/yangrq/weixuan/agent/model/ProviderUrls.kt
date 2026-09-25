package cn.yangrq.weixuan.agent.model

internal object ProviderUrls {
    /** 是否指向本机回环地址（微玄只允许本地推理服务，禁止联网模型）。 */
    fun isLoopbackUrl(baseUrl: String): Boolean {
        val normalized = baseUrl.trim().lowercase()
        return normalized.contains("127.0.0.1") ||
            normalized.contains("localhost") ||
            normalized.contains("[::1]")
    }

    fun normalizeBaseUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/')

    fun openAiChatCompletionsUrl(baseUrl: String): String =
        appendPath(baseUrl, "chat/completions")

    fun openAiResponsesUrl(baseUrl: String): String =
        appendPath(baseUrl, "responses")

    fun openAiModelsUrl(baseUrl: String): String =
        appendPath(baseUrl, "models")

    fun anthropicMessagesUrl(baseUrl: String): String =
        appendPath(baseUrl, "v1/messages")

    fun anthropicModelsUrl(baseUrl: String): String =
        appendPath(baseUrl, "v1/models")

    private fun appendPath(baseUrl: String, path: String): String =
        "${normalizeBaseUrl(baseUrl)}/${path.trimStart('/')}"
}
