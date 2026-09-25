package cn.yangrq.weixuan

import android.app.Application
import android.os.Handler
import android.os.Looper
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
                if (LocalSettings.autoLoad) {
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
