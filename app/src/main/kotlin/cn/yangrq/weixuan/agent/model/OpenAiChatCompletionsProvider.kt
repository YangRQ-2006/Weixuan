package cn.yangrq.weixuan.agent.model

import cn.yangrq.weixuan.agent.runtime.AgentRunController
import cn.yangrq.weixuan.agent.runtime.AgentTokenUsage
import cn.yangrq.weixuan.data.model.OpenAiEndpointMode
import cn.yangrq.weixuan.local.LocalSettings
import cn.yangrq.weixuan.data.model.ProviderSourceTypes
import cn.yangrq.weixuan.data.provider.ProviderSourceRegistry
import cn.yangrq.weixuan.local.GenieXLocalEngine
import cn.yangrq.weixuan.local.LlamaServerProcess
import cn.yangrq.weixuan.local.LocalEngineStatus
import cn.yangrq.weixuan.local.LocalMemoryModel
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "OpenAiTools"

/**
 * 本地工具 schema 的 token 预算（2026-10-04 按实测重定）。
 *
 * **实测数据**（脚本见 `scripts/bench/agent_eval.py`，服务端 `usage.prompt_tokens`）：
 * - 57 个工具的 schema + system + 一句用户话 = **6110 token**，即 ≈**107 token/工具**
 * - 全部 59 个工具 ≈ **6324 token**，而 `ctx = 6144` → **schema 本身就装不下**
 * - 把描述全删光也只能降到 ≈4225，留不出对话空间 → "精简描述"这条路**算术上就不成立**
 *
 * **预算推导**（`ctx 6144 = 工具 + 系统提示 + 对话历史 + 输出预留`）：
 * ```
 * 6144 − 系统提示(~300) − 输出预留(~800) − 对话历史(留 2000)  →  工具 ≤ ~3000
 * ```
 * 所以取 3400：既能让精选集通过，又给对话留出 ~2400 的活口。
 *
 * ⚠️ 这个常量是**跟着 ctx 走的**：若 `DEFAULT_CTX_CAP` 改了，必须重新推导。
 */
private const val LOCAL_TOOLS_TOKEN_BUDGET = 3400

/**
 * 对话历史在「历史 + 工具」可用额度里的占比（另一半给工具）。
 *
 * 额度推导（**全部跟着真实 ctx 走**，见 [localCtx]）：
 * ```
 * 可用 = ctx − 输出预留(1024) − 安全余量(512)
 *  ctx 6144 → 可用 4608 → 历史 2304 / 工具 2304
 *  ctx 8192 → 可用 6656 → 历史 3328 / 工具 3328（工具再受 LOCAL_TOOLS_TOKEN_BUDGET 上限约束）
 * ```
 * 实测印证：`in=6122 / ctx=6144` 时系统提示 + 12 条历史占了 ≈3260 token，
 * 超出 2304 的配额 ⇒ 会被新的 token 预算裁掉，这正是「运行失败」的修复点。
 */
private const val LOCAL_HISTORY_SHARE_PERCENT = 50

/** 单步输出预留（Agent 单步通常 200~800 tokens，留 1024 覆盖带工具参数的长回复）。 */
private const val LOCAL_OUTPUT_RESERVE_TOKENS = 1024

/** 对话历史与工具估算的误差余量（估算取 chars/3 偏大，这里再留一层，防真实 token 超估）。 */
private const val LOCAL_PROMPT_SAFETY_TOKENS = 512

/** 工具预算下限：低于这个数就不值得再砍了（再少还不如直接用核心白名单）。 */
private const val LOCAL_TOOLS_MIN_BUDGET = 600

/** 本地引擎处于 LOADING 时，模型请求最多等它就绪多久（实测加载 ~13 秒，留足余量）。 */
private const val LOCAL_READY_WAIT_MS = 120_000L

/**
 * 本地引擎就绪等待 —— 2026-10-04 修复「一发消息就提示模型请求重试」。
 *
 * **实测证据**（logcat，同一段 21 秒）：
 * ```
 * 12:09:08.778  libllama-server.so: fastrpc/dspqueue 初始化（HTP session 打开中）
 * 12:09:14.413  重试 round=1  code=HTTP_503
 * 12:09:16.461  重试 round=2  code=HTTP_503
 * 12:09:20.525  重试 round=3  code=HTTP_503
 * 12:09:21.772  LlamaServer: llama-server 就绪：http://127.0.0.1:18787/v1
 * ```
 * **503 是 llama-server 子进程自己在加载期间返回的**（不是 App 的包装服务）。
 * 而 Agent 的 run 路径上没有任何"等引擎就绪"的逻辑，只靠退避重试（2s/4s/8s）硬熬 ——
 * 用户看到的就是「模型请求重试」。
 *
 * 注意 18787 的归属（这是上一次修复改错位置的原因）：
 * `LocalSettings.DEFAULT_PORT = 18787`、`BuiltinProviders` 的 baseUrl 指向 `127.0.0.1:18787/v1`，
 * 而自建 runtime 的**子进程 llama-server 也监听 18787** → **Agent 是直连子进程的**，
 * `LocalOpenAiServer`（App 自己的包装服务）并不在这条链路上。
 *
 * 这里只对**回环地址**生效，且只在引擎确实处于 LOADING 时等待：
 * 网络 provider（云端）完全不受影响；"没在加载"的情况仍按原样返回 503，语义不变。
 */
private fun awaitLocalEngineReadyIfLoopback(baseUrl: String) {
    if (!ProviderUrls.isLoopbackUrl(baseUrl)) return
    val engine = GenieXLocalEngine
    if (engine.isReady) return
    if (engine.state.value.status != LocalEngineStatus.LOADING) return
    Log.i(TAG, "本地引擎加载中，模型请求等待就绪（最多 ${LOCAL_READY_WAIT_MS / 1000}s）…")
    val settled = runBlocking {
        withTimeoutOrNull(LOCAL_READY_WAIT_MS) {
            engine.state.first {
                it.status == LocalEngineStatus.READY || it.status == LocalEngineStatus.ERROR
            }
        }
    }
    Log.i(TAG, "本地引擎等待结束：${settled?.status ?: "超时"}")
}

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

        // 2026-10-04 修复：本地引擎在加载模型期间会对任何请求回 HTTP 503 —— 发请求前先等它就绪。
        awaitLocalEngineReadyIfLoopback(config.baseUrl)

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
     * 本地模型思考开关：llama-server 不支持 reasoning_effort 参数，改用 Qwen3 原生的
     * `/no_think` 软开关（追加到 system 消息末尾）。关闭思考后本地推理显著加速
     * （思考 token 常占生成量的大头）。云端模型走标准 reasoning_effort，不受影响。
     */
    private fun applyLocalThinkingSwitch(
        messages: JSONArray,
        config: AgentModelClient.ModelConfig,
    ): JSONArray {
        if (LocalSettings.thinkingEnabled) return messages
        val url = config.baseUrl
        if (!url.contains("127.0.0.1") && !url.contains("localhost")) return messages
        if (messages.length() == 0) return messages
        // Qwen3 官方软开关须落在"最后一条 user 消息"末尾（放 system 中不生效）
        for (i in messages.length() - 1 downTo 0) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == "user") {
                m.put("content", m.optString("content") + " /no_think")
                return messages
            }
        }
        return messages
    }

    /**
     * 历史裁剪（本地小窗口优化）：只保留最近 maxCount 条消息，并保证 tool 消息与其
     * 前置 assistant（含 tool_calls）配对完整——否则 llama.cpp 会以 400 拒绝请求。
     * 云端大窗口模型不受影响（消息数未超限时原样返回）。
     */
    /**
     * 按 **token 预算**压缩对话历史 —— 2026-10-04 修复「运行失败」。
     *
     * **旧实现的问题**：只按**条数**截断（`maxCount = 12`），完全不看 token 数：
     * ```kotlin
     * if (messages.length() <= maxCount) return messages
     * var start = messages.length() - maxCount
     * ```
     * 12 条消息里只要夹着几条 `observe_screen` 的 UI 树 dump（单条可达上千 token），
     * 就能把上下文吃光。实测代价：
     * ```
     * 13:09:14  prompt eval = 12004ms / 6122 tokens       ← 6122 / ctx 6144
     * 13:09:14  slot release: n_tokens = 6138, truncated = 0
     * 13:09:15  srv send_error: task id = 21  →  run_failed
     * ```
     *
     * **新实现**：从最新往回累加，累计超出 [LOCAL_HISTORY_TOKEN_BUDGET] 就丢弃更旧的消息。
     * 另有一条硬要求：**不能以 `role=tool` 开头** —— 它的 `assistant(tool_calls)` 一旦被截掉，
     * 请求会被判非法（这也是旧实现在此处的处理，保留）。
     *
     * 与工具预算的分工：历史先压到 ≤ [LOCAL_HISTORY_TOKEN_BUDGET]，
     * 工具再拿 `ctx − 历史估算 − 输出预留 − 安全余量`（见 [toolTokenBudget]）。
     * 两者之和恒 ≤ ctx，不会再出现"历史一长就爆"。
     */
    private fun trimHistory(messages: JSONArray): JSONArray {
        if (messages.length() == 0) return messages
        val budget = localHistoryBudget()
        var total = 0
        var start = messages.length()
        var i = messages.length() - 1
        while (i >= 0) {
            val cost = ((messages.optJSONObject(i)?.toString()?.length ?: 0) / 3) + 4
            if (total + cost > budget) break
            total += cost
            start = i
            i--
        }
        // 不能以 tool 结果开头（其配对的 assistant(tool_calls) 已被截掉 → 请求非法）
        while (start < messages.length() && messages.optJSONObject(start)?.optString("role") == "tool") {
            start++
        }
        // 极端情况：单条就超预算 → 至少保留最后一条，总比空消息列表好
        if (start >= messages.length()) start = messages.length() - 1
        if (start == 0) return messages
        val out = JSONArray()
        for (k in start until messages.length()) {
            messages.optJSONObject(k)?.let { out.put(it) }
        }
        Log.i(
            TAG,
            "历史按 token 预算压缩：${messages.length()} 条 → ${out.length()} 条（估算 $total token，" +
                "预算 $budget）",
        )
        return out
    }

    /**
     * 超预算时优先保留的工具（**按用途精选的 28 个**，不再是原来那 12 个）。
     *
     * 为什么换掉原来那份：原白名单只有 12 个基础操作工具，把
     * `read_file / write_file / list_directory / set_clipboard / get_clipboard /
     * open_system_panel / skills_list / memory_get / read_image / recent_notifications /
     * get_setting / set_alarm / set_timer` 全部砍掉了 —— 实测（26 条真实中文语句）显示
     * 模型在这些场景只能拿近似工具硬顶（`read_file→run_command`、`open_system_panel→press_key`、
     * `browser_use→launch_app`），**工具选择准确率从 84.6% 掉到 50%**。
     *
     * 选取原则：覆盖「观察 → 操作 → 验证」主循环 + 文件/剪贴板 + 最常用的设备控制 + 记忆/技能，
     * 并刻意排除冗余项（如 `terminal` 与 `run_command` 重复、`open_uri` 已被 `launch_app` 覆盖）。
     *
     * ⚠️ **这份名单尚未经过实测校准** —— 按实测 ≈107 token/工具，28 个 ≈3000 token，落在 3400 预算内。
     * 但"选了这 28 个之后准确率是多少"必须用 `agent_eval.py` 重新测，不能凭感觉认为它更好。
     */
    private val preferredToolNames = linkedSetOf(
        // 观察 / 上下文
        "get_current_context", "observe_screen", "read_image", "recent_notifications",
        // 应用
        "launch_app", "search_apps",
        // 手势
        "tap", "tap_element", "long_press_element", "swipe", "scroll",
        // 文本输入
        "input_text", "press_key", "set_clipboard", "get_clipboard",
        // 等待与校验
        "wait", "wait_for_text",
        // 文件
        "read_file", "write_file", "list_directory",
        // 命令
        "run_command",
        // 系统面板与设置
        "open_system_panel", "get_setting",
        // 设备
        "set_alarm", "set_timer",
        // 记忆与技能
        "memory_get", "memory_write", "skills_list",
    )

    /** 核心兜底白名单（比精选集更小，仅当精选集仍超预算时才启用）。 */
    private val coreToolNames = setOf(
        "get_current_context", "launch_app", "search_apps",
        "tap", "tap_element", "input_text", "press_key", "swipe",
        "observe_screen", "wait", "wait_for_text", "run_command",
    )

    /**
     * 工具裁剪 —— 2026-10-04 按实测重做（三层降级）。
     *
     * **旧逻辑是负优化**：原来只要 `tools.length() > 40` 就砍成 12 个核心工具，实测：
     *   · 39 工具 → 选对 **22/26 = 84.6%**（prompt 4786 token，装得下）
     *   · 白名单 12 工具 → 选对 **13/26 = 50%**
     * 因为被砍掉的正是模型会用对的工具。
     *
     * **新逻辑**：全量装得下就一个都不砍；装不下才退到 28 个精选工具；
     * 精选仍超预算（ctx 更小或工具更多时）再退到 12 个核心白名单兜底。
     * 每层都打日志，便于线上核对"模型到底看得到多少工具"。
     */
    private fun compactTools(
        tools: JSONArray,
        messages: JSONArray,
        config: AgentModelClient.ModelConfig,
    ): JSONArray {
        if (tools.length() == 0) return tools
        // 实测标定：57 工具 ≈ 6110 token、JSON 文本 ≈25200 字符 → 约 4.1 字符/token（以实测为准）。
        // 这里取 3（估偏大）保守处理：宁可少给，也不要估小了把上下文撑爆。
        val est = tools.toString().length / 3
        val budget = toolTokenBudget(messages, config)
        if (est <= budget) {
            Log.i(TAG, "工具集完整下发：${tools.length()} 个，估算 $est token（本次预算 $budget）")
            return tools
        }

        // ① 先按优先级取精选工具。
        //    注意：**不是所有名字都一定存在** —— `AgentToolCapabilities.project()` 会按权限过滤
        //    （通知读取 / 短信 / 健康数据等需要特殊授权的会被摘掉）。
        //    实测某次 59 个运行时工具里，我的 28 个精选只命中 18 个。
        val out = JSONArray()
        val byName = HashMap<String, JSONObject>()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            byName[t.optJSONObject("function")?.optString("name").orEmpty()] = t
        }
        for (name in preferredToolNames) byName[name]?.let { out.put(it) }

        // ② 预算还有余量就把其余可用工具按原顺序补进来。
        //    旧写法只用精选名单，命中 18 个后还剩 ~850 token 预算白白闲置，
        //    而运行时另有 ~41 个可用工具（唤醒/通知/媒体控制等）完全没机会出现。
        val selected = HashSet<String>()
        for (i in 0 until out.length()) {
            selected.add(out.optJSONObject(i)?.optJSONObject("function")?.optString("name").orEmpty())
        }
        var chars = out.toString().length
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val n = t.optJSONObject("function")?.optString("name").orEmpty()
            if (n in selected) continue
            val cost = t.toString().length + 1
            if ((chars + cost) / 3 > budget) break
            out.put(t)
            chars += cost
        }

        val estOut = out.toString().length / 3
        if (out.length() == 0) {
            Log.w(TAG, "精选名单全部不存在于当前可用工具集，回退为全量（可能超预算，请检查 capabilities）")
            return tools
        }
        val sb = StringBuilder()
        for (i in 0 until out.length()) {
            if (i > 0) sb.append(',')
            sb.append(out.optJSONObject(i)?.optJSONObject("function")?.optString("name"))
        }
        Log.w(
            TAG,
            "工具集 ${tools.length()} 个估算 $est token 超本次预算 $budget，" +
                "按优先级填充为 ${out.length()} 个（估算 $estOut token）：$sb",
        )
        return out
    }

    /**
     * 本次请求的工具 token 预算 —— **必须扣掉真实的消息体积**（2026-10-04 修复「运行失败」）。
     *
     * **踩过的坑**：上一版用固定常量 3400，完全没考虑对话历史。实测代价：
     * ```
     * 13:09:14  slot print_timing: prompt eval = 12004ms / 6122 tokens
     * 13:09:14  slot release: n_tokens = 6138, truncated = 0     ← ctx 6144 已满
     * 13:09:15  srv send_error: task id = 21                     → 13:09:15.868 run_failed
     * ```
     * `in=6122` 里系统提示 + 对话历史就占了 ≈3260 token（我原以为只有 ~300，差了一个数量级），
     * 27 个工具（≈2860）一加就爆。历史越长越严重 —— **任何固定预算都必然会被历史挤爆**。
     *
     * 所以改为动态：`预算 = ctx − 消息估算 − 输出预留 − 安全余量`。
     * 历史短时自然给得多（上限仍是 [LOCAL_TOOLS_TOKEN_BUDGET]），历史长时自动收缩，
     * 宁可少给工具也不要让请求进不去上下文。
     */
    private fun toolTokenBudget(messages: JSONArray, config: AgentModelClient.ModelConfig): Int {
        // 云端 provider 上下文大得多，沿用原来的固定上限，避免误伤。
        if (!ProviderUrls.isLoopbackUrl(config.baseUrl)) return LOCAL_TOOLS_TOKEN_BUDGET
        val avail = localPromptBudget(localCtx()) - messages.toString().length / 3
        return avail.coerceIn(LOCAL_TOOLS_MIN_BUDGET, LOCAL_TOOLS_TOKEN_BUDGET)
    }

    /**
     * **实际生效**的上下文窗口。
     * 窗口由内存阶梯 8192/6144/4096 现算（[LocalMemoryModel.plan]），未必等于 `DEFAULT_CTX_CAP`；
     * 预算必须按真实窗口分配，否则阶梯落到 4096 时按 6144 算的预算会撑爆上下文。
     */
    private fun localCtx(): Int =
        LlamaServerProcess.activeContextSize.takeIf { it > 0 } ?: LocalMemoryModel.DEFAULT_CTX_CAP

    /** 「历史 + 工具」共享的可用额度（已扣掉输出预留与安全余量）。 */
    private fun localPromptBudget(ctx: Int): Int =
        (ctx - LOCAL_OUTPUT_RESERVE_TOKENS - LOCAL_PROMPT_SAFETY_TOKENS).coerceAtLeast(1200)

    /** 历史配额 = 可用额度 × [LOCAL_HISTORY_SHARE_PERCENT]，另一半留给工具。 */
    private fun localHistoryBudget(): Int =
        (localPromptBudget(localCtx()) * LOCAL_HISTORY_SHARE_PERCENT / 100).coerceAtLeast(600)

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
        val finalMessages = OpenAiRequestMessages.forChatCompletions(
            applyLocalThinkingSwitch(trimHistory(messages), config),
        )
        return JSONObject()
            .put("model", config.model)
            .put("stream", true)
            // 让 llama.cpp 在流式结束时返回 usage（含 completion_tokens），供计算实际速率
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("messages", finalMessages)
            .put("tools", compactTools(tools, finalMessages, config))
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
        // 流式响应不含 llama.cpp 的 timings，改为客户端测速（首 token 延迟 + 解码速率）
        val streamStartMs = System.currentTimeMillis()
        var firstTokenMs = 0L
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
            if (firstTokenMs == 0L) firstTokenMs = System.currentTimeMillis() - streamStartMs
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

        // 客户端测速：流式响应无 timings，用 usage 的 completion_tokens 与实耗估算解码速率
        val elapsedTotalMs = (System.currentTimeMillis() - streamStartMs).toDouble()
        val decodeMs = (elapsedTotalMs - firstTokenMs).coerceAtLeast(1.0)
        val outTokens = usage?.outputTokens
        val measuredSpeed = if (outTokens != null && outTokens > 0) outTokens / (decodeMs / 1000.0) else null
        val measuredTtft = firstTokenMs.takeIf { it > 0 }?.toDouble()
        // 客户端测速（流式无 timings 时兜底）：usage 可能整体为 null，若直接 usage?.copy(...)
        // 会把测出来的速度一起丢掉，导致界面永远看不到 tok/s。
        val baseUsage = usage ?: AgentTokenUsage()
        val mergedUsage = baseUsage.copy(
            tokensPerSecond = usage?.tokensPerSecond ?: measuredSpeed,
            ttftMs = usage?.ttftMs ?: measuredTtft,
        ).takeUnless { it.isEmpty }

        return JSONObject()
            .put("role", "assistant")
            .put("content", content.toString())
            .put("reasoning_content", reasoningContent.toString())
            .put("finish_reason", finishReason.orEmpty())
            .also { message ->
                mergedUsage?.let { message.put("usage", it.toJson()) }
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
        // 注意：usage 缺失时不能直接返回 null —— 自建 llama.cpp 的流式若未携带 usage，
        // 仍需从响应根的 timings 读取速度（否则界面永远看不到 tok/s）。
        val usage = chunk.optJSONObject("usage") ?: JSONObject()
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
            // 速度与首字延迟必须一并序列化：下游（AgentLoop → UI）正是从这里取值，
            // 漏掉这两个字段会让界面永远显示不出 tok/s（2026-09-27 日志实证定位）。
            tokensPerSecond?.let { json.put("tokens_per_second", it) }
            ttftMs?.let { json.put("ttft_ms", it) }
        }

    private fun String.compactError(): String =
        replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > MAX_ERROR_CHARS) it.take(MAX_ERROR_CHARS) + "..." else it }
}
