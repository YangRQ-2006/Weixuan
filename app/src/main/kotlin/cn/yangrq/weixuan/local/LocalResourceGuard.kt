package cn.yangrq.weixuan.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 本地推理资源守护（移植自微玄 NPU Agent 优化套件的 ResourceGuard）。
 *
 * 目标：端侧大模型推理不卡死手机、不触发 Android LMK 杀进程。
 * 三路感知：内存水位 / SoC 温控（排除 trip-point 假温度）/ 电量，
 * 产出速度系数、自适应 KV/上下文预算、批次大小与最大推理时长。
 */
class LocalResourceGuard {

    companion object {
        const val SAFE_MARGIN = 500
        const val WARNING_LEVEL = 800
        const val CRITICAL_LEVEL = 300
        const val EMERGENCY_LEVEL = 150

        const val TEMP_COOL = 40.0f
        const val TEMP_WARM = 50.0f
        const val TEMP_HOT = 60.0f
        const val TEMP_CRITICAL = 70.0f
        const val SPEED_COOL = 1.0f
        const val SPEED_WARM = 0.7f
        const val SPEED_HOT = 0.4f

        const val BATTERY_LOW = 20
        const val BATTERY_CRITICAL = 10

        const val KV_CACHE_MAX_MB = 2048
        const val KV_CACHE_MIN_MB = 128

        /** 每 token 的 KV Cache 估算（MB），8B 保守取 0.5。 */
        const val KV_MB_PER_TOKEN = 0.5f

        const val MONITOR_INTERVAL_MS = 5000L

        private val WHITESPACE = Regex("\\s+")
    }

    @Volatile
    var onEmergency: ((String) -> Unit)? = null

    @Volatile
    private var lastEmergencyAt = 0L

    // -------------------------------------------------- 内存守卫

    /** 读取 /proc/meminfo，返回 (MemAvailable MB, MemTotal MB)。 */
    fun checkMemory(): Pair<Int, Int> {
        var availMb = 0
        var totalMb = 0
        try {
            File("/proc/meminfo").forEachLine { line ->
                when {
                    line.startsWith("MemAvailable:") ->
                        availMb = line.split(WHITESPACE).getOrNull(1)?.toIntOrNull()?.div(1024) ?: 0
                    line.startsWith("MemTotal:") ->
                        totalMb = line.split(WHITESPACE).getOrNull(1)?.toIntOrNull()?.div(1024) ?: 0
                }
            }
        } catch (_: Exception) {
        }
        return availMb to totalMb
    }

    fun memAvailableMb(): Int = checkMemory().first

    /** 加载后内存审计底线：低于此余量判定不可持续，自动卸载防卡死。 */
    val POST_LOAD_MIN_AVAIL_MB = 800

    /** 绝对内存底线：任何加载前可用内存低于此值直接拒绝。 */
    val PRE_LOAD_MIN_AVAIL_MB = 2048

    /**
     * 加载前预检（2026-09-25 低内存大模型模式）：
     * - 小模型（<1GB）：全量+余量；
     * - 大模型（≥1GB）：mmap 驻留估算（权重按需分页 ≈40% 驻留）+ KV 预算 + 系统余量。
     *   估算仅为预检，真正的保护是 [postLoadAudit] 实测审计（不足即自动卸载）。
     */
    fun canLoadModel(modelMb: Int): Boolean {
        val avail = memAvailableMb()
        val need = if (modelMb < 1024) {
            modelMb * 6 / 5 + 1024
        } else {
            (modelMb * 2 / 5 + 2048 + 1024).toInt()
        }
        return avail > need && avail > PRE_LOAD_MIN_AVAIL_MB
    }

    /** 加载完成后实测审计：返回 null=通过；返回错误消息=余量不足应卸载。 */
    fun postLoadAudit(): String? {
        val avail = memAvailableMb()
        return if (avail < POST_LOAD_MIN_AVAIL_MB) {
            "加载后内存余量不足（实测可用 ${avail}MB < ${POST_LOAD_MIN_AVAIL_MB}MB），已自动卸载防卡死"
        } else {
            null
        }
    }

    /** 当前设备热状态码（PowerManager.THERMAL_STATUS_*，0=NONE）。 */
    fun thermalStatus(context: android.content.Context): Int = runCatching {
        context.getSystemService(android.os.PowerManager::class.java)
            ?.currentThermalStatus ?: android.os.PowerManager.THERMAL_STATUS_NONE
    }.getOrDefault(android.os.PowerManager.THERMAL_STATUS_NONE)

    /** 真实电池温度（摄氏度）。MIUI 充电场景 thermalStatus 常年虚报 SEVERE/CRITICAL
     *  （预节流信号，非真实温度，2026-09-25 实证：手机温热却报"过热"），故以实测温度为准。 */
    fun batteryTempC(context: android.content.Context): Int = runCatching {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val tenth = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        tenth / 10
    }.getOrDefault(0)

    /**
     * 温度安全边界（分级，风险匹配负载）：
     * - 小模型（<1GB，加载负载极小）：电池 ≤45°C 放行；
     * - 大模型（≥1GB，大块内存分配+满载计算）：严格执行 ≤42°C；
     * - 热状态 ≥ EMERGENCY 或电池 >45°C：一律拒绝。
     */
    fun isThermalOk(context: android.content.Context, modelMb: Int = 0): Boolean {
        val tempC = batteryTempC(context)
        val status = thermalStatus(context)
        if (status >= android.os.PowerManager.THERMAL_STATUS_EMERGENCY) return false
        if (tempC <= 0) return true // 温度读取失败不据此拦截
        val limitC = if (modelMb in 1 until 1024) 45 else 42
        return tempC <= limitC
    }

    /** 动态 KV Cache 预算：min((可用-安全水位)/2, 2048MB)，下限 128MB。 */
    fun optimalKvCacheMb(): Int {
        val available = memAvailableMb() - SAFE_MARGIN
        return (available / 2).coerceIn(KV_CACHE_MIN_MB, KV_CACHE_MAX_MB)
    }

    /** 自适应上下文窗口：KV 预算 / 每 token 开销，clamp [512, 8192]。 */
    /**
     * 上下文窗口按【当前模型】的 KV 密度计算（2026-09-25 事故：prompt 可达 8k tokens
     * （系统提示+31 工具定义），旧窗口 4070 装不下 → llama.cpp 陷入 context-shifting
     * 死循环、零输出）。小模型 KV 密度低，可开大窗口；大模型收紧。
     */
    fun optimalContextWindow(modelMb: Int = 0): Int {
        val kvMbPerToken = when {
            modelMb <= 1024 -> 0.12f  // 0.6B 级
            modelMb <= 5000 -> 0.45f  // 8B 级
            else -> 0.6f              // 14B+
        }
        val tokens = (optimalKvCacheMb() / kvMbPerToken).toInt()
        return tokens.coerceIn(2048, 16384)
    }

    // -------------------------------------------------- 温控管理

    /** 读取真实温度传感器（排除 trip-point 阈值与无效读数）。 */
    fun temperatures(): Map<String, Float> {
        val temps = hashMapOf("cpu" to 0f, "gpu" to 0f, "battery" to 0f, "npu" to 0f)
        try {
            val zones = File("/sys/class/thermal").listFiles() ?: emptyArray()
            for (zone in zones) {
                if (!zone.name.startsWith("thermal_zone")) continue
                try {
                    val type = File(zone, "type").readText().trim().lowercase()
                    if (type.contains("trip") || type.contains("hw-trip")) continue
                    val raw = File(zone, "temp").readText().trim().toIntOrNull() ?: continue
                    val temp = raw / 1000f
                    if (temp < -100f || temp > 150f) continue
                    val key = when {
                        type.contains("cpu") || type.contains("cpullc") -> "cpu"
                        type.contains("gpu") || type.contains("gpuss") -> "gpu"
                        type.contains("battery") || type.contains("batt") -> "battery"
                        type.contains("dsp") || type.contains("npu") || type.contains("hexagon") ||
                            type.contains("nsphvx") || type.contains("nsphmx") || type.contains("qmx") -> "npu"
                        else -> continue
                    }
                    temps[key] = maxOf(temps.getValue(key), temp)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return temps
    }

    fun maxTemperatureC(): Float = temperatures().values.maxOrNull() ?: 0f

    /** 温控速度系数：1.0 / 0.7 / 0.4 / 0.0(暂停)。 */
    fun speedFactor(): Float {
        val t = maxTemperatureC()
        return when {
            t >= TEMP_CRITICAL -> 0f
            t >= TEMP_HOT -> SPEED_HOT
            t >= TEMP_WARM -> SPEED_WARM
            else -> SPEED_COOL
        }
    }

    /** 温控暂停已按用户要求废除（2026-09-25）：不再因温度拒绝/暂停推理。 */
    fun shouldPause(): Boolean = false

    /** 自适应批次大小：512/256/128/64。 */
    fun optimalBatchSize(): Int = when {
        speedFactor() >= 1.0f -> 512
        speedFactor() >= 0.7f -> 256
        speedFactor() >= 0.4f -> 128
        else -> 64
    }

    // -------------------------------------------------- 功耗管理

    fun batteryStatus(): Pair<Int, Boolean> {
        return try {
            val pct = File("/sys/class/power_supply/battery/capacity").readText().trim().toIntOrNull() ?: 100
            val status = File("/sys/class/power_supply/battery/status").readText().trim().lowercase()
            pct to (status == "charging")
        } catch (_: Exception) {
            100 to true
        }
    }

    /** 电量感知的最大推理时长（秒）：充电中不限。 */
    fun maxInferenceSec(): Long {
        val (pct, charging) = batteryStatus()
        return when {
            charging -> 3600
            pct > 50 -> 600
            pct > 20 -> 180
            else -> 60
        }
    }

    // -------------------------------------------------- 综合判定

    fun preInferenceCheck(): LocalInferenceConfig {
        val (availMb, _) = checkMemory()
        val factor = speedFactor()
        val (pct, _) = batteryStatus()

        val reasons = mutableListOf<String>()
        if (availMb <= CRITICAL_LEVEL) reasons.add("内存不足 (${availMb}MB)")
        if (pct <= BATTERY_CRITICAL) reasons.add("电量极低")

        val canInfer = availMb > CRITICAL_LEVEL && pct > BATTERY_CRITICAL
        val reason = if (canInfer) {
            "温度:${maxTemperatureC()}°C, 内存:${availMb}MB, 电量:${pct}%"
        } else {
            reasons.joinToString("; ")
        }

        return LocalInferenceConfig(
            canInfer = canInfer,
            maxTokens = (1024 * factor).toInt().coerceAtLeast(64),
            batchSize = optimalBatchSize(),
            contextWindow = optimalContextWindow(),
            kvCacheMb = optimalKvCacheMb(),
            speedFactor = factor,
            maxInferenceSec = maxInferenceSec(),
            memAvailableMb = availMb,
            maxTempC = maxTemperatureC(),
            batteryPct = pct,
            reason = reason,
        )
    }

    /** 紧急释放：触发回调（上层清 KV Cache），60s 冷却防风暴。 */
    fun triggerEmergency(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastEmergencyAt < 60_000) return
        lastEmergencyAt = now
        try {
            onEmergency?.invoke(reason)
        } catch (_: Exception) {
        }
    }

    fun startMonitoring(scope: CoroutineScope, onState: ((LocalInferenceConfig) -> Unit)? = null): Job {
        return scope.launch {
            while (isActive) {
                val cfg = preInferenceCheck()
                if (cfg.memAvailableMb <= EMERGENCY_LEVEL) {
                    triggerEmergency("内存紧急水位 (${cfg.memAvailableMb}MB)")
                }
                onState?.invoke(cfg)
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }
}

/** 自适应推理配置快照。 */
data class LocalInferenceConfig(
    val canInfer: Boolean,
    val maxTokens: Int,
    val batchSize: Int,
    val contextWindow: Int,
    val kvCacheMb: Int,
    val speedFactor: Float,
    val maxInferenceSec: Long,
    val memAvailableMb: Int,
    val maxTempC: Float,
    val batteryPct: Int,
    val reason: String,
)
