package cn.yangrq.weixuan.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import cn.yangrq.weixuan.R
import cn.yangrq.weixuan.core.AndroidAgentLogger
import cn.yangrq.weixuan.core.safeLogType
import cn.yangrq.weixuan.local.LocalSettings
import cn.yangrq.weixuan.ui.MainActivity
import java.util.concurrent.atomic.AtomicLong

/** 只在用户任务存活期间持有前台执行生命周期；进程被系统停止后不重放任务。 */
internal class AgentExecutionService : Service() {
    private val stopQueue = ExecutionStopQueue { failure ->
        AndroidAgentLogger.warn("Execution task stop failed: type=${failure.safeLogType()}")
    }
    private val owner = ownerSequence.incrementAndGet()
    private var foregroundActive = false
    @Volatile private var startRejected = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        leases.attachOwner(owner)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.execution_channel), NotificationManager.IMPORTANCE_LOW),
        )
        ensureForeground()
    }

    private fun ensureForeground() {
        if (foregroundActive || startRejected) return
        leases.attachOwner(owner)
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            foregroundActive = true
        } catch (failure: RuntimeException) {
            startRejected = true
            AndroidAgentLogger.warn("Execution service foreground failed: type=${failure.safeLogType()}")
            stopTasks(startFailed = true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTasks()
            ACTION_SERVER_KEEPALIVE -> {
                serverKeepAlive = true
                ensureForeground()
                refreshNotification()
            }
            ACTION_SERVER_STOP -> {
                serverKeepAlive = false
                refreshNotification()
            }
            else -> {
                ensureForeground()
                refreshNotification()
            }
        }
        // 服务器保活模式下用 START_STICKY：进程被系统回收时尽量把服务带回来（引擎本身由
        // EtaApp 的自动加载 + LocalServerKeepAlive 看门狗负责重建）。其它情况保持原语义。
        return if (serverKeepAlive) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        // 销毁时同样收回本服务拥有的任务。回收在独立有界工作线程上完成，不阻塞 Main。
        stopQueue.close(leases.drainOwner(owner))
        super.onDestroy()
    }

    private fun stopTasks(startFailed: Boolean = false) {
        val callbacks = leases.drain(startFailed)
        stopQueue.submit(callbacks) {
            mainHandler.post { if (instance === this) refreshNotification() }
        }
    }

    private fun refreshNotification() {
        // 服务器保活模式（2026-10-05）：即使没有执行任务租约，也要保持前台存活——
        // 否则 onStartCommand 一进来就会「无租约 → 收起前台 → stopSelf」，保活形同虚设。
        if (!serverKeepAlive && leases.closeOwnerIfIdle(owner)) {
            foregroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (serverKeepAlive) {
            // 服务器模式常驻通知：文案明确「推理服务器 + 监听地址 + 数据不出设备」。
            val port = runCatching { LocalSettings.localServerPort }
                .getOrDefault(cn.yangrq.weixuan.config.LocalServerPrefs.DEFAULT_PORT)
            val host = if (runCatching { LocalSettings.localServerLan }.getOrDefault(false)) {
                "0.0.0.0"
            } else {
                "127.0.0.1"
            }
            val stop = PendingIntent.getService(
                this, 2, Intent(this, AgentExecutionService::class.java).setAction(ACTION_SERVER_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return builder
                .setContentTitle("微玄推理服务器运行中")
                .setContentText("$host:$port · 数据不出设备（本地私有 AI）")
                .addAction(
                    Notification.Action.Builder(null, "停止服务器保活", stop).build(),
                )
                .build()
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentExecutionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return builder
            .setContentTitle(getString(R.string.execution_title))
            .setContentText(getString(R.string.execution_summary, leases.count()))
            .addAction(Notification.Action.Builder(null, getString(R.string.execution_stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "eta_execution"
        private const val NOTIFICATION_ID = 1107
        private const val ACTION_STOP = "cn.yangrq.weixuan.action.STOP_USER_EXECUTION"

        /** 服务器保活模式的动作（由 [cn.yangrq.weixuan.local.LocalServerKeepAlive] 发出）。 */
        private const val ACTION_SERVER_KEEPALIVE = "cn.yangrq.weixuan.action.SERVER_KEEPALIVE"
        private const val ACTION_SERVER_STOP = "cn.yangrq.weixuan.action.SERVER_KEEPALIVE_STOP"

        /**
         * 是否处于「推理服务器保活」前台模式。**跨类静态**：进程被杀后清零（属预期——
         * 此时由 EtaApp 自动加载重新武装）。
         */
        @Volatile
        private var serverKeepAlive = false

        /** 服务器保活前台是否活跃（供 UI 展示「前台服务是否活跃」）。 */
        val isServerKeepAliveActive: Boolean
            get() = serverKeepAlive && instance != null

        private val leases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentExecutionService? = null

        /**
         * 进入「推理服务器保活」前台模式（幂等）。**调用方须自行保证服务器模式已开启**
         * （门控在 [cn.yangrq.weixuan.local.LocalServerKeepAlive.onEngineReady] 第一行）。
         * 失败返回 false，由调用方降级为「仅看门狗」。
         */
        fun acquireServerKeepAlive(context: Context): Boolean {
            if (instance?.startRejected == true) return false
            serverKeepAlive = true
            return try {
                context.applicationContext.startForegroundService(
                    Intent(context, AgentExecutionService::class.java).setAction(ACTION_SERVER_KEEPALIVE),
                )
                true
            } catch (failure: RuntimeException) {
                serverKeepAlive = false
                AndroidAgentLogger.warn("Server keepalive foreground start rejected: type=${failure.safeLogType()}")
                false
            }
        }

        /** 退出「推理服务器保活」前台模式：关闭静态标志并刷新通知（无租约时会自行收起前台）。 */
        fun releaseServerKeepAlive(context: Context) {
            serverKeepAlive = false
            val target = instance ?: return
            mainHandler.post { target.refreshNotification() }
        }

        /** 必须从有效的用户入口取得引用，再创建会话或子进程；失败时调用方不启动任务。 */
        fun acquire(
            context: Context,
            id: String,
            allowBoundFallback: Boolean = false,
            onStop: () -> Unit,
        ): Boolean {
            if (instance?.startRejected == true) return false
            if (!leases.acquire(id, allowBoundFallback, onStop)) return true
            return try {
                context.applicationContext.startForegroundService(Intent(context, AgentExecutionService::class.java))
                true
            } catch (failure: RuntimeException) {
                leases.release(id)
                AndroidAgentLogger.warn("Execution service start rejected: type=${failure.safeLogType()}")
                false
            }
        }

        fun release(id: String) {
            leases.release(id)
            mainHandler.post { instance?.refreshNotification() }
        }
    }
}
