package cn.yangrq.weixuan.local

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ModelType
import cn.yangrq.weixuan.local.LocalChatConversion.estimateModelMb
import cn.yangrq.weixuan.local.LocalChatConversion.toGenieXMessages
import cn.yangrq.weixuan.local.LocalChatConversion.toLocalUsage
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * GenieX（高通 NPU/GPU）本地聊天引擎。
 *
 * 生命周期：`initialize()` → `loadModel()` → `streamChat()` → `unload()`。
 * 模型权重来自 GenieX 模型中心（[ModelManagerWrapper]）或用户自定义 .gguf 路径；
 * 生成参数由 [LocalPerfTuner] 按 [LocalResourceGuard] 的内存/温度/电量预算自适应。
 */
object GenieXLocalEngine : LocalChatEngine {

    private const val TAG = "EtaLocalEngine"

    private val _state = MutableStateFlow(LocalEngineState())
    override val state: StateFlow<LocalEngineState> = _state.asStateFlow()

    private val guard = LocalResourceGuard()
    private val generationMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var sdkReady = false

    @Volatile
    private var wrapper: LlmWrapper? = null

    @Volatile
    private var loadedKey: String = ""

    override val isReady: Boolean
        get() = wrapper != null && _state.value.status == LocalEngineStatus.READY

    override fun currentModelName(): String = loadedKey.ifBlank { LocalSettings.modelName }

    // ---------------------------------------------------------------- 初始化

    override suspend fun initialize(context: Context): Result<Unit> {
        appContext = context.applicationContext
        if (sdkReady) return Result.success(Unit)
        val app = context.applicationContext
        return suspendCancellableCoroutine { continuation ->
            try {
                GenieXSdk.getInstance().init(
                    app,
                    object : GenieXSdk.InitCallback {
                        override fun onSuccess() {
                            sdkReady = true
                            _state.value = _state.value.copy(
                                status = LocalEngineStatus.READY_TO_LOAD,
                                message = "GenieX 就绪",
                            )
                            if (continuation.isActive) continuation.resume(Result.success(Unit))
                        }

                        override fun onFailure(reason: String) {
                            _state.value = _state.value.copy(
                                status = LocalEngineStatus.ERROR,
                                message = "GenieX 初始化失败：$reason",
                            )
                            if (continuation.isActive) {
                                continuation.resume(
                                    Result.failure(IllegalStateException("GenieX 初始化失败：$reason"))
                                )
                            }
                        }
                    },
                )
            } catch (throwable: Throwable) {
                sdkReady = false
                _state.value = _state.value.copy(
                    status = LocalEngineStatus.ERROR,
                    message = throwable.message ?: "GenieX 初始化异常",
                )
                if (continuation.isActive) continuation.resume(Result.failure(throwable))
            }
        }
    }

    // ---------------------------------------------------------------- 模型管理

    override suspend fun refreshInstalledModels(): List<LocalModelEntry> {
        val installed = runCatching { ModelManagerWrapper.list() }.getOrNull().orEmpty()
        return installed.map { name ->
            val paths = runCatching { ModelManagerWrapper.getPaths(name) }.getOrNull()
            val modelPath = paths?.model_path?.takeIf { it.isNotBlank() }
            LocalModelEntry(
                name = name,
                precision = "",
                displayName = name,
                installed = modelPath != null,
                modelPath = modelPath,
                tokenizerPath = paths?.tokenizer_path?.takeIf { it.isNotBlank() },
                sizeBytes = modelPath?.let { File(it).length() } ?: 0L,
            )
        }
    }

    override suspend fun downloadModel(
        name: String,
        precision: String,
        onProgress: (Double) -> Unit,
    ): Result<Unit> {
        val context = appContext ?: return Result.failure(IllegalStateException("引擎未初始化"))
        initialize(context).onFailure { return Result.failure(it) }
        return try {
            // Hub 自动路由（2026-09-27）：Qualcomm AI Hub 提供"按芯片预编译"的 bundle
            // （精度形如 w4a16/w8a16，走 NPU-only 的 QAIRT 路径，追求峰值性能）；
            // 其余精度（q4_0/q8_0 等 GGUF）走 HuggingFace。据此自动选源。
            val isPrecompiled = Regex("^w\\d+a\\d+$").matches(precision.lowercase())
            val input = ModelPullInput(
                model_name = name,
                precision = precision,
                hub = if (isPrecompiled) HubSource.AIHUB else HubSource.HUGGINGFACE,
                model_type = ModelType.LLM,
            )
            ModelManagerWrapper.pullFlow(input).collect { event ->
                when (event) {
                    is ModelManagerWrapper.PullEvent.Progress -> {
                        val files = event.files
                        val ratio = if (files.isEmpty()) {
                            0.0
                        } else {
                            val downloaded = files.sumOf { it.downloaded_bytes }
                            val total = files.sumOf { it.total_bytes }.coerceAtLeast(1)
                            downloaded.toDouble() / total.toDouble()
                        }
                        onProgress(ratio.coerceIn(0.0, 1.0))
                    }

                    is ModelManagerWrapper.PullEvent.Completed -> onProgress(1.0)

                    is ModelManagerWrapper.PullEvent.Error -> {
                        throw IllegalStateException("模型下载失败[${event.code}]：${event.message}")
                    }
                }
            }
            Result.success(Unit)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Result.failure(throwable)
        }
    }

    /** 最近一次真实加载失败时间（elapsedRealtime）；仅记录进入加载流程后的失败。 */
    @Volatile
    private var lastLoadFailureAt: Long = 0

    override suspend fun loadModel(name: String?): Result<Unit> {
        val context = appContext ?: return Result.failure(IllegalStateException("引擎未初始化"))
        // 自建 llama.cpp runtime 模式（2026-09-25）：直接启动 llama-server 子进程（内置内存预检，
        // 不可承载的模型会被安全拒绝而非拖垮系统）。
        if (LocalSettings.useSelfBuiltEngine) {
            val modelPath = LocalSettings.customModelPath
            if (modelPath.isBlank() || !java.io.File(modelPath).exists()) {
                val msg = "自建模式：未选择模型文件（请先在「本地模型」页选择 GGUF）"
                _state.value = _state.value.copy(status = LocalEngineStatus.ERROR, message = msg)
                return Result.failure(IllegalStateException(msg))
            }
            _state.value = _state.value.copy(status = LocalEngineStatus.LOADING, message = "自建 runtime 启动中…")
            return withContext(Dispatchers.IO) {
                val ok = LlamaServerProcess.start(context, modelPath, LocalSettings.DEFAULT_PORT, 6144)
                if (ok) {
                    loadedKey = modelPath
                    _state.value = _state.value.copy(
                        status = LocalEngineStatus.READY,
                        message = "自建 llama.cpp runtime 已就绪（NPU: Hexagon HTP + mmap + KV量化）",
                    )
                    Result.success(Unit)
                } else {
                    val msg = "自建 runtime 启动失败或内存不足（已安全拒绝，未影响系统）"
                    _state.value = _state.value.copy(status = LocalEngineStatus.ERROR, message = msg)
                    Result.failure(IllegalStateException(msg))
                }
            }
        }
        // 加载全程持前台执行租约：加载 GB 级模型需数十秒，期间用户切出/锁屏时
        // 进程必须免于 MIUI「一键清理」与后台压制（2026-09-25 事故实证：加载中被
        // OneKeyClean SIGKILL，进度归零，反复循环永远加载不完）。
        val leaseId = "geniex-model-load-${System.currentTimeMillis()}"
        val leased = cn.yangrq.weixuan.agent.runtime.AgentExecutionService.acquire(context, leaseId) { }
        try {
            val result = loadModelInternal(context, name)
            val msg = result.exceptionOrNull()?.message ?: ""
            if (result.isFailure && "拒绝" !in msg && "冷却" !in msg && "未安装" !in msg) {
                lastLoadFailureAt = android.os.SystemClock.elapsedRealtime()
            }
            return result
        } finally {
            if (leased) cn.yangrq.weixuan.agent.runtime.AgentExecutionService.release(leaseId)
        }
    }

    private suspend fun loadModelInternal(context: android.content.Context, name: String?): Result<Unit> {
        val initStart = android.os.SystemClock.elapsedRealtime()
        initialize(context).onFailure { return Result.failure(it) }
        Log.i(TAG, "GenieX SDK 初始化完成：耗时 ${android.os.SystemClock.elapsedRealtime() - initStart}ms")
        val target = name?.takeIf { it.isNotBlank() } ?: LocalSettings.modelName
        val resolved = resolveModel(target)
            ?: return Result.failure(
                IllegalStateException("模型未安装：$target（请在「本地模型」页下载，或指定自定义 .gguf 路径）")
            )
        if (wrapper != null && loadedKey == resolved.key && _state.value.status == LocalEngineStatus.READY) {
            return Result.success(Unit)
        }
        // 内存估算必须用【实际文件大小】：旧版误用 LocalSettings.modelName（默认
        // "Qwen/Qwen3-8B"→估算 5600MB），导致 378MB 的 0.6B 被误判拒载（2026-09-25 终极根因）。
        val fileMb = (java.io.File(resolved.modelPath).length() / 1024 / 1024).toInt()
        val estimateMb = if (fileMb > 0) fileMb else estimateModelMb(target)
        // 安全边界一：温度（设备过热时禁止加载，防止热失控卡死，2026-09-25 事故）
        // 温控按用户要求解除拦截（2026-09-25）：仅记录温度供观测，不再拒绝加载。
        // 防卡死核心防线保留：内存边界、重试上限、冷却期、前台租约。
        Log.i(TAG, "加载前热状态：电池 ${guard.batteryTempC(context)}°C（温控仅提示，不拦截）")
        // 安全边界二：冷却期（加载失败后 3 分钟内禁止重试，防反复大分配压垮系统）
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val lastFail = lastLoadFailureAt
        if (lastFail > 0 && nowMs - lastFail < 180_000) {
            val waitSec = (180_000 - (nowMs - lastFail)) / 1000
            val message = "上次加载失败，冷却中（${waitSec}s 后可再试；安全边界防反复分配）"
            return Result.failure(IllegalStateException(message))
        }
        // 安全边界三：内存（SDK 为大块预分配，须留足全量×1.2+1GB，宁可拒绝不可冒险）
        if (!guard.canLoadModel(estimateMb)) {
            val message = "内存不足，拒绝加载（当前可用 ${guard.memAvailableMb()}MB，" +
                "加载需求约 ${guard.loadNeedMb(estimateMb)}MB——请清理后台释放内存后重试）"
            _state.value = _state.value.copy(status = LocalEngineStatus.ERROR, message = message)
            return Result.failure(IllegalStateException(message))
        }
        withContext(Dispatchers.IO) { unloadInternal() }
        _state.value = _state.value.copy(
            status = LocalEngineStatus.LOADING,
            modelName = target,
            precision = resolved.precision,
            message = "正在加载模型（首次加载较慢）…",
        )
        val draftPath = resolveDraftModelPath(target)
        val isQairtBundleEarly = LocalSettings.qairtBundleEnabled ||
            resolved.modelPath.contains("genie_config", true) ||
            resolved.modelPath.contains("w4a16", true) ||
            resolved.key.contains("w4a16", true)
        val config = LocalPerfTuner.buildModelConfig(
            guard,
            draftPath,
            estimateMb,
            qairtBundle = isQairtBundleEarly,
        )
        // 预编译 bundle（AI Hub QAIRT）：其 genie_config.json 写死 QnnHtp 后端，插件只接受
        // NPU 计算单元——传 HYBRID/CPU 会直接报 "Parameter not supported by this plugin"。
        // 故此处提前判定，并让 units 只含 NPU。GGUF 仍按 hybrid→npu→cpu 逐级回退。
        val units = if (isQairtBundleEarly) {
            listOf(LocalPerfTuner.COMPUTE_UNIT_NPU)
        } else {
            when (LocalSettings.computeUnit) {
                LocalPerfTuner.COMPUTE_UNIT_NPU -> listOf(
                    LocalPerfTuner.COMPUTE_UNIT_NPU,
                    LocalPerfTuner.COMPUTE_UNIT_CPU,
                )
                LocalPerfTuner.COMPUTE_UNIT_CPU -> listOf(LocalPerfTuner.COMPUTE_UNIT_CPU)
                else -> listOf(
                    LocalPerfTuner.COMPUTE_UNIT_HYBRID,
                    LocalPerfTuner.COMPUTE_UNIT_NPU,
                    LocalPerfTuner.COMPUTE_UNIT_CPU,
                )
            }
        }
        var lastError: Throwable? = null
        for (unit in units) {
            // runtime 自动路由（2026-09-27）：Qualcomm AI Hub 的预编译 bundle
            // （含 genie_config.json / part*_of_*.bin，精度 w4a16）以 QAIRT 在 NPU 上
            // NPU-only 执行——这是官方峰值性能路径；GGUF 仍走 llama.cpp（通用路径）。
            val isPrecompiledBundle = isQairtBundleEarly
            val input = LlmCreateInput(
                model_path = resolved.modelPath,
                tokenizer_path = resolved.tokenizerPath,
                config = config,
                runtime_id = if (isPrecompiledBundle) {
                    LocalPerfTuner.RUNTIME_QAIRT
                } else {
                    LocalPerfTuner.RUNTIME_LLAMA_CPP
                },
                compute_unit = unit,
            )
            val result = runCatching {
                LlmWrapper.builder()
                    .llmCreateInput(input)
                    .dispatcher(Dispatchers.IO)
                    .build()
            }.getOrElse { Result.failure(it) }
            val loaded = result.getOrNull()
            if (loaded != null) {
                wrapper = loaded
                loadedKey = resolved.key
                _state.value = _state.value.copy(
                    status = LocalEngineStatus.READY,
                    modelName = target,
                    precision = resolved.precision,
                    nCtx = config.nCtx,
                    computeUnit = unit,
                    speculativeDecoding = draftPath != null,
                    message = "模型已加载",
                )
                Log.i(TAG, "loaded $target via $unit, nCtx=${config.nCtx}, draft=${draftPath != null}")
                Log.i(TAG, "模型加载后可用内存=${guard.memAvailableMb()}MB（权重估算=${estimateMb}MB，观测 mmap 实际驻留）")
                // 实测审计（低内存大模型模式）：余量不足立即卸载，防卡死
                val auditError = guard.postLoadAudit()
                if (auditError != null) {
                    runCatching { unloadInternal() }
                    wrapper = null
                    loadedKey = ""
                    _state.value = _state.value.copy(status = LocalEngineStatus.ERROR, message = auditError)
                    return Result.failure(IllegalStateException(auditError))
                }
                return Result.success(Unit)
            }
            lastError = result.exceptionOrNull()
            Log.w(TAG, "compute_unit=$unit 加载失败：${lastError?.message}")
        }
        val message = lastError?.message ?: "模型加载失败"
        _state.value = _state.value.copy(status = LocalEngineStatus.ERROR, message = message)
        return Result.failure(lastError ?: IllegalStateException(message))
    }

    override suspend fun unload() {
        // 自建模式：停止 llama-server 子进程（含其全部内存）
        if (LocalSettings.useSelfBuiltEngine && LlamaServerProcess.isRunning()) {
            runCatching { LlamaServerProcess.stop() }
        }
        withContext(Dispatchers.IO) { unloadInternal() }
        if (_state.value.status != LocalEngineStatus.UNINITIALIZED) {
            _state.value = _state.value.copy(
                status = LocalEngineStatus.READY_TO_LOAD,
                message = "模型已卸载",
                nCtx = 0,
                computeUnit = "",
                speculativeDecoding = false,
            )
        }
    }

    override fun cancelGeneration() {
        val current = wrapper ?: return
        scope.launch { runCatching { current.stopStream() } }
    }

    private suspend fun unloadInternal() {
        val current = wrapper ?: return
        wrapper = null
        loadedKey = ""
        runCatching { current.stopStream() }
        runCatching { current.close() }
    }

    // ---------------------------------------------------------------- 推理

    override fun streamChat(
        messages: JSONArray,
        tools: JSONArray,
        maxTokens: Int?,
    ): Flow<LocalStreamEvent> {
        val current = wrapper
        if (current == null || _state.value.status != LocalEngineStatus.READY) {
            return flowOf(LocalStreamEvent.Failed("本地模型未加载，请先在「本地模型」页面加载模型", 503))
        }
        val pre = guard.preInferenceCheck()
        if (!pre.canInfer) {
            return flowOf(LocalStreamEvent.Failed("设备资源受限：${pre.reason}", 503))
        }

        return channelFlow {
            // 推理全程持前台执行租约（2026-09-25 实证：用户切走后 MIUI 冻结进程，
            // 推理线程停摆、流永远无输出、CPU 零增长）。加载已有租约，推理同样需要。
            val inferCtx = appContext
            val inferLease = "geniex-infer-${System.currentTimeMillis()}"
            val inferLeased = inferCtx != null &&
                cn.yangrq.weixuan.agent.runtime.AgentExecutionService.acquire(inferCtx, inferLease) { }
            try {
            // 锁门禁（修复 2026-09-25 死锁 bug：旧版 tryLock 成功后直接 withLock，
            // kotlinx Mutex 不可重入 → 永久死锁 → 推理永不执行、零输出）。
            // 改为只读探测 isLocked：被占用则取消旧生成并限时等待，随后统一 withLock。
            if (generationMutex.isLocked) {
                cancelGeneration()
                val deadline = System.currentTimeMillis() + 60_000
                while (generationMutex.isLocked && System.currentTimeMillis() < deadline) {
                    delay(250)
                }
                if (generationMutex.isLocked) {
                    send(LocalStreamEvent.Failed("上一次生成未在超时内结束，新请求被拒绝（请重试或重启应用）", 503))
                    return@channelFlow
                }
            }
            generationMutex.withLock {
                // 工具集按窗口裁剪（2026-09-25 ggml_abort 崩溃修复）：工具 schema 是 prompt 主体
                // （31 工具 ≈ 8k tokens），小窗口模型（4B ≈ 4.5k）装不下 → context-shifting 崩溃。
                val effectiveTools = limitToolsForWindow(tools, _state.value.nCtx)
                val hasTools = effectiveTools.length() > 0
                val prompt = buildPrompt(current, messages, effectiveTools, hasTools)
                if (prompt.isNullOrBlank()) {
                    send(LocalStreamEvent.Failed("chat template 应用失败，无法构造 prompt", 500))
                    return@withLock
                }
                val genConfig = LocalPerfTuner.buildGenerationConfig(guard, maxTokens)
                val filter = LocalToolStreamFilter()
                val sink = FilterSink(this@channelFlow)
                var usage: LocalUsage? = null
                var finishReason = "stop"
                var toolCalls = 0
                var failed = false

                suspend fun consume(events: List<LocalFilterEvent>) {
                    for (event in events) {
                        when (event) {
                            is LocalFilterEvent.Visible -> if (event.text.isNotEmpty()) {
                                sink.send(LocalStreamEvent.TextDelta(event.text))
                            }

                            is LocalFilterEvent.Thinking -> if (event.text.isNotEmpty()) {
                                sink.send(LocalStreamEvent.ReasoningDelta(event.text))
                            }

                            is LocalFilterEvent.Calls -> {
                                toolCalls += event.calls.size
                                event.calls.forEach { sink.send(LocalStreamEvent.ToolCall(it)) }
                            }
                        }
                    }
                }

                try {
                    current.generateStreamFlow(prompt, genConfig).collect { result ->
                        when (result) {
                            is LlmStreamResult.Token -> consume(filter.feed(result.text))

                            is LlmStreamResult.Completed -> {
                                val profile = result.profile
                                if (profile != null) {
                                    usage = profile.toLocalUsage()
                                    if (profile.stopReason == "length") finishReason = "length"
                                    _state.value = _state.value.copy(
                                        lastSpeedTokensPerSecond = profile.decodingSpeed,
                                        lastGeneratedTokens = profile.generatedTokens,
                                    )
                                }
                            }

                            is LlmStreamResult.Error -> {
                                failed = true
                                sink.send(
                                    LocalStreamEvent.Failed(
                                        result.throwable.message ?: "本地推理失败",
                                        500,
                                    )
                                )
                            }

                            else -> {}
                        }
                    }
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    failed = true
                    sink.send(LocalStreamEvent.Failed(throwable.message ?: "本地推理异常", 500))
                } finally {
                    if (!currentCoroutineContext().isActive) {
                        withContext(NonCancellable) { runCatching { current.stopStream() } }
                    }
                }

                if (failed) return@withLock
                consume(filter.finish())
                if (toolCalls > 0) {
                    finishReason = "tool_calls"
                }
                usage?.let { sink.send(LocalStreamEvent.Usage(it)) }
                sink.send(LocalStreamEvent.Finished(finishReason))
            }
            } finally {
                if (inferLeased) cn.yangrq.weixuan.agent.runtime.AgentExecutionService.release(inferLease)
            }
        }.flowOn(Dispatchers.IO)
    }

    /** 用 channelFlow 的 ProducerScope 包一层，便于在嵌套作用域里发送事件。 */
    private class FilterSink(private val scope: ProducerScope<LocalStreamEvent>) {
        suspend fun send(event: LocalStreamEvent) {
            scope.send(event)
        }
    }

    /**
     * 构造 prompt：
     * 1. 优先使用模型原生 chat template 的工具支持（`applyChatTemplate(messages, toolsJson)`），
     *    仅当格式化结果里确实出现工具定义时才采用；
     * 2. 否则回退到文本工具协议（system 消息注入 + 工具轮次文本化）。
     */
    /**
     * 工具集【压缩】而非裁剪（2026-09-25 重设计）：
     * 案发：早期实现按窗口删工具（31→7），导致 Agent 丢失 UI 操作类工具，
     * 多步任务（打开抖音并搜索）只能完成第一步——删工具 = 删能力。
     * 现改为程序化压缩 schema：截短工具描述（核心语义保留）+ 删除参数级冗长说明，
     * 31 工具 token 从 ~8k 降到 ~2k，窗口装得下且能力完整（4B/14B 均受益）。
     */
    private fun limitToolsForWindow(tools: JSONArray, nCtx: Int): JSONArray {
        // 关键修复（2026-09-27）：此前把工具描述截到 40 字符并删除全部参数级 description，
        // 这恰恰删掉了小模型填参数所需的唯一线索 → Agent 多步任务只完成第一步
        // （"打开抖音"之后不再搜索）。工具契约必须完整保留。
        return tools
    }

    private suspend fun buildPrompt(
        current: LlmWrapper,
        messages: JSONArray,
        tools: JSONArray,
        hasTools: Boolean,
    ): String? {
        // 思考过程诱导注入（纯提示词层）：不注入 /think、不启用 SDK thinking——
        // GenieX v0.7.0 的 thinking 路径会 ggml_backend_sched_alloc_graph 崩溃（2026-09-25 实证）。
        // 仅以 system 指令引导模型自愿输出思考标签（FilterSink 原生支持该协议）。
        if (LocalSettings.thinkingEnabled) {
            var sysIndex = -1
            for (i in 0 until messages.length()) {
                if (messages.optJSONObject(i)?.optString("role") == "system") {
                    sysIndex = i
                    break
                }
            }
            if (sysIndex >= 0) {
                val sys = messages.optJSONObject(sysIndex)
                sys?.put("content", sys.optString("content") + LocalChatConversion.THINKING_INSTRUCTION)
            } else {
                messages.put(
                    0,
                    org.json.JSONObject().apply {
                        put("role", "system")
                        put("content", LocalChatConversion.THINKING_INSTRUCTION.trimStart())
                    },
                )
            }
        }
        if (hasTools) {
            val nativeOutput = runCatching {
                current.applyChatTemplate(
                    messages = toGenieXMessages(messages, fallback = false).toTypedArray(),
                    tools = tools.toString(),
                    enableThinking = false, // SDK thinking 路径崩溃（ggml_abort），思考改由提示词层诱导
                )
            }.getOrNull()?.getOrNull()
            val formatted = nativeOutput?.formattedText
            if (!formatted.isNullOrBlank() && LocalToolProtocol.isToolCallingSupported(formatted, tools)) {
                Log.i(TAG, "使用原生 chat template 工具支持，prompt=${formatted.length} 字符")
                return formatted
            }
        }
        val fallbackMessages = toGenieXMessages(messages, fallback = true)
        if (hasTools) {
            LocalChatConversion.injectToolProtocol(fallbackMessages, tools)
        } else {
            return runCatching {
                current.applyChatTemplate(
                    messages = fallbackMessages.toTypedArray(),
                    tools = null,
                    enableThinking = false, // SDK thinking 路径崩溃（ggml_abort），思考改由提示词层诱导
                )
            }.getOrNull()?.getOrNull()?.formattedText
        }
        val output = runCatching {
            current.applyChatTemplate(
                messages = fallbackMessages.toTypedArray(),
                tools = null,
                enableThinking = false, // SDK thinking 路径崩溃（ggml_abort），思考改由提示词层诱导
            )
        }.getOrNull()?.getOrNull()?.formattedText
        Log.i(TAG, "使用文本工具协议回退，prompt=${output?.length ?: 0} 字符")
        return output
    }

    // ---------------------------------------------------------------- 模型解析

    private data class ResolvedModel(
        val modelPath: String,
        val tokenizerPath: String?,
        val precision: String,
        val key: String,
    )

    private suspend fun resolveModel(target: String): ResolvedModel? {
        val customPath = LocalSettings.customModelPath
        if (customPath.isNotBlank()) {
            val file = File(customPath)
            if (!file.exists()) return null
            val tokenizer = LocalSettings.customTokenizerPath.takeIf { it.isNotBlank() }
            return ResolvedModel(
                modelPath = file.absolutePath,
                tokenizerPath = tokenizer,
                precision = "custom",
                key = "custom:${file.absolutePath}",
            )
        }
        val paths = runCatching { ModelManagerWrapper.getPaths(target) }.getOrNull() ?: return null
        val modelPath = paths.model_path?.takeIf { it.isNotBlank() } ?: return null
        val precision = LocalSettings.modelPrecision
        return ResolvedModel(
            modelPath = modelPath,
            tokenizerPath = paths.tokenizer_path?.takeIf { it.isNotBlank() },
            precision = precision,
            key = "$target@$precision",
        )
    }

    /** 推测解码 draft 模型：优先用户导入的本地文件，其次本机已存在的 GenieX 模型。 */
    /**
     * 【已禁用 2026-09-25】推测解码（draft）不可用：
     * GenieX v0.7.0 的推测解码是 EAGLE 专用实现（需要含 draft_embed/draft_feat_* 等张量的
     * EAGLE draft 模型）；普通 GGUF 小模型（如 Qwen3-0.6B）传入会触发 native 异常
     * `unknown speculative type: draft`（libc++abi 终止 → 进程崩溃）。
     * 待 SDK 支持通用 draft 或引入 EAGLE 模型后再启用。
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun resolveDraftModelPath(target: String): String? = null
}
