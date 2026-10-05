package cn.yangrq.weixuan.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import cn.yangrq.weixuan.agent.device.RootAccess
import cn.yangrq.weixuan.agent.runtime.AgentExecutionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * **服务器模式保活加固**（2026-10-05）。
 *
 * 目标：本地推理服务器一旦开启，就尽量别被系统在后台掐掉——llama-server 是 GB 级模型常驻的
 * 持续负载，冷启动一次要数十秒，被 MIUI/澎湃「一键清理」或 LMK 杀掉后用户感知极差。
 *
 * ── 双轨设计（无 root 也能用一部分）────────────────────────────────────────
 * **无 root 可用**：
 * 1. **前台服务 + 常驻通知**：复用已声明的 [AgentExecutionService]（`FOREGROUND_SERVICE` +
 *    `FOREGROUND_SERVICE_SPECIAL_USE` 权限齐备），进入「服务器保活」模式后它常驻前台并显示
 *    「微玄推理服务器运行中 · host:port」。前台服务是防冻结/防回收最有效的无 root 手段。
 * 2. **克制的 PartialWakeLock**：**仅在「服务器模式开启 + 引擎已加载 + 保活已武装」时持有**，
 *    引擎停止（用户卸载 / 热断路器暂停）立即释放；且**带 1 小时超时**（超时自动放锁，
 *    不无限持锁——无条件 24/7 持锁会持续耗电发热，而发烫正是本项目已知的核心痛点）。
 * 3. **轻量看门狗**：独立于 [LlamaServerProcess.slotScope] 的**应用级 scope**（与热断路器
 *    同一套「对象级长生命周期 scope + 协程轮询」模式），定期检查引擎是否还活着；被系统杀但
 *    服务器模式仍开着 → **带指数退避**自动拉起；**连续失败 [MAX_RESTARTS] 次后停止重试并通知用户**。
 * **有 root 才有的加固**（[RootAccess.isGranted] 为真时才执行，任一步失败仅记日志、绝不影响功能）：
 * 4. App 与 llama-server 子进程 `oom_score_adj` 调低到 -800（降低被 LMK 杀的概率；重启后被
 *    系统重置属正常，看门狗会重新施加）。
 * 5. `dumpsys deviceidle whitelist +cn.yangrq.weixuan` 加入 Doze 电池优化白名单。
 * 6. 无 `WAKE_LOCK` 权限时的兜底：用 root 写内核 wakelock（`/sys/power/wake_lock`）持续唤醒。
 *
 * ── ❗最高优先约束 ────────────────────────────────────────────────────────
 * **这一切只在 `LocalSettings.localServerEnabled == true` 时生效。** [onEngineReady] 第一行即
 * 判该门控，[armWatchdog] 与看门狗循环也各判一次；未开服务器模式的用户**不写新键、不起新服务、
 * 不持锁**，行为与旧版逐字节不变。
 */
object LocalServerKeepAlive {

    private const val TAG = "LsKeepAlive"

    /** root 加固专用日志 tag（与主流程分开，便于 `logcat -s LsKeepAliveRoot` 诊断）。 */
    private const val ROOT_TAG = "LsKeepAliveRoot"

    private const val WAKE_LOCK_TAG_TXT = "weixuan:local_server"

    /** 看门狗轮询间隔。与热断路器同量级（~20 秒），足够发现「被系统杀掉」。 */
    private const val POLL_MS = 20_000L

    /** 指数退避基数 / 上限：第 n 次失败等待 min(BASE * 2^(n-1), MAX)。 */
    private const val BACKOFF_BASE_MS = 5_000L
    private const val BACKOFF_MAX_MS = 120_000L

    /** 连续失败达到该次数即放弃自动重拉，并通知用户（避免疯狂重拉耗电发热）。 */
    private const val MAX_RESTARTS = 5

    /** PartialWakeLock 超时（1 小时）：超时自动释放，杜绝「忘记放锁导致 24/7 持锁」。 */
    private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L

    /** 内核 wakelock 名（root 兜底用）。 */
    private const val KERNEL_WAKE_LOCK_NAME = "weixuan_local_server"

    private const val CHANNEL_KEEPALIVE = "eta_server_keepalive"
    private const val NOTIFY_GIVEUP_ID = 18788

    /** 保活状态快照（供 UI 展示「前台服务/锁/看门狗是否活跃」）。 */
    data class Status(
        /** 是否已「武装」：引擎就绪且服务器模式开启，看门狗有权自动重拉。 */
        val armed: Boolean = false,
        /** 前台服务是否活跃（[AgentExecutionService] 服务器保活模式）。 */
        val serviceActive: Boolean = false,
        /** 是否真正持有 PartialWakeLock（无权限时为 false）。 */
        val wakeLockHeld: Boolean = false,
        /** 看门狗协程是否活跃。 */
        val watchdogActive: Boolean = false,
        /** 已自动重拉引擎成功的次数。 */
        val restartCount: Int = 0,
        /** 是否已因连续失败放弃自动重拉。 */
        val gaveUp: Boolean = false,
        /** 最近一次错误/说明（给 UI 一句话）。 */
        val lastError: String? = null,
        /** root 加固是否已施加（至少一条命令成功）。 */
        val rootApplied: Boolean = false,
        /** root 加固结果摘要（逐条命令的成败）。 */
        val rootDetail: String = "",
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * 看门狗专用**应用级 scope**（本对象单例长生命周期）。
     *
     * 刻意**不复用** [LlamaServerProcess.slotScope]：`stop()` 会取消 slot 保存任务，而保活
     * 看门狗必须跨 stop/start 存活（它要负责把被杀的引擎拉回来），生命周期必须与 slot 解耦。
     * 这与热断路器 [LlamaServerProcess.thermalScope] 的做法一致。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var watchdogJob: Job? = null

    @Volatile
    private var appCtx: Context? = null

    /** 「武装」标志：引擎就绪时置真，[LlamaServerProcess] 调用 stop() 时置假。 */
    @Volatile
    private var armed = false

    @Volatile
    private var modelPath: String? = null

    @Volatile
    private var port: Int = -1

    /** 累计成功重拉次数（展示用）。 */
    @Volatile
    private var restarts = 0

    /** 看门狗内部「连续失败」计数（成功观测到引擎存活即归零）。 */
    @Volatile
    private var consecutiveFailures = 0

    /** 是否已放弃自动重拉。 */
    @Volatile
    private var gaveUp = false

    private var wakeLock: PowerManager.WakeLock? = null

    // ══════════════════════════════════════════════════════════════════════
    //  生命周期入口（由 LlamaServerProcess 在引擎就绪 / 停止时调用）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 引擎就绪后调用。**第一道也是最重要的门控**：服务器模式未开启直接返回，
     * 不写键、不起服务、不持锁、不起协程。
     */
    fun onEngineReady(context: Context, modelPath: String, port: Int) {
        if (!LocalSettings.localServerEnabled) return // ❗最高优先约束：只在服务器模式下生效
        val app = context.applicationContext
        appCtx = app
        this.modelPath = modelPath
        this.port = port
        armed = true
        gaveUp = false
        consecutiveFailures = 0
        Log.i(TAG, "保活武装：引擎就绪（$modelPath @ $port），服务器模式已开启")
        startForegroundService(app)
        acquireWakeLock(app)
        armWatchdog(app)
        applyRootHardening(app)
        refreshStatus()
    }

    /**
     * 引擎停止后调用（用户卸载模型 / 热断路器因高温暂停 / 进程内重建）。
     *
     * 语义：**放下所有保活手段**——停前台服务、释放唤醒锁、停看门狗、解除「武装」。
     * 之所以要解除武装：stop() 代表「引擎已停」，此后看门狗若仍武装就会把用户/热断路器
     * **有意停掉**的引擎又拉回来。需要在停止后自动恢复的场景（热断路器降温恢复）由
     * [LlamaServerProcess] 自己重新 `start()` → 再次 [onEngineReady] 完成。
     */
    fun onEngineStopped() {
        if (!armed && watchdogJob == null && !AgentExecutionService.isServerKeepAliveActive) {
            // 从未武装过：什么都不做（未开启服务器模式的用户不会走到这里，即便走到也无副作用）
            return
        }
        armed = false
        stopWatchdog()
        releaseWakeLock()
        releaseKernelWakeLock()
        appCtx?.let { AgentExecutionService.releaseServerKeepAlive(it) }
        Log.i(TAG, "保活解除：引擎已停止（前台服务/唤醒锁/看门狗全部收起）")
        refreshStatus()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ① 前台服务（复用 AgentExecutionService 的服务器保活模式）
    // ══════════════════════════════════════════════════════════════════════

    private fun startForegroundService(context: Context) {
        val ok = runCatching { AgentExecutionService.acquireServerKeepAlive(context) }
            .getOrElse {
                Log.w(TAG, "启动前台服务失败（忽略，功能不受影响）：${it.message}")
                false
            }
        if (!ok) Log.w(TAG, "前台服务未能启动：保活降级为仅看门狗")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ② 克制的 PartialWakeLock
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 持锁条件（三者同时满足才会走到这里）：服务器模式开启 · 引擎已加载 · 保活已武装。
     * 带 1 小时超时——即「不是无条件 24/7 持锁」，到期自动放；看门狗会周期性续期。
     *
     * 注意：本项目的 `AndroidManifest.xml` **未声明 `android.permission.WAKE_LOCK`**，
     * 因此 `acquire()` 会抛 `SecurityException`。此处在 runCatching 内静默降级（只记日志），
     * 由 root 轨的内核 wakelock 兜底；补齐权限后本方法即可直接生效，无需改代码。
     */
    private fun acquireWakeLock(context: Context) {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG_TXT)
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLock = lock
            Log.i(TAG, "已持有 PartialWakeLock（${WAKE_LOCK_TIMEOUT_MS / 60000} 分钟后自动释放）")
        }.onFailure {
            Log.w(
                TAG,
                "未能持有 PartialWakeLock（多因 manifest 缺 android.permission.WAKE_LOCK）：" +
                    "type=${it.javaClass.simpleName} msg=${it.message}；保活改由前台服务+看门狗承担",
            )
        }
    }

    /** 看门狗续期：锁已超时释放（引擎仍活着）时重新获取；发动机已停则不续。 */
    private fun renewWakeLockIfNeeded() {
        val ctx = appCtx ?: return
        if (!armed || !LlamaServerProcess.isRunning()) return
        if (wakeLock?.isHeld != true) acquireWakeLock(ctx)
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ③ 轻量看门狗（独立 scope，退避重拉）
    // ══════════════════════════════════════════════════════════════════════

    private fun armWatchdog(context: Context) {
        if (!LocalSettings.localServerEnabled) return // 再次门控（防御性）
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            Log.i(TAG, "保活看门狗已启动（每 ${POLL_MS / 1000}s 检查；退避上限 ${BACKOFF_MAX_MS / 1000}s）")
            while (isActive) {
                delay(POLL_MS)
                // 运行期门控①：用户随时可能关掉服务器模式 → 立即退出，不留后台任务
                if (!LocalSettings.localServerEnabled) {
                    Log.i(TAG, "服务器模式已关闭，看门狗退出")
                    break
                }
                // 运行期门控②：已解除武装（引擎被有意停止）→ 退出
                if (!armed) {
                    Log.i(TAG, "保活已解除武装，看门狗退出")
                    break
                }
                if (gaveUp) {
                    Log.i(TAG, "已放弃自动重拉，看门狗退出")
                    break
                }
                // 运行期门控③：热断路器已因高温暂停引擎 → 不与之争抢（交由断路器负责恢复）
                if (LlamaServerProcess.thermalGuardState !=
                    LlamaServerProcess.ThermalGuardState.NORMAL
                ) {
                    Log.i(TAG, "热断路器处于暂停态，看门狗本轮不重拉（避免与降温恢复打架）")
                    continue
                }
                if (LlamaServerProcess.isRunning()) {
                    consecutiveFailures = 0
                    renewWakeLockIfNeeded()
                    continue
                }
                // ── 引擎已不在运行，但服务器模式仍开着 → 尝试自动拉起（带退避）──
                val ctx = appCtx ?: break
                val path = modelPath ?: break
                val backoff = (BACKOFF_BASE_MS shl consecutiveFailures.coerceAtMost(6))
                    .coerceAtMost(BACKOFF_MAX_MS)
                Log.w(
                    TAG,
                    "检测到引擎已停止（服务器模式仍开启），第 ${consecutiveFailures + 1} 次尝试重拉，" +
                        "${backoff / 1000}s 后执行",
                )
                delay(backoff)
                if (!armed || !LocalSettings.localServerEnabled) break
                if (LlamaServerProcess.isRunning()) { consecutiveFailures = 0; continue }
                val ok = runCatching { LlamaServerProcess.start(ctx, path, port) }
                    .getOrElse {
                        Log.e(TAG, "重拉引擎抛异常：${it.message}")
                        false
                    }
                if (ok) {
                    restarts++
                    consecutiveFailures = 0
                    Log.i(TAG, "引擎已自动拉起（累计第 $restarts 次）")
                } else {
                    consecutiveFailures++
                    Log.w(
                        TAG,
                        "自动拉起失败（连续 $consecutiveFailures 次）：" +
                            "${LlamaServerProcess.lastFailureReason ?: "无详细原因"}",
                    )
                    if (consecutiveFailures >= MAX_RESTARTS) {
                        gaveUp = true
                        val reason = LlamaServerProcess.lastFailureReason ?: "未知原因"
                        notifyGiveUp(ctx, reason)
                        Log.e(TAG, "连续 $MAX_RESTARTS 次重拉失败，停止自动重试：$reason")
                        break
                    }
                }
            }
            refreshStatus()
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /** 连续失败达上限后通知用户（best-effort：通知失败不影响功能）。 */
    private fun notifyGiveUp(context: Context, reason: String) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_KEEPALIVE,
                    "推理服务器保活",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            val notification = android.app.Notification.Builder(context, CHANNEL_KEEPALIVE)
                .setSmallIcon(cn.yangrq.weixuan.R.drawable.ic_notification)
                .setContentTitle("推理服务器未在运行")
                .setContentText("已连续 $MAX_RESTARTS 次无法自动拉起本地引擎（$reason），请到「本地推理服务器」页手动检查")
                .setStyle(
                    android.app.Notification.BigTextStyle()
                        .bigText("已连续 $MAX_RESTARTS 次无法自动拉起本地推理引擎，已停止自动重试。\n原因：$reason\n可在「本地推理服务器」页手动重新加载模型。"),
                )
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFY_GIVEUP_ID, notification)
        }.onFailure { Log.w(TAG, "放弃重拉通知发送失败（忽略）：${it.message}") }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ④ root 加固（rootAvailable 为真时才做，失败静默降级）
    // ══════════════════════════════════════════════════════════════════════

    private fun applyRootHardening(context: Context) {
        scope.launch {
            if (!RootAccess.isGranted) {
                Log.i(ROOT_TAG, "无 root（RootAccess 未授权），跳过所有 root 加固；无 root 保活由前台服务+看门狗承担")
                refreshStatus()
                return@launch
            }
            val results = LinkedHashMap<String, Boolean>()

            // ① 应用自身 oom_score_adj（避免主进程被 LMK 优先回收）
            val myPid = android.os.Process.myPid()
            results["app oom_score_adj($myPid)"] = runRoot(
                "echo -800 > /proc/$myPid/oom_score_adj",
            )

            // ② llama-server 子进程 oom_score_adj（它是 GB 级 RSS，最容易被 LMK 盯上）
            results["llama-server oom_score_adj"] = runRoot(
                "for p in \$(pgrep -f libllama-server); do echo -800 > /proc/\$p/oom_score_adj; done",
            )

            // ③ 加入 Doze/电池优化白名单（重启后被重置属正常，看门狗再次武装时会重新施加）
            results["deviceidle whitelist"] = runRoot(
                "dumpsys deviceidle whitelist +cn.yangrq.weixuan",
            )

            // ④ 内核 wakelock 兜底（manifest 未声明 WAKE_LOCK 时，用 root 侧持续唤醒）
            val ipcOk = runRoot("echo $KERNEL_WAKE_LOCK_NAME > /sys/power/wake_lock")
            kernelWakeLockApplied = ipcOk
            results["kernel wake_lock"] = ipcOk

            val detail = results.entries.joinToString("、") { (k, v) -> "$k=${if (v) "OK" else "失败"}" }
            Log.i(ROOT_TAG, "root 加固完成：$detail（任一步失败均不影响功能，仅记日志）")
            rootAppliedFlag = results.values.any { it }
            rootDetailText = detail
            refreshStatus()
        }
    }

    /** 释放 root 侧内核 wakelock（best-effort）。 */
    private fun releaseKernelWakeLock() {
        if (!kernelWakeLockApplied) return
        if (!RootAccess.isGranted) { kernelWakeLockApplied = false; return }
        runRoot("echo $KERNEL_WAKE_LOCK_NAME > /sys/power/wake_unlock")
        kernelWakeLockApplied = false
    }

    @Volatile
    private var kernelWakeLockApplied = false

    @Volatile
    private var rootAppliedFlag = false

    @Volatile
    private var rootDetailText = ""

    /**
     * 执行一条 root 命令，**绝不抛异常、绝不阻塞主流程**（独立 IO 协程内调用）。
     * 每条命令都记日志到 [ROOT_TAG]，便于按 tag 诊断。
     *
     * @return true 表示命令退出码为 0。
     */
    private fun runRoot(command: String): Boolean = runCatching {
        val process = ProcessBuilder("/system/bin/su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = runCatching { process.inputStream.bufferedReader().readText().trim() }
            .getOrDefault("")
        val finished = process.waitFor()
        Log.i(
            ROOT_TAG,
            "su -c [$command] → exit=$finished" + if (output.isNotBlank()) " out=${output.take(200)}" else "",
        )
        finished == 0
    }.getOrElse {
        Log.w(ROOT_TAG, "su -c [$command] 执行失败（忽略）：${it.message}")
        false
    }

    // ══════════════════════════════════════════════════════════════════════
    //  状态上报
    // ══════════════════════════════════════════════════════════════════════

    private fun refreshStatus() {
        _status.value = Status(
            armed = armed,
            serviceActive = AgentExecutionService.isServerKeepAliveActive,
            wakeLockHeld = wakeLock?.isHeld == true || kernelWakeLockApplied,
            watchdogActive = watchdogJob?.isActive == true,
            restartCount = restarts,
            gaveUp = gaveUp,
            lastError = LlamaServerProcess.lastFailureReason.takeIf { gaveUp },
            rootApplied = rootAppliedFlag,
            rootDetail = rootDetailText,
        )
    }

    /** 供 UI 主动刷新一次状态（页面进入时调用）。 */
    fun refresh() = refreshStatus()
}
