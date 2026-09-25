package cn.yangrq.weixuan.data.provider

import cn.yangrq.weixuan.data.model.AnthropicProviderSetting
import cn.yangrq.weixuan.data.model.OpenAiCompatibleProviderSetting
import cn.yangrq.weixuan.data.model.OpenAiEndpointMode
import cn.yangrq.weixuan.data.model.ProviderSetting
import cn.yangrq.weixuan.data.model.ProviderSourceTypes

internal object BuiltinProviders {
    const val DEFAULT_SYSTEM_PROMPT =
        "你是微玄（WeiXuan），运行在 Android 手机上的端侧 AI 智能体。" +
            "你的名字出自《道德经》「玄之又玄，众妙之门」——微，是端侧设备的形态；玄，是深度智能与算力。" +
            "你完全离线运行：模型推理全程在手机的 NPU/GPU 上完成，数据不出设备。" +
            "你可以回答问题、与用户交流，也可以通过当前可用的工具了解设备情况并执行操作。" +
            "回答使用用户的语言，简洁、直接、自然。"

    const val OPENAI_ID = "builtin-openai"
    const val ANTHROPIC_ID = "builtin-anthropic"
    const val BAILIAN_ID = "builtin-dashscope"
    const val DEEPSEEK_ID = "builtin-deepseek"
    const val KIMI_ID = "builtin-kimi"
    const val MIMO_ID = "builtin-mimo"
    const val MINIMAX_ID = "builtin-minimax"
    const val STEPFUN_ID = "builtin-stepfun"
    const val SILICONFLOW_ID = "builtin-siliconflow"
    const val OPENROUTER_ID = "builtin-openrouter"

    /** 本地模型（GenieX NPU/GPU）Provider：baseUrl 指向 App 内回环 OpenAI 兼容服务。 */
    const val LOCAL_GENIEX_ID = "builtin-local-geniex"

    val PROVIDERS: List<ProviderSetting> = listOf(
        // 本地模型放在首位：默认完全不联网，baseUrl 指向本机回环服务。
        OpenAiCompatibleProviderSetting(
            id = LOCAL_GENIEX_ID,
            name = "本地模型（GenieX NPU）",
            baseUrl = "http://127.0.0.1:18787/v1",
            sourceType = ProviderSourceTypes.CUSTOM,
            // 回环服务不需要真实密钥，但运行配置校验要求非空，这里用占位值。
            apiKey = "local",
            isBuiltIn = true,
            sortOrder = -1,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            endpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
            models = listOf(
                cn.yangrq.weixuan.data.model.Model(
                    id = "local-geniex-default",
                    modelId = "local",
                    displayName = "本地模型（GenieX NPU/GPU）",
                    isBuiltIn = true,
                    sortOrder = 0,
                    inputModalities = listOf(cn.yangrq.weixuan.data.model.Model.TEXT_MODALITY),
                    outputModalities = listOf(cn.yangrq.weixuan.data.model.Model.TEXT_MODALITY),
                    attachment = false,
                    toolCall = true,
                    reasoning = false,
                    source = cn.yangrq.weixuan.data.model.ModelSource.CATALOG,
                )
            ),
        ),
    )

    fun providerById(id: String): ProviderSetting? =
        PROVIDERS.firstOrNull { it.id == id }
}
