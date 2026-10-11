package cn.yangrq.weixuan.local

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地图像生成引擎（GPU / Vulkan 后端）。
 *
 * 引擎：stable-diffusion.cpp（MIT，leejet/stable-diffusion.cpp）
 * 后端：Vulkan（Adreno 840）—— OpenCL 在普通 Android App 里因 linker
 *       命名空间限制访问不到 /vendor 的 libOpenCL.so，故不走 OpenCL。
 *
 * ## 内存仲裁（重要）
 * 本机 16GB，LLM（4B fp16 + KV）稳态约 3.4GB，SD1.5 fp16 约 1.7GB，
 * 二者**不能同时驻留**。出图前必须先停 LLM 服务，出图完成再恢复。
 * 见 docs/IMAGE_GENERATION.md。
 *
 * ## 线程
 * 所有 native 调用在 [scope] 的单线程上执行（sd.cpp 上下文非线程安全）。
 */
class ImageGenerationEngine(private val context: Context) {

    companion object {
        private const val TAG = "WeiXuanSD"

        /** 热闸门：出图前温度高于此值拒绝开始（与 LLM 热保护一致的保守值） */
        const val TEMP_GATE_C = 40f

        /** 模型目录（<filesDir>/models/sd/） */
        fun modelDir(context: Context): File = File(context.filesDir, "models/sd")

        /** 模型下载基址（HF 在国内不可达，默认走镜像；可在设置里改） */
        const val MODEL_BASE_URL = "https://hf-mirror.com"
    }

    enum class State { IDLE, PREPARING, READY, GENERATING, RELEASING }

    @Volatile
    var state: State = State.IDLE
        private set

    /** 出图前 LLM 是否在运行（恢复用） */
    @Volatile
    var llmWasRunning: Boolean = false
        private set

    private val initDone = AtomicBoolean(false)

    // ---- JNI ----------------------------------------------------------------
    private external fun nativeInit(): Boolean
    private external fun nativeVersion(): String
    private external fun nativeSystemInfo(): String
    private external fun nativeListDevices(): String
    private external fun nativeLoadModel(
        modelPath: String, vaePath: String?, nThreads: Int,
        backend: String, wtype: String, maxVramGiB: Float,
    ): Boolean

    external fun nativeFreeModel()
    private external fun nativeIsLoaded(): Boolean
    private external fun nativeProgressStep(): Int
    private external fun nativeProgressTotal(): Int
    private external fun nativeGenerate(
        prompt: String, negativePrompt: String?,
        width: Int, height: Int, steps: Int, cfgScale: Float,
        seed: Long, sampler: String, scheduler: String,
    ): ByteArray?

    init {
        synchronized(this) {
            if (!initDone.getAndSet(true)) {
                System.loadLibrary("weixuan_sd")
                if (!nativeInit()) {
                    Log.e(TAG, "nativeInit 失败")
                } else {
                    Log.i(TAG, "stable-diffusion.cpp ${nativeVersion()}")
                }
            }
        }
    }

    // ---- 诊断 ---------------------------------------------------------------
    fun systemInfo(): String = runCatching { nativeSystemInfo() }.getOrDefault("")
    fun listDevices(): String = runCatching { nativeListDevices() }.getOrDefault("")
    fun isModelLoaded(): Boolean = runCatching { nativeIsLoaded() }.getOrDefault(false)
    fun progressStep(): Int = runCatching { nativeProgressStep() }.getOrDefault(0)
    fun progressTotal(): Int = runCatching { nativeProgressTotal() }.getOrDefault(0)

    // ---- 内存仲裁 -----------------------------------------------------------

    /**
     * 准备出图环境：温度门控 → 停 LLM → 加载模型。
     * @return 成功 null；失败为原因（中文，可直接展示）
     */
    suspend fun prepare(
        modelFile: File,
        vaeFile: File? = null,
        nThreads: Int = 4,
        backend: String = "vulkan",
        wtype: String = "F16",
        maxVramGiB: Float = 0f,
    ): String? = runCatching {
        if (state != State.IDLE && state != State.READY) {
            return@runCatching "引擎正忙（${state}），请稍候"
        }

        // ① 温度门控（与 LLM 热保护同源；读取失败返回 0f → 放行）
        val temp = runCatching { LocalResourceGuard().maxTemperatureC() }.getOrDefault(0f)
        if (temp > TEMP_GATE_C) {
            return@runCatching "设备温度 ${"%.1f".format(temp)}°C 过高（>${TEMP_GATE_C.toInt()}°C），已拒绝出图"
        }

        // ② 内存仲裁：LLM 与 SD 不能共存
        state = State.PREPARING
        llmWasRunning = LlamaServerProcess.isRunning()
        if (llmWasRunning) {
            Log.i(TAG, "出图前停止 LLM 服务（内存仲裁）")
            LlamaServerProcess.stop()
            // 等进程退出 + 内存回收
            repeat(40) {
                if (!LlamaServerProcess.isRunning()) return@repeat
                kotlinx.coroutines.delay(100)
            }
            if (LlamaServerProcess.isRunning()) {
                state = State.IDLE
                return@runCatching "LLM 服务停止失败，内存不足"
            }
        }

        // ③ 加载模型
        if (!modelFile.exists()) {
            state = State.IDLE
            return@runCatching "模型文件不存在：${modelFile.absolutePath}"
        }
        val ok = nativeLoadModel(
            modelFile.absolutePath,
            vaeFile?.takeIf { it.exists() }?.absolutePath,
            nThreads, backend, wtype, maxVramGiB,
        )
        if (!ok) {
            state = State.IDLE
            return@runCatching "模型加载失败（详见日志 tag $TAG）"
        }

        state = State.READY
        Log.i(TAG, "图像模型就绪（LLM 曾运行=$llmWasRunning）")
        null
    }.getOrElse { "出图准备异常：${it.message}" }

    /** 当前进度 0..1（无总量信息时返回 -1） */
    fun progressFraction(): Float {
        val total = progressTotal()
        val step = progressStep()
        return if (total <= 0) -1f else step.toFloat() / total
    }

    // ---- 生成 ---------------------------------------------------------------

    data class GenConfig(
        val prompt: String,
        val negativePrompt: String? = null,
        val width: Int = 512,
        val height: Int = 512,
        val steps: Int = 20,
        val cfgScale: Float = 7f,
        val seed: Long = -1,
        val sampler: String = "euler_a",
        val scheduler: String = "karras",
    )

    /**
     * 文生图。返回 PNG 字节（可直接写文件/解码为 Bitmap），失败为 null。
     * 调用前必须 [prepare] 成功。
     */
    suspend fun generate(cfg: GenConfig): ByteArray? = runCatching {
        if (state != State.READY) {
            Log.e(TAG, "generate: 状态=${state}，未就绪")
            return@runCatching null
        }
        state = State.GENERATING
        try {
            val bytes = nativeGenerate(
                cfg.prompt, cfg.negativePrompt,
                cfg.width, cfg.height, cfg.steps, cfg.cfgScale,
                cfg.seed, cfg.sampler, cfg.scheduler,
            )
            bytes
        } finally {
            state = State.READY
        }
    }.getOrElse {
        Log.e(TAG, "generate 异常", it)
        state = State.READY
        null
    }

    /** 把 [generate] 的 RGBA 字节解码为 Bitmap（sd.cpp 返回 width*height*4） */
    fun rgbaToBitmap(rgba: ByteArray, width: Int, height: Int): Bitmap? = runCatching {
        // sd.cpp 的 sd_image_t.data 是 RGBA8888
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val ints = IntArray(width * height)
        var i = 0
        var p = 0
        while (i < ints.size) {
            val r = rgba[p].toInt() and 0xFF
            val g = rgba[p + 1].toInt() and 0xFF
            val b = rgba[p + 2].toInt() and 0xFF
            val a = rgba[p + 3].toInt() and 0xFF
            ints[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            i++; p += 4
        }
        bmp.setPixels(ints, 0, width, 0, 0, width, height)
        bmp
    }.getOrNull()

    // ---- 释放 ---------------------------------------------------------------

    /**
     * 卸载 SD 模型；[restoreLlama] 为 true 且出图前 LLM 在运行时恢复 LLM。
     *
     * 恢复是**同步**的：agent 的工具调用发生在两次 LLM 请求之间，
     * 工具返回前必须让 LLM 服务重新就绪，否则 agent 下一轮请求会失败。
     * （LLM 重载需数秒，调用方应在后台协程中调用本函数。）
     */
    suspend fun release(restoreLlama: Boolean = true) {
        if (state == State.IDLE) return
        state = State.RELEASING
        try {
            if (isModelLoaded()) nativeFreeModel()
        } finally {
            state = State.IDLE
        }
        if (restoreLlama && llmWasRunning) {
            llmWasRunning = false
            val modelPath = LlamaServerProcess.currentModelPath()
            val port = LlamaServerProcess.currentPort()
            if (modelPath != null && port > 0) {
                Log.i(TAG, "出图完成，恢复 LLM 服务：$modelPath :$port")
                // 等待 SD 权重完全释放再重启，避免峰值内存叠加
                System.gc()
                kotlinx.coroutines.delay(500)
                LlamaServerProcess.start(
                    context, modelPath, port,
                    LlamaServerProcess.activeContextSize,
                )
            } else {
                Log.w(TAG, "无法恢复 LLM：模型路径/端口不可读（model=$modelPath port=$port）")
            }
        }
    }
}
