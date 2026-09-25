package cn.yangrq.weixuan.local

import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.ProfilingData
import com.geniex.sdk.bean.ToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI wire 消息 ↔ GenieX ChatMessage 的转换层。
 *
 * Eta 的 Agent 层产出的是 OpenAI 风格 `messages`（含 content 数组、tool_calls、role=tool），
 * 这里把它适配成 GenieX 的 [ChatMessage]。
 *
 * [fallback] = true 表示当前 GGUF 的 chat template **不支持工具**（模板格式化结果里没有工具定义），
 * 此时 role=tool / assistant.tool_calls 无法被模板正确渲染，需要降级成纯文本轮次：
 * 工具调用渲染成 `<tool_call>{...}</tool_call>` 正文，工具结果渲染成 user 消息。
 */
internal object LocalChatConversion {

    private const val IMAGE_PLACEHOLDER = "[图片已省略：本地模型为纯文本模式]"

    fun toGenieXMessages(messages: JSONArray, fallback: Boolean): MutableList<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role").trim().ifBlank { "user" }
            val content = flattenContent(message.opt("content"))
            val toolCalls = message.optJSONArray("tool_calls")

            when {
                toolCalls != null && toolCalls.length() > 0 -> {
                    val calls = (0 until toolCalls.length()).mapNotNull { position ->
                        val item = toolCalls.optJSONObject(position) ?: return@mapNotNull null
                        val function = item.optJSONObject("function")
                        val name = function?.optString("name").orEmpty().ifBlank { item.optString("name") }
                        if (name.isBlank()) return@mapNotNull null
                        val arguments = when (val raw = function?.opt("arguments") ?: item.opt("arguments")) {
                            null -> "{}"
                            is JSONObject, is JSONArray -> raw.toString()
                            else -> raw.toString()
                        }
                        ToolCall(
                            id = item.optString("id").ifBlank { "call_$position" },
                            name = name,
                            arguments = arguments,
                        )
                    }
                    if (fallback) {
                        val rendered = calls.joinToString("\n") { call ->
                            "${LocalToolProtocol.TOOL_CALL_OPEN}" +
                                "{\"name\": \"${call.name}\", \"arguments\": ${call.arguments}}" +
                                LocalToolProtocol.TOOL_CALL_CLOSE
                        }
                        result.add(ChatMessage(role = "assistant", content = (content + "\n" + rendered).trim()))
                    } else {
                        result.add(ChatMessage(role = "assistant", content = content, toolCalls = calls))
                    }
                }

                role == "tool" -> {
                    val toolName = message.optString("name").takeIf { it.isNotBlank() } ?: "tool"
                    val toolCallId = message.optString("tool_call_id").ifBlank { "" }
                    if (fallback) {
                        result.add(
                            ChatMessage(
                                role = "user",
                                content = "[工具 $toolName 的返回结果]\n$content",
                            )
                        )
                    } else {
                        result.add(
                            ChatMessage(
                                role = "tool",
                                content = content,
                                toolCallId = toolCallId,
                                toolName = toolName,
                            )
                        )
                    }
                }

                else -> result.add(ChatMessage(role = role, content = content))
            }
        }
        return result
    }

    /** 把工具协议说明注入（或新增）system 消息。 */
    fun injectToolProtocol(messages: MutableList<ChatMessage>, tools: JSONArray) {
        val instruction = LocalToolProtocol.buildFallbackInstruction(tools)
        val systemIndex = messages.indexOfFirst { it.role == "system" }
        if (systemIndex >= 0) {
            val existing = messages[systemIndex]
            messages[systemIndex] = ChatMessage(role = "system", content = existing.content + instruction)
        } else {
            messages.add(0, ChatMessage(role = "system", content = instruction.trimStart()))
        }
    }

    /** content 可能是字符串，也可能是 OpenAI 的多模态 parts 数组。 */
    fun flattenContent(raw: Any?): String = when (raw) {
        null -> ""
        is String -> raw
        is JSONArray -> buildString {
            for (index in 0 until raw.length()) {
                val part = raw.optJSONObject(index) ?: continue
                when (val type = part.optString("type")) {
                    "text", "input_text" -> append(part.optString("text"))
                    "image_url", "input_image" -> append(IMAGE_PLACEHOLDER)
                    else -> {
                        val text = part.optString("text")
                        if (text.isNotBlank()) append(text) else append("[$type]")
                    }
                }
            }
        }
        is JSONObject -> raw.optString("text").ifBlank { raw.toString() }
        else -> raw.toString()
    }

    fun ProfilingData.toLocalUsage(): LocalUsage = LocalUsage(
        promptTokens = promptTokens,
        completionTokens = generatedTokens,
        tokensPerSecond = decodingSpeed,
        ttftMs = ttftMs,
    )

    /** 按模型名粗略估算权重内存占用（MB），用于加载前预检。 */
    fun estimateModelMb(name: String): Int {
        val lower = name.lowercase()
        val billions = Regex("(\\d+(?:\\.\\d+)?)\\s*b").find(lower)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: return LocalPerfTuner.MODEL_ESTIMATE_MB_8B
        val bytesPerParam = when {
            lower.contains("q8") -> 1.05
            lower.contains("q6") -> 0.85
            lower.contains("q5") -> 0.75
            lower.contains("q4") -> 0.58
            else -> 0.7
        }
        return (billions * 1000 * bytesPerParam).toInt().coerceAtLeast(600)
    }
}
