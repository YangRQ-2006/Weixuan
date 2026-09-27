package cn.yangrq.weixuan.local

import android.util.Log
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * MoE（BigMoeOnEdge）引擎的 OpenAI 兼容桥。
 *
 * 存在意义：Agent 侧只认「OpenAI 兼容 HTTP 接口」（baseUrl=127.0.0.1:port）。而 bmoe-cli
 * 是 stdin/stdout 行协议的常驻进程。本类在两者之间做一个极薄的适配层：
 *   Agent → HTTP POST /v1/chat/completions → 本桥 → BmoeCliProcess(stdin)
 *   bmoe-cli stdout(BMOE_PROGRESS/BMOE_DONE) → 本桥 → SSE(data: ...) → Agent
 *
 * 这样 Agent、工具、UI 全部零改动，只切换本地引擎即可用上 30B MoE。
 */
class BmoeOpenAiServer(private val port: Int) {

    companion object {
        private const val TAG = "BmoeOpenAiServer"
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    private val pool = Executors.newFixedThreadPool(4)

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    fun start(): Boolean {
        if (isRunning) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
            serverSocket = socket
            pool.execute { acceptLoop(socket) }
            Log.i(TAG, "MoE 桥已启动：http://127.0.0.1:$port/v1")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "MoE 桥启动失败（端口 $port）：${t.message}", t)
            false
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                pool.execute { handleClient(client) }
            } catch (t: Throwable) {
                if (!socket.isClosed) Log.w(TAG, "accept 异常：${t.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                val input = s.getInputStream()
                val request = readRequest(input) ?: return
                val path = request.first
                val body = request.second
                val out = BufferedOutputStream(s.getOutputStream())

                when {
                    path.startsWith("/health") || path.startsWith("/v1/models") -> {
                        val json = if (path.startsWith("/health")) {
                            """{"status":"ok","engine":"bmoe-moe"}"""
                        } else {
                            """{"object":"list","data":[{"id":"local-moe","object":"model"}]}"""
                        }
                        writeJson(out, json)
                    }
                    path.startsWith("/v1/chat/completions") -> handleChat(out, body)
                    else -> writeJson(out, """{"error":{"message":"not found"}}""", 404)
                }
                out.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "请求处理失败：${t.message}")
            }
        }
    }

    /** 读取 HTTP 请求，返回 (请求行, body)。 */
    private fun readRequest(input: InputStream): Pair<String, String>? {
        val headers = ArrayList<String>()
        val sb = StringBuilder()
        var c = input.read()
        // 读头部
        while (c != -1) {
            if (c == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                sb.setLength(0)
                if (line.isEmpty()) break
                headers.add(line)
            } else {
                sb.append(c.toChar())
            }
            c = input.read()
        }
        if (headers.isEmpty()) return null
        val contentLength = headers.firstOrNull { it.startsWith("Content-Length", true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            String(buf, 0, read, Charsets.UTF_8)
        } else {
            ""
        }
        return headers[0] to body
    }

    private fun writeJson(out: OutputStream, json: String, code: Int = 200) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
    }

    /**
     * 处理 /v1/chat/completions。
     *
     * 只取最后一条 user 消息作为 prompt（对话状态由 bmoe session 侧的 KV 前缀复用维持；
     * 这里 clear_kv=true 让每轮独立，避免多轮工具调用时把 tool 结果误当上下文）。
     */
    private fun handleChat(out: OutputStream, body: String) {
        val req = runCatching { JSONObject(body) }.getOrNull()
        if (req == null) {
            writeJson(out, """{"error":{"message":"invalid json"}}""", 400)
            return
        }
        val messages = req.optJSONArray("messages") ?: JSONArray()
        val prompt = buildPrompt(messages)
        val nPredict = req.optInt("max_tokens", 512).coerceIn(16, 2048)
        val stream = req.optBoolean("stream", false)

        val deltaBuf = StringBuilder()
        val answer = StringBuilder()
        val done = CountDownLatch(1)
        val failed = AtomicBoolean(false)

        val ok = BmoeCliProcess.generate(
            prompt = prompt,
            nPredict = nPredict,
            think = false,
            clearKv = true,
            onDelta = { d ->
                deltaBuf.append(d)
                if (stream) {
                    runCatching { emitSse(out, d) }
                }
            },
            onDone = { text ->
                answer.append(text)
                done.countDown()
            },
        )
        if (!ok) {
            writeJson(out, """{"error":{"message":"moe engine not ready"}}""", 503)
            return
        }

        val finished = runCatching { done.await(nPredict * 3L + 300, TimeUnit.SECONDS) }
            .getOrDefault(false)
        if (!finished) failed.set(true)

        val finalText = if (answer.isNotEmpty()) answer.toString() else deltaBuf.toString()

        if (stream) {
            // 收尾：usage + DONE
            val usage = JSONObject()
                .put("prompt_tokens", prompt.length / 3)
                .put("completion_tokens", finalText.length / 3)
                .put("total_tokens", (prompt.length + finalText.length) / 3)
            val tail = JSONObject()
                .put("id", "bmoe-" + System.currentTimeMillis())
                .put("object", "chat.completion.chunk")
                .put("choices", JSONArray())
                .put("usage", usage)
            out.write("data: $tail\n\n".toByteArray(Charsets.UTF_8))
            out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
        } else {
            val resp = JSONObject()
                .put("id", "bmoe-" + System.currentTimeMillis())
                .put("object", "chat.completion")
                .put("model", "local-moe")
                .put(
                    "choices",
                    JSONArray().put(
                        JSONObject()
                            .put("index", 0)
                            .put(
                                "message",
                                JSONObject().put("role", "assistant").put("content", finalText),
                            )
                            .put("finish_reason", "stop"),
                    ),
                )
            writeJson(out, resp.toString())
        }
    }

    private fun emitSse(out: OutputStream, delta: String) {
        val chunk = JSONObject()
            .put("id", "bmoe-" + System.currentTimeMillis())
            .put("object", "chat.completion.chunk")
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("delta", JSONObject().put("content", delta))
                        .put("finish_reason", JSONObject.NULL),
                ),
            )
        out.write("data: $chunk\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** 拼 prompt：把 system + 历史压成单串（bmoe session 走 chat template）。 */
    private fun buildPrompt(messages: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role")
            val content = m.optString("content")
            if (content.isBlank()) continue
            when (role) {
                "system" -> sb.append(content).append("\n\n")
                "assistant", "user" -> sb.append(content).append("\n")
                "tool" -> sb.append("[工具结果] ").append(content).append("\n")
            }
        }
        return sb.toString().trim()
    }
}
