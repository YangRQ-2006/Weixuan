package cn.yangrq.weixuan.agent.model

import cn.yangrq.weixuan.agent.runtime.AgentEvent
import cn.yangrq.weixuan.agent.runtime.AgentRunController

/** 重试只包围模型请求；完整响应返回前不提交历史或执行本地工具。 */
internal class AgentModelRetry(
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    data class Result(val round: Int, val response: ProviderResponse)

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
    ): Result {
        var round = initialRound
        var retries = 0
        awaitLocalServerReady(request, controller, round, onEvent)
        while (true) {
            controller.throwIfCancelled()
            onEvent(AgentEvent.RoundStarted(round, request.messages.length()))
            var hostedToolStarted = false
            var callbackFailed = false
            try {
                val response = provider.complete(request, controller) { event ->
                    if (event is ProviderEvent.HostedToolStarted) hostedToolStarted = true
                    try {
                        onProviderEvent(round, event)
                    } catch (failure: Exception) {
                        callbackFailed = true
                        throw failure
                    }
                }
                return Result(round, response)
            } catch (failure: Exception) {
                controller.throwIfCancelled()
                if (callbackFailed || Thread.currentThread().isInterrupted) throw failure
                val classified = AgentModelFailure.transport(failure) ?: throw failure
                if (hostedToolStarted) throw AgentModelFailure(
                    classified.code, false, classified.message.orEmpty(), classified, recoveryAllowed = false,
                )
                if (!classified.retryable) throw classified
                if (retries == MAX_RETRIES) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "${classified.message} 已重试 $MAX_RETRIES 次仍未恢复，已保留此前完成的工具结果。",
                        classified,
                    )
                }
                retries += 1
                val delayMs = BASE_DELAY_MS shl (retries - 1)
                onEvent(
                    AgentEvent.ModelRetryScheduled(
                        round, retries, MAX_RETRIES, delayMs.toInt(), classified.code,
                        classified.message.orEmpty().take(80),
                    )
                )
                waitBeforeRetry(controller, delayMs)
                controller.throwIfCancelled()
                // 展示保留失败尝试，模型上下文与最终思考摘要只接纳成功尝试。
                discardAttemptReasoning()
                round += 1
            }
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 2_000L

        /** 本地模型冷启动等待上限与轮询间隔。 */
        /**
         * 本地引擎就绪等待上限。
         *
         * **2026-10-01 修正：60s → 240s。** 旧值小于引擎自身的加载预算
         * （[cn.yangrq.weixuan.local.LlamaServerProcess] 里 `fileMb > 3000 → 180s，否则 90s`），
         * 于是 Agent 会在"引擎其实正在正常加载"时就提前抛 LOCAL_MODEL_NOT_READY，
         * 把一次正常的冷启动变成两次「模型请求暂时中断，N 秒后重试」——
         * 实测现象：Qwen3-VL-4B（mmproj 独立图 + -nkvo 后注意力落 CPU，加载 40~60s+）
         * 前两次请求必失败、第三次才成。
         * 取值 = 引擎最大预算 180s + 60s 余量。注意这不是"干等"：
         * 引擎一旦自己判定失败会置 LoadState.FAILED，循环会立刻抛出并带出真实原因。
         */
        private const val LOCAL_READY_WAIT_MS = 240_000L
        private const val LOCAL_READY_POLL_MS = 500L

        /**
         * 「本地模型加载中」提示复用 [AgentEvent.ModelRetryScheduled] 通道，
         * 常量与文案分流统一定义在 [AgentEvent.LOCAL_MODEL_LOADING_CODE] / `displayMessage`。
         */
    }

    /**
     * 本地模型冷启动保护（**2026-10-04 重写判据 —— 这是「一发消息就重试」的真正根因**）。
     *
     * **旧实现的致命缺陷**：入口第一句是 `if (isTcpReachable(port)) return`，而
     * `isTcpReachable` 只做一次 **TCP 三次握手**。llama-server 在**模型加载期间就已经 listen**
     * （内核用 backlog 直接完成握手，与 server 是否 accept 无关），所以裸 TCP 探测**恒成功**
     * → 函数立刻返回、**跳过整个等待** → 请求发给一个还在加载的 server → 它回 **HTTP 503** → 重试。
     * 实测时间线（三次 503 全部落在加载窗口内）：
     * ```
     * 12:09:08.778  libllama-server.so: fastrpc/dspqueue 初始化
     * 12:09:14.413  重试 round=1 code=HTTP_503
     * 12:09:16.461  重试 round=2 code=HTTP_503
     * 12:09:20.525  重试 round=3 code=HTTP_503
     * 12:09:21.772  LlamaServer: llama-server 就绪：http://127.0.0.1:18787/v1
     * ```
     *
     * **新实现**：以**显式状态** `LlamaServerProcess.loadState` 为准（它由加载流程自己维护：
     * 启动子进程前置 LOADING、就绪后置 READY、失败置 FAILED），TCP 只作二次确认：
     *  - LOADING → **等**（上限 [LOCAL_READY_WAIT_MS]，只轮询状态，不重试、不重复触发内存分配）
     *  - READY   → 放行
     *  - FAILED  → 立刻抛出加载失败的真实原因（不做无意义的 3 次重试）
     *  - IDLE    → 本进程没在管本地模型 → 放行，交给原有错误处理
     */
    private fun awaitLocalServerReady(
        request: ProviderRequest,
        controller: AgentRunController,
        round: Int,
        onEvent: (AgentEvent) -> Unit,
    ) {
        val port = Regex("""(?:127\.0\.0\.1|localhost):(\d+)""")
            .find(request.config.baseUrl)?.groupValues?.get(1)?.toIntOrNull() ?: return

        val local = cn.yangrq.weixuan.local.LlamaServerProcess
        val loadState = { local.loadState }
        val failed = {
            AgentModelFailure(
                code = "LOCAL_MODEL_NOT_READY",
                retryable = false,
                message = "本地模型未就绪：${local.lastFailureReason ?: "加载失败"}。" +
                    "可清理后台后在「本地模型」页重新加载。",
                recoveryAllowed = false,
            )
        }

        when (loadState()) {
            cn.yangrq.weixuan.local.LlamaServerProcess.LoadState.FAILED -> throw failed()
            // 引擎自报就绪 → 直接放行（不再依赖裸 TCP 探测）
            cn.yangrq.weixuan.local.LlamaServerProcess.LoadState.READY -> return
            cn.yangrq.weixuan.local.LlamaServerProcess.LoadState.LOADING -> Unit
            // 本进程没在管本地模型（未启用 / 已被系统杀掉）→ 维持旧行为，交给原有错误处理
            else -> return
        }

        val deadline = System.currentTimeMillis() + LOCAL_READY_WAIT_MS
        val waitStart = System.currentTimeMillis()
        // ★ 给用户**可见反馈**（2026-10-04）：加载实测受存储带宽限制（2.9GB ÷ ~150MB/s ≈ 20 秒，
        //   见 scripts/bench/loadfactor_test.sh），耗时省不掉；但原先这 17~20 秒是**完全静默**的，
        //   用户只感到"卡住/等很久"。这里复用已有的「系统提示」通道弹一条说明。
        //   复用 ModelRetryScheduled 而非新增事件：它的 wire 序列化与 UI 渲染链路都已贯通，
        //   只需在 projector 里按 reasonCode 分流，避免改动 wire 格式（见 LOCAL_MODEL_LOADING_CODE）。
        onEvent(
            AgentEvent.ModelRetryScheduled(
                round = round,
                attempt = 0,
                maxAttempts = 0,
                delayMs = 0,
                reasonCode = AgentEvent.LOCAL_MODEL_LOADING_CODE,
                detail = "正在从存储加载模型，首次约 20 秒（模型 2.9GB，受读取速度限制），请稍候…",
            ),
        )
        android.util.Log.i(
            "AgentModelRetry",
            "本地引擎加载中，模型请求等待就绪（上限 ${LOCAL_READY_WAIT_MS / 1000}s，状态=${loadState()}）",
        )
        while (System.currentTimeMillis() < deadline) {
            controller.throwIfCancelled()
            // ★ 必须两者都满足：状态 READY **且** TCP 可达。
            //   只看 TCP 会把"正在加载"误判为就绪（这正是旧实现的 bug）。
            if (loadState() == cn.yangrq.weixuan.local.LlamaServerProcess.LoadState.READY &&
                isTcpReachable(port)
            ) {
                android.util.Log.i(
                    "AgentModelRetry",
                    "本地引擎已就绪，等待 ${System.currentTimeMillis() - waitStart}ms 后放行请求",
                )
                return
            }
            if (loadState() == cn.yangrq.weixuan.local.LlamaServerProcess.LoadState.FAILED) throw failed()
            try {
                Thread.sleep(LOCAL_READY_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        throw AgentModelFailure(
            code = "LOCAL_MODEL_NOT_READY",
            retryable = false,
            message = "本地模型仍在加载中（已等待 ${LOCAL_READY_WAIT_MS / 1000} 秒）。" +
                "首次加载 GB 级模型需要 20~40 秒，请稍后重发。",
            recoveryAllowed = false,
        )
    }

    private fun isTcpReachable(port: Int): Boolean = try {
        java.net.Socket().use {
            it.connect(java.net.InetSocketAddress("127.0.0.1", port), 400)
        }
        true
    } catch (_: Exception) {
        false
    }
}
