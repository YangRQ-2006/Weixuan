package cn.yangrq.weixuan.local

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地模型工具协议。
 *
 * 两条路径：
 * 1. **原生路径**：GGUF 的 chat template 支持 tools（如 Qwen3），直接
 *    `applyChatTemplate(messages, toolsJson)`，由模板注入工具定义，模型按训练格式输出；
 * 2. **回退路径**：模板不支持工具（格式化结果里找不到工具名）时，由本对象生成一段
 *    文本工具协议追加到 system 消息，并规定统一输出格式
 *    `<tool_call>{"name": "...", "arguments": {...}}</tool_call>`。
 *
 * 两种路径的输出都由 [LocalToolStreamFilter] + [LocalToolCallParser] 解析，格式兼容。
 */
object LocalToolProtocol {

    private const val TAG = "EtaLocalTool"

    const val TOOL_CALL_OPEN = "<tool_call>"
    const val TOOL_CALL_CLOSE = "</tool_call>"

    /** 格式化后的 prompt 里是否真的出现了工具定义（判定模板是否支持 tools）。 */
    fun isToolCallingSupported(formattedPrompt: String, tools: JSONArray): Boolean {
        if (tools.length() == 0) return false
        var matched = 0
        for (index in 0 until tools.length()) {
            val name = toolName(tools.optJSONObject(index)) ?: continue
            if (formattedPrompt.contains(name)) matched++
        }
        val supported = matched > 0
        Log.i(TAG, "native tool template supported=$supported matched=$matched/${tools.length()}")
        return supported
    }

    fun toolName(tool: JSONObject?): String? {
        if (tool == null) return null
        val function = tool.optJSONObject("function") ?: tool
        val name = function.optString("name").trim()
        return name.takeIf { it.isNotEmpty() }
    }

    /** 生成回退工具协议文本（追加到 system 消息尾部）。 */
    fun buildFallbackInstruction(tools: JSONArray): String {
        val builder = StringBuilder()
        builder.append("\n\n【工具调用协议】\n")
        builder.append("你可以调用以下工具来获取信息或执行操作。")
        builder.append("当你需要调用工具时，**只输出**下面格式的文本，不要输出任何解释、不要使用代码围栏：\n")
        builder.append("$TOOL_CALL_OPEN{\"name\": \"工具名\", \"arguments\": {\"参数名\": 值}}$TOOL_CALL_CLOSE\n")
        builder.append("一次需要多个工具时，连续输出多个 $TOOL_CALL_OPEN ... $TOOL_CALL_CLOSE 块。\n")
        builder.append("不需要调用工具时，直接用自然语言回答用户。\n")
        builder.append("可用工具列表：\n")
        for (index in 0 until tools.length()) {
            val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
            builder.append("${index + 1}. name: ${function.optString("name")}\n")
            val description = function.optString("description")
            if (description.isNotBlank()) builder.append("   description: $description\n")
            val parameters = function.optJSONObject("parameters")
            if (parameters != null) {
                builder.append("   parameters(JSON Schema): ${parameters.toString().take(4000)}\n")
            }
        }
        return builder.toString()
    }
}

/** 过滤器输出的语义事件。 */
sealed interface LocalFilterEvent {
    data class Visible(val text: String) : LocalFilterEvent
    data class Thinking(val text: String) : LocalFilterEvent
    data class Calls(val calls: List<LocalToolCall>) : LocalFilterEvent
}

/**
 * 流式工具调用过滤器。
 *
 * 目标：把模型原始 token 流拆成「可见正文 / 思考内容 / 工具调用」三类，且**不把工具调用的
 * 原始标记暴露成正文**（否则 Agent 会把工具 JSON 当聊天内容展示）。
 *
 * 做法：维护一个前瞻缓冲，只在确认不是标记起始时才把文本作为正文吐出；
 * 命中工具标记后进入捕获态，直到闭合标记或流结束才解析。
 */
class LocalToolStreamFilter {

    private companion object {
        const val TAG = "EtaLocalToolFilter"
        const val THINK_OPEN = " thinking"

        /** 思考块闭合标记（不同 chat template 约定不同，取最先出现者）。 */
        val THINK_CLOSES = listOf("</think" + ">", "<|/think|>", "<｜end▁of▁thinking｜>")

        /** 工具调用起始标记 → 对应闭合标记（null 表示直到流结束才结束捕获）。 */
        val TOOL_MARKERS: List<Pair<String, String?>> = listOf(
            "<tool_call>" to "</tool_call>",
            "<tool_calls>" to "</tool_calls>",
            "<|tool_call|>" to "<|/tool_call|>",
            "<|python_tag|>" to null,
            "```tool_call" to "```",
            "```json" to "```",
        )

        /** 无标记裸 JSON 工具调用的启发式开头。 */
        val BARE_JSON_STARTS = listOf("{\"name\"", "{\"tool_calls\"", "[{\"name\"", "{\n  \"name\"")
    }

    private enum class State { NORMAL, THINKING, CAPTURE }

    private val buffer = StringBuilder()
    private var state = State.NORMAL
    private var captureCloser: String? = null
    private var visibleEmitted = 0
    private var pendingCallIndex = 0

    /** 喂入一个 token，返回可立即产出的事件。 */
    fun feed(token: String): List<LocalFilterEvent> {
        if (token.isEmpty()) return emptyList()
        buffer.append(token)
        val events = mutableListOf<LocalFilterEvent>()
        drain(events, atEnd = false)
        return events
    }

    /** 流结束，产出剩余内容（含未闭合的工具调用）。 */
    fun finish(): List<LocalFilterEvent> {
        val events = mutableListOf<LocalFilterEvent>()
        drain(events, atEnd = true)
        when (state) {
            State.THINKING -> {
                if (buffer.isNotEmpty()) {
                    events.add(LocalFilterEvent.Thinking(buffer.toString()))
                    buffer.setLength(0)
                }
            }
            State.CAPTURE -> {
                val payload = buffer.toString()
                buffer.setLength(0)
                emitCalls(payload, events)
            }
            State.NORMAL -> {
                if (buffer.isNotEmpty()) {
                    events.add(LocalFilterEvent.Visible(buffer.toString()))
                    buffer.setLength(0)
                }
            }
        }
        state = State.NORMAL
        return events
    }

    private fun drain(events: MutableList<LocalFilterEvent>, atEnd: Boolean) {
        var progressed = true
        while (progressed) {
            progressed = false
            val text = buffer.toString()
            when (state) {
                State.NORMAL -> {
                    when {
                        text.startsWith(THINK_OPEN) -> {
                            buffer.delete(0, THINK_OPEN.length)
                            state = State.THINKING
                            progressed = true
                        }
                        else -> {
                            val marker = TOOL_MARKERS.firstOrNull { text.startsWith(it.first) }
                            if (marker != null) {
                                buffer.delete(0, marker.first.length)
                                state = State.CAPTURE
                                captureCloser = marker.second
                                progressed = true
                            } else if (visibleEmitted == 0 && !atEnd &&
                                BARE_JSON_STARTS.any { text.startsWith(it) }
                            ) {
                                // 无标记裸 JSON：整体当作工具调用捕获
                                state = State.CAPTURE
                                captureCloser = null
                                progressed = true
                            } else {
                                val hold = holdBackLength(text)
                                if (text.length > hold) {
                                    val visible = text.substring(0, text.length - hold)
                                    buffer.delete(0, visible.length)
                                    visibleEmitted += visible.length
                                    if (visible.isNotEmpty()) {
                                        events.add(LocalFilterEvent.Visible(visible))
                                    }
                                }
                            }
                        }
                    }
                }
                State.THINKING -> {
                    val close = earliestClose(text)
                    val maxCloseLen = THINK_CLOSES.maxOf { it.length }
                    if (close != null) {
                        val (closeAt, marker) = close
                        if (closeAt > 0) events.add(LocalFilterEvent.Thinking(text.substring(0, closeAt)))
                        buffer.delete(0, closeAt + marker.length)
                        state = State.NORMAL
                        progressed = true
                    } else if (text.length > maxCloseLen) {
                        val emit = text.substring(0, text.length - maxCloseLen + 1)
                        buffer.delete(0, emit.length)
                        if (emit.isNotEmpty()) events.add(LocalFilterEvent.Thinking(emit))
                    }
                }
                State.CAPTURE -> {
                    val closer = captureCloser
                    if (closer != null) {
                        val closeAt = text.indexOf(closer)
                        if (closeAt >= 0) {
                            val payload = text.substring(0, closeAt)
                            buffer.delete(0, closeAt + closer.length)
                            emitCalls(payload, events)
                            state = State.NORMAL
                            captureCloser = null
                            progressed = true
                        }
                    }
                    // closer == null：一直缓冲到流结束
                }
            }
        }
    }

    private fun emitCalls(payload: String, events: MutableList<LocalFilterEvent>) {
        if (payload.isBlank()) return
        val calls = LocalToolCallParser.parse(payload, pendingCallIndex)
        if (calls.isEmpty()) {
            Log.w(TAG, "工具调用解析失败，降级为正文：${payload.take(400)}")
            events.add(LocalFilterEvent.Visible(payload))
            return
        }
        pendingCallIndex += calls.size
        events.add(LocalFilterEvent.Calls(calls))
    }

    /** 找到最先出现的思考闭合标记。 */
    private fun earliestClose(text: String): Pair<Int, String>? {
        var bestAt = -1
        var bestMarker: String? = null
        for (marker in THINK_CLOSES) {
            val at = text.indexOf(marker)
            if (at >= 0 && (bestAt < 0 || at < bestAt)) {
                bestAt = at
                bestMarker = marker
            }
        }
        val marker = bestMarker ?: return null
        return if (bestAt < 0) null else bestAt to marker
    }

    /** 计算必须扣住的后缀长度：任何标记的最长「合法前缀」后缀。 */
    private fun holdBackLength(text: String): Int {
        var max = 0
        val candidates = TOOL_MARKERS.map { it.first } + listOf(THINK_OPEN)
        for (marker in candidates) {
            val limit = minOf(marker.length - 1, text.length)
            for (length in limit downTo 1) {
                if (text.regionMatches(text.length - length, marker, 0, length)) {
                    if (length > max) max = length
                    break
                }
            }
        }
        return max
    }
}

/** 工具调用 JSON 解析器（对 8B 级模型的常见格式瑕疵做容错）。 */
object LocalToolCallParser {

    private const val TAG = "EtaLocalToolParser"

    fun parse(payload: String, startIndex: Int = 0): List<LocalToolCall> {
        val cleaned = clean(payload)
        if (cleaned.isEmpty()) return emptyList()

        val start = cleaned.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return emptyList()
        val body = cleaned.substring(start)

        for (candidate in candidates(body)) {
            val calls = runCatching { parseJson(candidate, startIndex) }.getOrNull()
            if (!calls.isNullOrEmpty()) return calls
        }
        Log.w(TAG, "无法解析工具调用载荷：${payload.take(500)}")
        return emptyList()
    }

    private fun clean(payload: String): String {
        var text = payload.trim()
        for (fence in listOf("```tool_call", "```json", "```")) {
            if (text.startsWith(fence)) text = text.removePrefix(fence).trim()
        }
        text = text.removeSuffix("```").trim()
        return text
    }

    /** 逐步放宽的候选串：原样 → 去尾逗号 → 截断到最后一个可闭合处 → 补全括号。 */
    private fun candidates(body: String): List<String> {
        val result = linkedSetOf<String>()
        result.add(body)
        val noTrailingComma = Regex(",(\\s*[}\\]])").replace(body) { it.groupValues[1] }
        result.add(noTrailingComma)
        result.add(balance(noTrailingComma))

        var index = noTrailingComma.length - 1
        var attempts = 0
        while (index >= 0 && attempts < 25) {
            val ch = noTrailingComma[index]
            if (ch == '}' || ch == ']') {
                result.add(balance(noTrailingComma.substring(0, index + 1)))
                attempts++
            }
            index--
        }
        return result.toList()
    }

    /** 补全缺失的右括号/右方括号（模型截断常见）。 */
    private fun balance(text: String): String {
        var braces = 0
        var brackets = 0
        var inString = false
        var escaped = false
        for (ch in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> braces++
                '}' -> braces--
                '[' -> brackets++
                ']' -> brackets--
            }
        }
        if (braces <= 0 && brackets <= 0) return text
        val builder = StringBuilder(text)
        if (inString) builder.append('"')
        repeat(braces.coerceAtLeast(0)) { builder.append('}') }
        repeat(brackets.coerceAtLeast(0)) { builder.append(']') }
        return builder.toString()
    }

    private fun parseJson(candidate: String, startIndex: Int): List<LocalToolCall> {
        val trimmed = candidate.trim()
        val calls = mutableListOf<LocalToolCall>()
        when {
            trimmed.startsWith("[") -> {
                val array = JSONArray(trimmed)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    toCall(item, startIndex + calls.size)?.let(calls::add)
                }
            }
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                val toolCalls = obj.optJSONArray("tool_calls")
                when {
                    toolCalls != null -> {
                        for (index in 0 until toolCalls.length()) {
                            val item = toolCalls.optJSONObject(index) ?: continue
                            toCall(item, startIndex + calls.size)?.let(calls::add)
                        }
                    }
                    else -> toCall(obj, startIndex + calls.size)?.let(calls::add)
                }
            }
            else -> return emptyList()
        }
        return calls
    }

    private fun toCall(item: JSONObject, position: Int): LocalToolCall? {
        var function = item.optJSONObject("function") ?: item
        val nested = function.optJSONObject("function")
        if (nested != null) function = nested

        val name = function.optString("name").trim()
            .ifBlank { item.optString("tool_name").trim() }
        if (name.isEmpty() || name == "null") return null

        val rawArguments = when {
            function.has("arguments") && !function.isNull("arguments") -> function.opt("arguments")
            item.has("arguments") && !item.isNull("arguments") -> item.opt("arguments")
            else -> null
        }
        val argumentsJson = when (rawArguments) {
            null -> "{}"
            is JSONObject, is JSONArray -> rawArguments.toString()
            is String -> rawArguments.trim().ifBlank { "{}" }
            else -> rawArguments.toString()
        }
        val id = item.optString("id").trim().takeIf { it.isNotEmpty() && it != "null" } ?: "call_$position"
        return LocalToolCall(index = position, id = id, name = name, argumentsJson = argumentsJson)
    }
}
