package cn.yangrq.weixuan.local

import android.util.Log
import cn.yangrq.weixuan.data.model.OpenAiCompatibleProviderSetting
import cn.yangrq.weixuan.data.provider.BuiltinProviders
import cn.yangrq.weixuan.data.repository.ProviderRepository
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 回环 OpenAI 兼容 HTTP 服务。
 *
 * 只监听 `127.0.0.1`，把 [LocalChatEngine] 包装成标准 OpenAI Chat Completions 接口，
 * 供 Eta 原生 `OpenAiChatCompletionsProvider` 直接调用——**Eta 的 Agent / UI / Provider 层零改动**。
 *
 * 已实现：`POST /v1/chat/completions`（流式 SSE 与非流式）、`GET /v1/models`。
 */
class LocalOpenAiServer(
    private val engine: LocalChatEngine,
    private val port: Int,
) {

    companion object {
        private const val TAG = "EtaLocalServer"
    }

    private val counter = AtomicLong(0)
    private val executor = Executors.newFixedThreadPool(4, object : ThreadFactory {
        private val index = AtomicLong(0)
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "eta-local-http-${index.incrementAndGet()}").apply { isDaemon = true }
    })

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    fun start(): Boolean {
        if (isRunning) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
            serverSocket = socket
            val thread = Thread({ acceptLoop(socket) }, "eta-local-accept")
            thread.isDaemon = true
            acceptThread = thread
            thread.start()
            Log.i(TAG, "本地 OpenAI 兼容服务已启动：http://127.0.0.1:$port/v1")
            true
        } catch (throwable: Throwable) {
            Log.e(TAG, "启动失败（端口 $port 可能被占用）：${throwable.message}")
            false
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        runCatching { executor.shutdownNow() }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (throwable: Throwable) {
                if (socket.isClosed) break
                Log.w(TAG, "accept 失败：${throwable.message}")
                continue
            }
            // 加固：读写超时防止单个连接卡死独占线程池（2026-09-24 僵尸监听事故）
            runCatching {
                client.tcpNoDelay = true
                client.soTimeout = 30_000
            }
            runCatching { executor.execute { runCatching { handle(client) }.onFailure { Log.w(TAG, "连接处理失败：${it.message}") } } }
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val contentLength: Int,
        val chunked: Boolean,
        val body: String?,
    )

    private fun handle(client: Socket) {
        client.use { socket ->
            val input = socket.getInputStream().buffered()
            val output = BufferedOutputStream(socket.getOutputStream())
            val request = runCatching { readRequest(input) }.getOrNull() ?: return
            val path = request.path.substringBefore('?')
            when {
                request.method == "GET" && (path == "/v1/models" || path == "/models") -> {
                    writeJson(output, 200, modelsJson().toString())
                }

                request.method == "POST" && (path == "/v1/chat/completions" || path == "/chat/completions") -> {
                    chat(output, request.body.orEmpty())
                }

                else -> writeJson(
                    output,
                    404,
                    errorJson("未知路径：${request.method} $path", "not_found").toString(),
                )
            }
        }
    }

    // ------------------------------------------------------------ 请求解析

    private fun readRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty().uppercase()
        val path = parts.getOrNull(1).orEmpty()

        var contentLength = 0
        var chunked = false
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            when (name) {
                "content-length" -> contentLength = value.toIntOrNull() ?: 0
                "transfer-encoding" -> if (value.lowercase().contains("chunked")) chunked = true
            }
        }

        val body = if (!chunked && contentLength > 0) {
            val buffer = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = input.read(buffer, read, contentLength - read)
                if (count < 0) break
                read += count
            }
            String(buffer, 0, read, Charsets.UTF_8)
        } else {
            null
        }
        return HttpRequest(method = method, path = path, contentLength = contentLength, chunked = chunked, body = body)
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        var readAny = false
        while (true) {
            val value = input.read()
            if (value < 0) return if (readAny) builder.toString() else null
            readAny = true
            if (value == '\n'.code) return builder.toString().trimEnd('\r')
            builder.append(value.toChar())
        }
    }

    // ------------------------------------------------------------ 路由处理

    private fun modelsJson(): JSONObject {
        val modelId = engine.currentModelName().ifBlank { LocalSettings.modelName }
        val item = JSONObject()
            .put("id", modelId)
            .put("object", "model")
            .put("created", System.currentTimeMillis() / 1000)
            .put("owned_by", "local-geniex")
        return JSONObject().put("object", "list").put("data", JSONArray().put(item))
    }

    private data class ChatRequest(
        val model: String,
        val messages: JSONArray,
        val tools: JSONArray,
        val maxTokens: Int?,
        val stream: Boolean,
    )

    private fun parseChatRequest(body: String): ChatRequest? = runCatching {
        val json = JSONObject(body)
        val maxTokens = json.optInt("max_tokens", 0)
        ChatRequest(
            model = json.optString("model").ifBlank { engine.currentModelName() },
            messages = json.optJSONArray("messages") ?: JSONArray(),
            tools = json.optJSONArray("tools") ?: JSONArray(),
            maxTokens = if (maxTokens > 0) maxTokens else null,
            stream = json.optBoolean("stream", true),
        )
    }.getOrNull()

    private fun chat(output: OutputStream, body: String) {
        val request = parseChatRequest(body)
        if (request == null || request.messages.length() == 0) {
            writeJson(output, 400, errorJson("请求体不是合法的 Chat Completions JSON", "invalid_request").toString())
            return
        }
        if (!engine.isReady) {
            writeJson(
                output,
                503,
                errorJson("本地模型未加载，请在「本地模型」页面加载模型", "model_not_loaded").toString(),
            )
            return
        }
        if (request.stream) streamChat(output, request) else completeChat(output, request)
    }

    private fun streamChat(output: OutputStream, request: ChatRequest) {
        writeSseHeaders(output)
        val id = "chatcmpl-local-${counter.incrementAndGet()}"
        val created = System.currentTimeMillis() / 1000
        var reason = "stop"
        var usage: LocalUsage? = null
        var failed = false

        runCatching {
            writeSseEvent(output, chunk(id, created, request.model, JSONObject().put("role", "assistant")).toString())
            runBlocking {
                engine.streamChat(request.messages, request.tools, request.maxTokens).collect { event ->
                    when (event) {
                        is LocalStreamEvent.TextDelta ->
                            writeSseEvent(
                                output,
                                chunk(id, created, request.model, JSONObject().put("content", event.text)).toString(),
                            )

                        is LocalStreamEvent.ReasoningDelta ->
                            writeSseEvent(
                                output,
                                chunk(
                                    id,
                                    created,
                                    request.model,
                                    JSONObject().put("reasoning_content", event.text),
                                ).toString(),
                            )

                        is LocalStreamEvent.ToolCall ->
                            writeSseEvent(
                                output,
                                chunk(id, created, request.model, toolCallDelta(event.call)).toString(),
                            )

                        is LocalStreamEvent.Usage -> usage = event.usage

                        is LocalStreamEvent.Finished -> reason = event.reason

                        is LocalStreamEvent.Failed -> {
                            failed = true
                            writeSseEvent(output, errorJson(event.message, "local_engine_error").toString())
                        }
                    }
                }
            }
        }.onFailure {
            // 写失败通常意味着客户端断开：此时生成流已被取消（引擎会停止底层生成）
            Log.w(TAG, "流式响应中断：${it.message}")
            failed = true
        }

        if (failed) {
            runCatching { writeSseEvent(output, "[DONE]") }
            return
        }
        usage?.let { writeSseEvent(output, usageChunk(id, created, request.model, it).toString()) }
        writeSseEvent(output, chunk(id, created, request.model, JSONObject(), reason).toString())
        writeSseEvent(output, "[DONE]")
    }

    private fun completeChat(output: OutputStream, request: ChatRequest) {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<LocalToolCall>()
        var reason = "stop"
        var usage: LocalUsage? = null
        var failure: String? = null

        runCatching {
            runBlocking {
                engine.streamChat(request.messages, request.tools, request.maxTokens).collect { event ->
                    when (event) {
                        is LocalStreamEvent.TextDelta -> content.append(event.text)
                        is LocalStreamEvent.ReasoningDelta -> reasoning.append(event.text)
                        is LocalStreamEvent.ToolCall -> toolCalls.add(event.call)
                        is LocalStreamEvent.Usage -> usage = event.usage
                        is LocalStreamEvent.Finished -> reason = event.reason
                        is LocalStreamEvent.Failed -> failure = event.message
                    }
                }
            }
        }.onFailure { failure = it.message ?: "本地推理失败" }

        failure?.let {
            writeJson(output, 503, errorJson(it, "local_engine_error").toString())
            return
        }

        val message = JSONObject().put("role", "assistant").put("content", content.toString())
        if (reasoning.isNotEmpty()) message.put("reasoning_content", reasoning.toString())
        if (toolCalls.isNotEmpty()) {
            message.put(
                "tool_calls",
                JSONArray().also { array ->
                    toolCalls.forEach { call ->
                        array.put(
                            JSONObject()
                                .put("id", call.id)
                                .put("type", "function")
                                .put("function", JSONObject().put("name", call.name).put("arguments", call.argumentsJson)),
                        )
                    }
                },
            )
        }
        val response = JSONObject()
            .put("id", "chatcmpl-local-${counter.incrementAndGet()}")
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", request.model)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("message", message)
                        .put("finish_reason", reason),
                ),
            )
        usage?.let { response.put("usage", usageJson(it)) }
        writeJson(output, 200, response.toString())
    }

    // ------------------------------------------------------------ 输出工具

    private fun chunk(id: String, created: Long, model: String, delta: JSONObject, finishReason: String? = null): JSONObject =
        JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("delta", delta)
                        .put("finish_reason", finishReason ?: JSONObject.NULL),
                ),
            )

    private fun usageChunk(id: String, created: Long, model: String, usage: LocalUsage): JSONObject =
        JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray())
            .put("usage", usageJson(usage))

    private fun usageJson(usage: LocalUsage): JSONObject = JSONObject()
        .put("prompt_tokens", usage.promptTokens.toInt())
        .put("completion_tokens", usage.completionTokens.toInt())
        .put("total_tokens", usage.totalTokens.toInt())
        // 本地引擎实测性能（非标准扩展字段，OpenAI 客户端会忽略）
        .put("tokens_per_second", usage.tokensPerSecond)
        .put("ttft_ms", usage.ttftMs)

    private fun toolCallDelta(call: LocalToolCall): JSONObject = JSONObject().put(
        "tool_calls",
        JSONArray().put(
            JSONObject()
                .put("index", call.index)
                .put("id", call.id)
                .put("type", "function")
                .put("function", JSONObject().put("name", call.name).put("arguments", call.argumentsJson)),
        ),
    )

    private fun errorJson(message: String, code: String): JSONObject = JSONObject().put(
        "error",
        JSONObject()
            .put("message", message)
            .put("code", code)
            .put("type", "local_engine_error"),
    )

    private fun writeSseHeaders(output: OutputStream) {
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun writeSseEvent(output: OutputStream, payload: String) {
        output.write("data: $payload\n\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun writeJson(output: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code ${statusText(code)}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        411 -> "Length Required"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "OK"
    }
}

/**
 * 进程内单例宿主：由 `EtaApp` 在用户开启本地服务时启动。
 */
object LocalServerHost {

    private const val TAG = "EtaLocalServerHost"

    /** 首选端口被占用时依次 +1 尝试的次数。 */
    private const val PORT_PROBE_RANGE = 12

    @Volatile
    private var server: LocalOpenAiServer? = null

    /** 实际绑定成功的端口；-1 表示未启动。 */
    @Volatile
    var boundPort: Int = -1
        private set

    val isRunning: Boolean
        get() = server?.isRunning == true

    fun start(engine: LocalChatEngine, port: Int): Boolean {
        if (isRunning) return true
        val created = LocalOpenAiServer(engine, port)
        val ok = created.start()
        if (ok) {
            server = created
            boundPort = port
        } else {
            Log.w(TAG, "本地服务启动失败，端口 $port 可能被占用")
        }
        return ok
    }

    /**
     * 启动本地服务并保证「服务端口 ↔ Provider baseUrl」一致。
     *
     * 为什么要探测端口：首选端口有可能被系统或其它 App 占用。实测本机上 8787 被太墟内置
     * 浏览器 MCP 服务占用、8788 也被占，此时服务 bind 失败，而 Eta 仍会向该端口发请求，
     * 命中别人返回的 404（这正是「提示 404」的根因）。这里改为自动探测可用端口，并把实际
     * 端口写回设置与「本地模型（GenieX NPU）」Provider 的 baseUrl。
     *
     * @return 实际绑定的端口；-1 表示全部候选端口都不可用。
     */
    suspend fun startAndSync(engine: LocalChatEngine, preferredPort: Int): Int {
        if (isRunning) return boundPort
        var bound = -1
        for (offset in 0 until PORT_PROBE_RANGE) {
            val candidate = preferredPort + offset
            val created = LocalOpenAiServer(engine, candidate)
            if (created.start()) {
                server = created
                bound = candidate
                break
            }
        }
        if (bound < 0) {
            Log.e(
                TAG,
                "本地服务启动失败：端口 $preferredPort..${preferredPort + PORT_PROBE_RANGE - 1} 全部不可用",
            )
            return -1
        }
        boundPort = bound
        LocalSettings.port = bound
        Log.i(TAG, "本地 OpenAI 兼容服务就绪：http://127.0.0.1:$bound/v1")
        syncProviderBaseUrl(bound)
        return bound
    }

    /**
     * 启动自检：从本进程向 127.0.0.1:$port/v1/models 发一次真实 HTTP GET。
     * 用于发现「端口已 bind 但 accept 线程未服务」的僵尸监听态——
     * 该状态下 TCP 握手成功但永远等不到响应，外部表现即"模型请求暂时中断"。
     */
    fun selfCheck(port: Int) {
        Thread({
            runCatching {
                val conn = (java.net.URL("http://127.0.0.1:$port/v1/models").openConnection()
                        as java.net.HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    instanceFollowRedirects = false
                }
                val code = conn.responseCode
                runCatching { conn.inputStream.close() }
                runCatching { conn.errorStream?.close() }
                if (code == 200) {
                    Log.i(TAG, "本地服务自检通过：HTTP 200（127.0.0.1:$port/v1/models）")
                } else {
                    Log.w(TAG, "本地服务自检异常：HTTP $code —— 端口在监听但服务未正常应答，建议重启应用")
                }
            }.onFailure { Log.w(TAG, "本地服务自检失败：${it.message}") }
        }, "eta-local-selfcheck").apply { isDaemon = true }.start()
    }

    /** 把内置本地 Provider 的 baseUrl 对齐到实际端口。 */
    private suspend fun syncProviderBaseUrl(port: Int) {
        val url = "http://127.0.0.1:$port/v1"
        runCatching {
            val target = ProviderRepository.allProviders()
                .firstOrNull { it.id == BuiltinProviders.LOCAL_GENIEX_ID }
                ?: BuiltinProviders.PROVIDERS.firstOrNull { it.id == BuiltinProviders.LOCAL_GENIEX_ID }
                ?: return@runCatching
            if (target.baseUrl == url) return@runCatching
            val updated = when (target) {
                is OpenAiCompatibleProviderSetting -> target.copy(baseUrl = url)
                else -> return@runCatching
            }
            ProviderRepository.updateProvider(updated)
            Log.i(TAG, "已把本地 Provider 的 baseUrl 对齐为 $url")
        }.onFailure { Log.w(TAG, "同步本地 Provider baseUrl 失败：${it.message}") }
    }

    fun stop() {
        server?.stop()
        server = null
        boundPort = -1
    }
}
