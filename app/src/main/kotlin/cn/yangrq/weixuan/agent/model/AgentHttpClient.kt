package cn.yangrq.weixuan.agent.model

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 模块全局 OkHttp 客户端。
 *
 * 模型流与普通 HTTP 请求共享连接池，但独立设置读取等待与重试策略。
 */
internal object AgentHttpClient {

    // 本地 llama-server/bmoe 是单/少 slot 的串行服务：前一个请求未结束时新连接可能被拒，
    // 15 秒太短会误判为"连接中断"进而触发 Agent 重试（表现为"能完成但会重试"）。放宽到 120 秒。
    private const val CONNECT_TIMEOUT_MS = 120_000L
    private const val READ_TIMEOUT_MS = 60_000L
    private const val WRITE_TIMEOUT_MS = 30_000L

    const val MODEL_READ_TIMEOUT_MS = 300_000L

    /**
     * 模型客户端 —— 2026-10-04 根因修复。
     *
     * **症状**：一调用工具就弹「模型请求重试」，第二次重试才回复；纯聊天不报错。
     *
     * **取证**（logcat 实测）：失败发生在 `provider_request_started` 之后 **4 毫秒**，
     * 错误码 `MODEL_CONNECTION_FAILED`（= 捕获到 IOException）。同一时刻 llama-server
     * 进程**存活且已连续运行 10 分钟**（`etime=10:07`，无重启、无 `model loaded` 新记录），
     * server 侧在那 4ms 内**没有任何日志**。→ 说明不是引擎挂了，而是**复用到了一条已死的
     * keep-alive 连接**。
     *
     * **机制**：工具调用会插进数秒空档（observe_screen / tap / wait / 文件读写），
     * 期间这条空闲连接被 llama-server（cpp-httplib）按 keep-alive 超时关掉；
     * 下一轮模型请求复用该连接 → 立刻 IOException。
     *
     * **为什么是 `retryOnConnectionFailure(false)` 惹的祸**：OkHttp 默认 `true`，会在
     * 「请求尚未真正送达」时对**连接级**失败透明重试——正是治这个病的药。关掉它之后异常直接上抛，
     * 只能靠 Agent 层重试兜底（表现为弹提示 + 等 2 秒），而重试走新连接必然成功。
     *
     * 当初关掉它的注释理由是「怕单 slot 忙被拒时乱重试」——**那是误判**：slot 忙时 server 回的是
     * **HTTP 503（正常响应）**，`retryOnConnectionFailure` 只处理连接级失败、**从不按状态码重试**。
     * 所以这个开关没挡住任何想挡的东西，只制造了上面这个 bug。
     *
     * **修复**：① 恢复透明重试；② 给模型客户端**独立的零空闲连接池**，从根上不再复用空闲连接
     * （loopback 上新建连接的代价可忽略，而共享连接池会让别的请求把这条连接晾死）。
     */
    val modelClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(MODEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .retryOnConnectionFailure(true)
            .build()
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
