package cn.yangrq.weixuan.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地图像生成工具（GPU / Vulkan 后端）。
 *
 * 注意：该工具会**独占内存**——执行期间 LLM 服务会被停止，
 * 工具返回前自动恢复。模型不应高频调用它（单次出图通常需数秒到数十秒）。
 */
internal object AgentImageToolCatalog {

    fun appendTo(tools: JSONArray, enabled: Boolean = true) {
        if (!enabled) return
        tools.put(
            AgentToolSchema.function(
                name = "generate_image",
                description = "Generate an image locally on the device GPU (Adreno Vulkan). " +
                    "Expensive: takes several to tens of seconds and temporarily suspends the " +
                    "conversation model to free memory (restored before this tool returns). " +
                    "Use only when the user explicitly asks for an image/illustration/picture, " +
                    "not for thumbnails, icons, or decoration. Returns the saved PNG path.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "prompt",
                                JSONObject()
                                    .put("type", "string")
                                    .put("minLength", 1)
                                    .put("maxLength", 1000)
                                    .put("description", "Image description. Be specific: subject, style, lighting, composition."),
                            )
                            .put(
                                "negative_prompt",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 500)
                                    .put("description", "Optional things to avoid (e.g. blurry, watermark, extra fingers)."),
                            )
                            .put(
                                "width",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("enum", JSONArray().put(512).put(768))
                                    .put("description", "Image width in pixels; default 512."),
                            )
                            .put(
                                "height",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("enum", JSONArray().put(512).put(768))
                                    .put("description", "Image height in pixels; default 512."),
                            )
                            .put(
                                "steps",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 4)
                                    .put("maximum", 30)
                                    .put("description", "Sampling steps; default 20. Fewer steps are faster but coarser."),
                            )
                            .put(
                                "cfg_scale",
                                JSONObject()
                                    .put("type", "number")
                                    .put("minimum", 1.0)
                                    .put("maximum", 20.0)
                                    .put("description", "Prompt adherence; default 7. Higher follows the prompt more strictly."),
                            )
                            .put(
                                "seed",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "Optional random seed for reproducibility; omit for random."),
                            ),
                    )
                    .put("required", JSONArray().put("prompt")),
            ),
        )
    }
}
