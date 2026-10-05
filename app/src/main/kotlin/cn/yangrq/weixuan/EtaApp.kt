package cn.yangrq.weixuan

import android.app.Application
import android.os.Handler
import android.os.Looper
import cn.yangrq.weixuan.agent.model.AgentToolResultBudget
import cn.yangrq.weixuan.agent.skill.SkillRuntime
import cn.yangrq.weixuan.agent.device.RootAccess
import cn.yangrq.weixuan.agent.terminal.TerminalRuntime
import cn.yangrq.weixuan.config.Prefs
import cn.yangrq.weixuan.core.AndroidAgentLogger
import cn.yangrq.weixuan.core.safeLogType
import cn.yangrq.weixuan.data.datastore.SettingsDataStore
import cn.yangrq.weixuan.data.repository.AgentMemoryRepository
import cn.yangrq.weixuan.data.repository.AppearanceSettingsRepository
import cn.yangrq.weixuan.data.repository.McpServerRepository
import cn.yangrq.weixuan.data.repository.LinuxEnvironmentSettingsRepository
import cn.yangrq.weixuan.data.repository.ProviderRepository
import cn.yangrq.weixuan.local.GenieXLocalEngine
import cn.yangrq.weixuan.local.LocalServerHost
import cn.yangrq.weixuan.local.LocalSettings
import cn.yangrq.weixuan.ui.app.PredictiveBackController
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 模块 UI 进程的 Application。
 *
 * 在进程启动时注册 [XposedServiceHelper] 监听器，框架会通过 XposedProvider 推送 binder，
 * 随后 UI 即可拿到 [XposedService] 写入 RemotePreferences，跨进程同步到各 hook 进程。
 *
 * UI 侧通过 [XposedService] 写入 RemotePreferences。
 */
class EtaApp : Application(), XposedServiceHelper.OnServiceListener {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.initLocal(this)
        if (!AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName)) {
            return
        }
        TerminalRuntime.initialize(this)
        // 工具结果全文落盘目录（超长结果截断后给出文件路径，模型可按需取回）——见 AgentToolResultBudget。
        AgentToolResultBudget.configure(this)
        RootAccess.initialize(this)
        SettingsDataStore.init(this)
        val predictiveBackEnabled = runBlocking(Dispatchers.IO) {
            AppearanceSettingsRepository.settings().predictiveBackEnabled
        }
        PredictiveBackController.apply(applicationInfo, predictiveBackEnabled)
        AgentMemoryRepository.init(this)
        ProviderRepository.init(this)
        LocalSettings.init(this)
        // 本地模型（GenieX NPU/GPU）：仅在用户开启时启动回环 OpenAI 兼容服务；
        // 未开启则完全不初始化 SDK、不占用内存。
        // 顺序约束：先 bind 端口再 initialize SDK——SDK 原生初始化可能耗时数十秒，
        // 期间 Agent 若发起模型请求，服务必须已在监听（快速返回 503），
        // 而不是连接被拒触发"模型请求暂时中断"重试链（2026-09-24 23:10 故障根因）。
        if (LocalSettings.serverEnabled) {
            applicationScope.launch {
                // 预热耗时基线（2026-10-04）：把「进程起来 → 后端探测 → 模型就绪」拆开计时。
                // 实测冷启动到就绪 ≈ 20s（前置约 6.6s + 模型加载约 13.3s），需要知道前置里花在哪。
                val warmStartMs = android.os.SystemClock.elapsedRealtime()
                // 后端设备探测预热（2026-10-02）——必须在任何推理启动之前完成，否则会出现
                // 「探测说不能用、其实能用」的错配。
                // 背景：探测要 dlopen 最重 44MB 的 libggml-vulkan-adreno.so 并起一次
                // Vulkan 实例，放在模型设置页的组合里做有两个问题：
                //   ① 页面首次组合时若中途被回收，只会探到第一个口味（实测只探到 HTP0），
                //      Vulkan 选项就永远不出现；
                //   ② 每次进页面都要付一次进程启动 + dlopen 的代价。
                // 放进进程级 IO 作用域预热，结果进 LocalBackend 的进程内缓存，后续全部命中。
                runCatching {
                    cn.yangrq.weixuan.local.LocalBackend.entries.forEach {
                        cn.yangrq.weixuan.local.LocalBackend.isAvailable(this@EtaApp, it)
                    }
                }
                // 模型落位迁移（2026-09-30）——必须在所有引擎分支之前执行。
                // 把外部存储（FUSE）上的 .gguf 搬到内部存储（原生 f2fs）：llama.cpp 在
                // FUSE 路径上 O_DIRECT 会读到错误数据而回退 buffered I/O → 每 token 数百次
                // 大缺页（官方基准 314~1894 次 vs O_DIRECT 6~10 次）→ 大模型加载被拖过
                // 预算，表现为「模型请求暂时中断，N 秒后重试」+ 整机卡。
                // 幂等；无 root 时静默退回 FUSE（功能不受影响，只是慢）。
                runCatching {
                    val moved = LocalSettings.migrateModelsToInternal(this@EtaApp)
                    if (moved > 0) {
                        AndroidAgentLogger.info("已迁移 $moved 个模型到内部存储（启用 O_DIRECT 快路径）")
                    }
                }
                // 自建 llama.cpp runtime（2026-09-25）：llama-server 子进程监听同一端口，
                // 官方 mmap 按需加载（14B 只驻留几百 MB）+ KV 量化 + flash-attn——解决大模型加载崩溃。
                if (LocalSettings.useSelfBuiltEngine) {
                    // 模型兜底（2026-09-27）：用户删除/换模型后旧路径会指向不存在的文件，
                    // 导致自动加载直接失败。这里回落到模型目录下的默认 4B（日常最快最稳）。
                    val configured = LocalSettings.customModelPath
                    val modelPath = if (configured.isNotBlank() && java.io.File(configured).exists()) {
                        configured
                    } else {
                        val internalFallback = java.io.File(
                            LocalSettings.internalModelsDir(this@EtaApp),
                            LocalSettings.DEFAULT_GGUF_NAME,
                        )
                        val fallback = if (internalFallback.exists()) {
                            internalFallback
                        } else {
                            java.io.File(LocalSettings.modelsDir(this@EtaApp), LocalSettings.DEFAULT_GGUF_NAME)
                        }
                        if (fallback.exists()) {
                            LocalSettings.customModelPath = fallback.absolutePath
                            AndroidAgentLogger.info("原模型不可用，已回落到默认 4B：${fallback.name}")
                            fallback.absolutePath
                        } else {
                            configured
                        }
                    }
                    if (modelPath.isNotBlank() && java.io.File(modelPath).exists()) {
                        val preloadStartMs = android.os.SystemClock.elapsedRealtime()
                        // 自动加载重试（2026-09-28 调整）：从 5 次 × 30 秒收敛到 3 次 × 5 秒。
                        // 原设计为"等系统回收内存后自行成功"，但 150 秒的等待对调试/日常都太久；
                        // 内存不足时应由用户清理后台（日志仍会明确提示）。
                        var ok = cn.yangrq.weixuan.local.LlamaServerProcess.start(
                            this@EtaApp,
                            modelPath,
                            cn.yangrq.weixuan.local.LocalSettings.DEFAULT_PORT,
                        )
                        var attempt = 0
                        while (!ok && attempt < 3) {
                            attempt++
                            AndroidAgentLogger.info(
                                "自建 runtime 暂未就绪（多为内存不足），5 秒后自动重试 $attempt/3",
                            )
                            kotlinx.coroutines.delay(5_000)
                            if (cn.yangrq.weixuan.local.LlamaServerProcess.isRunning()) {
                                ok = true
                                break
                            }
                            ok = cn.yangrq.weixuan.local.LlamaServerProcess.start(
                                this@EtaApp,
                                modelPath,
                                cn.yangrq.weixuan.local.LocalSettings.DEFAULT_PORT,
                            )
                        }
                        // 慢重试阶段（2026-10-04 加）：实测冷启动到就绪 ≈ 6.6s 前置 + 13.3s 加载。
                        // 旧实现在快重试 15 秒后**彻底放弃**，于是用户发问时才现加载 —— 他感知到的
                        // 「加上工具要等 20 秒 / 正在加载模型」正是这段。用户通常几十秒后才真正发问，
                        // 因此这里继续以 15 秒为间隔续试（约 3 分钟上限），让「发问前就已就绪」成为常态；
                        // 仍然失败则保持原行为（日志明确指引手动加载），不做无限等待。
                        var slow = 0
                        while (!ok && slow < 12 && LocalSettings.serverEnabled) {
                            slow++
                            AndroidAgentLogger.info(
                                "自建 runtime 慢重试 $slow/12（15 秒一次，约 3 分钟）：发问前就绪即可免去临场加载",
                            )
                            kotlinx.coroutines.delay(15_000)
                            if (cn.yangrq.weixuan.local.LlamaServerProcess.isRunning()) {
                                ok = true
                                break
                            }
                            ok = cn.yangrq.weixuan.local.LlamaServerProcess.start(
                                this@EtaApp,
                                modelPath,
                                cn.yangrq.weixuan.local.LocalSettings.DEFAULT_PORT,
                            )
                        }
                        AndroidAgentLogger.info(
                            if (ok) {
                                val nowMs = android.os.SystemClock.elapsedRealtime()
                                val loadCost = nowMs - preloadStartMs
                                val frontCost = preloadStartMs - warmStartMs
                                "自建 runtime 已就绪（llama-server :${LocalSettings.port}/v1），" +
                                    "预热总用时 ${nowMs - warmStartMs}ms（前置 ${frontCost}ms + 加载 ${loadCost}ms）"
                            } else {
                                "自建 runtime 多次重试仍未就绪：请清理后台后打开「本地模型」页手动加载"
                            },
                        )
                    } else {
                        AndroidAgentLogger.warn("自建 runtime：未配置模型文件（customModelPath 为空）")
                    }
                    return@launch
                }
                val boundPort = LocalServerHost.startAndSync(GenieXLocalEngine, LocalSettings.port)
                if (boundPort <= 0) {
                    AndroidAgentLogger.warn("本地推理服务启动失败：候选端口全部被占用")
                } else {
                    AndroidAgentLogger.info("本地推理服务已启动：http://127.0.0.1:$boundPort/v1")
                    LocalServerHost.selfCheck(boundPort)
                    // 常驻前台租约（2026-09-25）：本地 OpenAI 服务是「随时可调用」的基础设施
                    // （Agent 后台任务 / 语音唤起 / 外部客户端），MIUI 冻结后台进程会让服务假死。
                    // 用户启用本地服务即持有（不释放）；关闭服务开关后重启自然解除。
                    runCatching {
                        val serverLease = "local-openai-server"
                        val leased = cn.yangrq.weixuan.agent.runtime.AgentExecutionService.acquire(this@EtaApp, serverLease) { }
                        AndroidAgentLogger.info("本地服务常驻租约：${if (leased) "已生效（后台随时可调用）" else "未生效（服务仅前台可用）"}")
                    }.onFailure { AndroidAgentLogger.warn("本地服务常驻租约获取失败：${it.message}") }
                }
                // SDK 原生初始化可能耗时数十秒：同样持前台租约防清理，并打耗时日志定位卡点。
                val initLease = "geniex-sdk-init"
                val initLeased = cn.yangrq.weixuan.agent.runtime.AgentExecutionService.acquire(this@EtaApp, initLease) { }
                try {
                    val initStart = android.os.SystemClock.elapsedRealtime()
                    GenieXLocalEngine.initialize(this@EtaApp)
                    AndroidAgentLogger.info("GenieX SDK 初始化完成：耗时 ${android.os.SystemClock.elapsedRealtime() - initStart}ms")
                } finally {
                    if (initLeased) cn.yangrq.weixuan.agent.runtime.AgentExecutionService.release(initLease)
                }
                // 大模型（>5GB）不自动加载（2026-09-25 安全）：加载峰值可能触发系统级内存压力，
                // 必须由用户在前台手动触发（可配合内存监控），避免"App 启动即卡死"。
                val targetModelMb = runCatching {
                    val p = LocalSettings.customModelPath
                    if (p.isNotBlank()) (java.io.File(p).length() / 1024 / 1024).toInt() else 0
                }.getOrDefault(0)
                if (targetModelMb > 5000) {
                    AndroidAgentLogger.warn(
                        "目标模型 ${targetModelMb}MB 属大模型：已跳过自动加载，请在「本地模型」页手动点「加载模型」",
                    )
                } else if (LocalSettings.autoLoad) {
                    // 安全边界（2026-09-25 手机卡死事故）：最多自动尝试 2 次，
                    // 内存/温度/冷却类拒绝一律不重试——反复触发大内存分配会把系统压垮。
                    var loaded = false
                    for (attempt in 1..2) {
                        val result = GenieXLocalEngine.loadModel()
                        if (result.isSuccess) {
                            loaded = true
                            break
                        }
                        val error = result.exceptionOrNull()?.message ?: "未知错误"
                        AndroidAgentLogger.warn("本地模型自动加载失败（第 $attempt/2 次）：$error")
                        if (error.contains("内存") || error.contains("温度") || error.contains("过热") || error.contains("冷却")) break
                        delay(20_000)
                    }
                    if (!loaded) {
                        AndroidAgentLogger.warn("本地模型自动加载未成功（安全边界已止损）。可稍后在「本地模型」页手动加载，或换更小的模型")
                    }
                }
            }
        }
        McpServerRepository.init(this)
        XposedServiceHelper.registerListener(this)
        applicationScope.launch {
            LinuxEnvironmentSettingsRepository.initialize(this@EtaApp)
            runCatching {
                SkillRuntime.createIndexService(this@EtaApp).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}"
                )
            }
        }
    }

    override fun onServiceBind(service: XposedService) {
        serviceInstance = service
        Prefs.reconcileAgentPreferences(service)
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        // 只有当前持有的 service 死亡时才清空并派发 null；
        // 多 framework 场景下死掉的可能是已被替换的旧实例，无需影响 UI。
        if (serviceInstance === service) {
            serviceInstance = null
            dispatch(null)
        }
    }

    companion object {
        @Volatile
        var serviceInstance: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                dispatchTo(listener, serviceInstance)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(service: XposedService?) {
            listeners.forEach { dispatchTo(it, service) }
        }

        private fun dispatchTo(listener: ServiceStateListener, service: XposedService?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onServiceStateChanged(service)
            } else {
                mainHandler.post {
                    if (listeners.contains(listener)) {
                        listener.onServiceStateChanged(service)
                    }
                }
            }
        }
    }
}
