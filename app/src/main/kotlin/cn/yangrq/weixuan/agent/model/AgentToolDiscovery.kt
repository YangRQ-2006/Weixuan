package cn.yangrq.weixuan.agent.model

import cn.yangrq.weixuan.config.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具按需发现（2026-10-05，借鉴 OkHuman「能力说明按需翻，不常驻 prompt」的思路）。
 *
 * ## 要解决的硬问题
 * 微玄运行时是 **57 个工具**，实测「57 工具 + system + 一句用户话 = **6110 token**」（ctx 只有 6144）。
 * decode 随上下文线性衰减（每 1000 token ≈ −1.4 t/s）⇒ 上下文 6.1k 时只有 **4.2 t/s** ✗，
 * 而首字还要付一次 **≈8.8s** 的全量 prefill ✗。
 *
 * ## 做法：常驻"常用工具"，其余靠 `find_tools` 现查现挂
 * 1. 每一轮只发 **核心常驻集 + 被发现过的工具 + `find_tools` 本身**（约 1.5k token 量级）；
 * 2. 模型发现"手里没有能用的工具"时，调用 `find_tools(query)`；
 * 3. 本模块按关键词在**完整工具集**里检索，把命中工具**登记到 discoveredTools**，
 *    于是下一轮这些工具的**完整 schema** 就出现在工具列表里 —— 模型可以正常结构化调用它们。
 *
 * **能力一个都不少**（任何工具都能通过发现拿到 ✓），只是不再把 57 份说明书一次性塞进每一轮。
 *
 * ## 为什么不是"砍工具白名单"
 * 他们 2026-10-04 实测过：**静态** 12 工具白名单会把工具选对率从 84.6% 打到 50% ✗
 * （被砍掉的正是模型会用对的工具）。本方案的核心集是"兜底"，另外还提供**发现通道**，
 * 因此不存在"想用却没有"的永久损失 —— 代价只是冷门工具多一轮往返（+1~2s）。
 *
 * 由设置开关 [Prefs.Keys.AGENT_TOOL_DISCOVERY] 控制，默认关闭（不影响既有行为）。
 */
internal object AgentToolDiscovery {

    /** 目录入口工具名。 */
    const val TOOL_NAME = "find_tools"

    private const val MAX_HITS = 10
    private const val SUMMARY_CHARS = 90

    /**
     * 核心常驻工具：覆盖"看一眼屏幕 → 点/滑/输 → 开应用 → 跑命令/读文件"这条日常主链路。
     * 其余（设备查询/系统设置/闹钟/通知/记忆/技能/浏览器/文件写入/媒体/应用状态…）走发现通道。
     */
    val CORE_TOOL_NAMES: Set<String> = setOf(
        "get_current_context",
        "observe_screen",
        "wait",
        "wait_for_text",
        "tap",
        "tap_element",
        "tap_area",
        "long_press",
        "swipe",
        "scroll",
        "press_key",
        "input_text",
        "launch_app",
        "search_apps",
        "terminal",
        "read_file",
    )

    fun isEnabled(): Boolean = Prefs.isEnabled(Prefs.Keys.AGENT_TOOL_DISCOVERY)

    /** `find_tools` 自身的 schema（参数只有一个 query）。 */
    fun schema(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", TOOL_NAME)
                .put(
                    "description",
                    "按关键词查找工具。默认只加载常用工具；当你要做的事在当前工具列表里找不到对应工具时，" +
                        "先用本工具查询（例如「闹钟」「通知」「系统设置」「技能」「浏览器」「记忆」「文件写入」）。" +
                        "命中的工具会在下一轮出现在工具列表里，然后按正常方式调用它们。query 可用中文或英文关键词。"
                )
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "query",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "要查找的能力关键词，例如「音量」「短信验证码」「安装技能」。")
                            )
                        )
                        .put("required", JSONArray().put("query"))
                )
        )

    /**
     * 本轮实际下发的工具集：`核心集 ∪ 已发现 ∪ find_tools`。
     * 保持与 [all] 相同的顺序（确定性），这样工具块的文本稳定 → KV 前缀不容易作废。
     */
    fun selectFor(all: JSONArray, discovered: Set<String>): JSONArray {
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            val name = item.optJSONObject("function")?.optString("name") ?: continue
            if (name in CORE_TOOL_NAMES || name in discovered) out.put(item)
        }
        out.put(schema())
        return out
    }

    /** 发现结果。 */
    data class Discovery(val names: Set<String>, val content: String)

    /**
     * 在**完整工具集**里检索。命中工具的名字会被登记（由调用方并入 discoveredTools），
     * 文本结果直接作为 `find_tools` 的工具结果回给模型。
     */
    fun discover(query: String, all: JSONArray): Discovery {
        val tokens = tokenize(query)
        val scored = ArrayList<Triple<Int, String, String>>()
        for (i in 0 until all.length()) {
            val fn = all.optJSONObject(i)?.optJSONObject("function") ?: continue
            val name = fn.optString("name")
            if (name.isEmpty() || name == TOOL_NAME) continue
            val description = fn.optString("description")
            val lowerName = name.lowercase()
            val haystack = (name + " " + description).lowercase()
            var score = 0
            for (token in tokens) {
                if (token.isEmpty()) continue
                if (lowerName.contains(token)) score += 4 else if (haystack.contains(token)) score += 1
            }
            if (score > 0) {
                scored += Triple(score, name, description.replace('\n', ' ').take(SUMMARY_CHARS))
            }
        }
        val hits = scored.sortedByDescending { it.first }.take(MAX_HITS)
        val matched = JSONArray()
        val names = LinkedHashSet<String>()
        for ((_, name, summary) in hits) {
            names += name
            matched.put(JSONObject().put("tool", name).put("desc", summary))
        }
        val content = JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("matched_count", hits.size)
            .put("matched", matched)
            .put(
                "note",
                if (hits.isEmpty()) {
                    "没有匹配的工具。请换关键词，或直接用现有工具完成任务；确实做不到就说明缺什么能力。"
                } else {
                    "这些工具的完整定义已在下一轮工具列表中，直接按正常方式调用即可。"
                }
            )
            .toString()
        return Discovery(names, content)
    }

    /**
     * 极简分词：抽 ASCII 词（≥2 字符）+ 中文二元组。
     * 不引入分词器依赖，够用且零成本；宁可多召回（多召回只多花一点 prompt，不漏工具）。
     */
    private fun tokenize(query: String): List<String> {
        val text = query.trim().lowercase()
        if (text.isEmpty()) return emptyList()
        val tokens = LinkedHashSet<String>()
        Regex("[a-z0-9_]{2,}").findAll(text).forEach { tokens += it.value }
        val cjk = text.filter { it.code in 0x4E00..0x9FFF }
        for (i in 0 until cjk.length - 1) tokens += cjk.substring(i, i + 2)
        if (cjk.length == 1) tokens += cjk
        return tokens.toList()
    }
}
