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
        awaitLocalServerReady(request, controller)
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
        private const val LOCAL_READY_WAIT_MS = 60_000L
        private const val LOCAL_READY_POLL_MS = 500L
    }

    /**
     * 本地模型冷启动保护（2026-09-28）：
     * baseUrl 指向本机回环时，若端口还没监听，说明 llama-server 仍在加载模型（4B 冷启动
     * 约 20~30s）。此时直接发请求会立刻拿到连接拒绝 -> 被判为 MODEL_CONNECTION_FAILED ->
     * 用户会看到一次「模型请求重试」再成功。这里先把这段时间等掉。
     *
     * 仅当确实有本地进程在启动时才等待（进程不在 = 未启用本地模型，直接放行），
     * 避免把「本地服务未开启」也拖成超时。
     */
    private fun awaitLocalServerReady(request: ProviderRequest, controller: AgentRunController) {
        val port = Regex("""(?:127\.0\.0\.1|localhost):(\d+)""")
            .find(request.config.baseUrl)?.groupValues?.get(1)?.toIntOrNull() ?: return
        // 进程不存在：本地模型未在启动，无需等待。
        if (!cn.yangrq.weixuan.local.LlamaServerProcess.isRunning()) return
        if (isTcpReachable(port)) return
        val deadline = System.currentTimeMillis() + LOCAL_READY_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            controller.throwIfCancelled()
            if (isTcpReachable(port)) return
            try {
                Thread.sleep(LOCAL_READY_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
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
