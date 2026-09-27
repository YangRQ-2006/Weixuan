package cn.yangrq.weixuan.agent.model

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

    val modelClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(MODEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
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
