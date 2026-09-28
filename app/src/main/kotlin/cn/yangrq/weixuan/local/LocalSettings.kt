package cn.yangrq.weixuan.local

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地模型设置（使用独立 SharedPreferences，避免与 Eta 上游 Prefs/DataStore 相互影响）。
 *
 * 注意：这些键只服务「本地回环 Provider」这条路，云端 Provider 的配置完全不受影响。
 */
object LocalSettings {
    private const val FILE = "eta_local_model"

    const val KEY_ENABLED = "server_enabled"
    const val KEY_PORT = "server_port"
    const val KEY_MODEL_NAME = "model_name"
    const val KEY_MODEL_PRECISION = "model_precision"
    const val KEY_AUTO_LOAD = "auto_load_model"
    const val KEY_DRAFT_ENABLED = "speculative_draft_enabled"
    const val KEY_COMPUTE_UNIT = "compute_unit"
    const val KEY_THINKING = "thinking_enabled"
    const val KEY_SELF_BUILT = "self_built_engine"
    const val KEY_BMOE_ENGINE = "bmoe_moe_engine"
    const val KEY_QAIRT_BUNDLE = "qairt_bundle_enabled"
    const val KEY_GENIEX_LLAMA = "geniex_llama_enabled"
    const val KEY_QAIRT_REVERTED = "qairt_reverted_v1"
    const val KEY_CUSTOM_MODEL_PATH = "custom_model_path"
    const val KEY_CUSTOM_TOKENIZER_PATH = "custom_tokenizer_path"

    const val DEFAULT_PORT = 18787
    const val DEFAULT_MODEL_NAME = "Qwen/Qwen3-8B"
    const val DEFAULT_PRECISION = "Q4_0"
    const val COMPUTE_UNIT_HYBRID = "hybrid"
    const val COMPUTE_UNIT_NPU = "npu"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
        // 一次性回退（2026-09-28）：QAIRT/GenieX 路线在非系统 App 上依赖链过深且有原生
        // 崩溃风险（JNI GetIntField 类型错误 -> SIGABRT），已放弃。此处强制恢复自建
        // llama.cpp 引擎，避免用户此前测试残留的 QAIRT 选中状态在开机自动加载时再次崩溃。
        runCatching {
            val sp = prefs ?: return@runCatching
            if (!sp.getBoolean(KEY_QAIRT_REVERTED, false)) {
                sp.edit()
                    .putBoolean(KEY_QAIRT_BUNDLE, false)
                    .putBoolean(KEY_GENIEX_LLAMA, false)
                    .putBoolean(KEY_SELF_BUILT, true)
                    .putBoolean(KEY_QAIRT_REVERTED, true)
                    .apply()
            }
        }
    }

    private val p: SharedPreferences
        get() = prefs ?: error("LocalSettings 尚未初始化，请先调用 LocalSettings.init(context)")

    /** 回环服务开关（默认开启：本版本只有本地模型，无需用户手动打开）。 */
    var serverEnabled: Boolean
        get() = p.getBoolean(KEY_ENABLED, true)
        set(value) = p.edit().putBoolean(KEY_ENABLED, value).apply()

    var port: Int
        get() = p.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = p.edit().putInt(KEY_PORT, value).apply()

    var modelName: String
        get() = p.getString(KEY_MODEL_NAME, DEFAULT_MODEL_NAME) ?: DEFAULT_MODEL_NAME
        set(value) = p.edit().putString(KEY_MODEL_NAME, value).apply()

    var modelPrecision: String
        get() = p.getString(KEY_MODEL_PRECISION, DEFAULT_PRECISION) ?: DEFAULT_PRECISION
        set(value) = p.edit().putString(KEY_MODEL_PRECISION, value).apply()

    /** 服务启动后自动加载上一次使用的模型。 */
    var autoLoad: Boolean
        get() = p.getBoolean(KEY_AUTO_LOAD, true)
        set(value) = p.edit().putBoolean(KEY_AUTO_LOAD, value).apply()

    /** 是否尝试启用推测解码（draft 模型，需额外下载 Qwen3-0.6B）。 */
    var draftEnabled: Boolean
        get() = p.getBoolean(KEY_DRAFT_ENABLED, true)
        set(value) = p.edit().putBoolean(KEY_DRAFT_ENABLED, value).apply()

    /** hybrid(GPU+NPU) / npu / cpu。默认 npu：实测纯 NPU 比 hybrid 快 2 倍以上
     *（2026-09-25 同机同模型对比：prefill 254.7 vs 116.8 tok/s，decode 8.8 vs 3.3 tok/s，
     *  首字 14.4s vs 30.6s——hybrid 的跨设备张量拷贝开销是性能杀手）。 */
    var computeUnit: String
        get() = p.getString(KEY_COMPUTE_UNIT, "npu") ?: "npu"
        set(value) = p.edit().putString(KEY_COMPUTE_UNIT, value).apply()

    /** 思考模式：模型输出思考过程（流式透传到界面思考块）；默认开启。 */
    var thinkingEnabled: Boolean
        get() = p.getBoolean(KEY_THINKING, true)
        set(value) = p.edit().putBoolean(KEY_THINKING, value).apply()

    /**
     * 自建 llama.cpp runtime 开关（2026-09-25）：启用后用 llama-server 子进程替代 GenieX SDK 推理。
     * 默认关闭（重要教训）：mmap 并不减少推理的总内存需求——推理必然读入全部权重页，
     * 因此 14B（8.6GB 权重）在可用内存不足时依然会引发系统级内存压力。
     * 已加内存预检（权重全量 + KV + 1GB 余量）作为硬保护。
     */
    var useSelfBuiltEngine: Boolean
        get() = p.getBoolean(KEY_SELF_BUILT, true)
        set(value) = p.edit().putBoolean(KEY_SELF_BUILT, value).apply()

    /**
     * MoE 流式引擎（BigMoeOnEdge）：模型体积远超可用内存时启用（如 Qwen3-30B-A3B，
     * 13–18GB）。每 token 只从闪存读当前激活的专家 + 热专家缓存，无损，且不会因
     * page cache 膨胀触发 lowmemorykiller。开启后 Agent 与 UI 无需任何改动。
     */
    var useBmoeEngine: Boolean
        get() = p.getBoolean(KEY_BMOE_ENGINE, false)
        set(value) = p.edit().putBoolean(KEY_BMOE_ENGINE, value).apply()

    /**
     * QAIRT 预编译 bundle 模式（2026-09-27）：主模型选中 AI Hub 的 Qwen3-4B w4a16 时置真，
     * 表示走 GenieX 的 QAIRT（NPU-only）路径，而非自建 llama.cpp。
     */
    /** GenieX llama.cpp 模式（2026-09-28）：本地 GGUF 走 GenieX 的 llama.cpp 插件。 */
    var geniexLlamaEnabled: Boolean
        get() = p.getBoolean(KEY_GENIEX_LLAMA, false)
        set(value) = p.edit().putBoolean(KEY_GENIEX_LLAMA, value).apply()

    var qairtBundleEnabled: Boolean
        get() = p.getBoolean(KEY_QAIRT_BUNDLE, false)
        set(value) = p.edit().putBoolean(KEY_QAIRT_BUNDLE, value).apply()

    /** 自定义 .gguf 路径（与 GenieX 模型中心互斥，二者取一）。 */
    var customModelPath: String
        get() = p.getString(KEY_CUSTOM_MODEL_PATH, "") ?: ""
        set(value) = p.edit().putString(KEY_CUSTOM_MODEL_PATH, value).apply()

    /** 默认 GGUF 文件名：4B 在 NPU 上 1–2 秒响应，作为日常默认与失效兜底。 */
    const val DEFAULT_GGUF_NAME = "Qwen3-4B-Q4_K_M.gguf"

    var customTokenizerPath: String
        get() = p.getString(KEY_CUSTOM_TOKENIZER_PATH, "") ?: ""
        set(value) = p.edit().putString(KEY_CUSTOM_TOKENIZER_PATH, value).apply()

    const val KEY_DRAFT_MODEL_PATH = "draft_model_path"

    /** 推测解码草稿模型路径（同族小模型，如 DeepSeek-R1-Distill-Qwen-1.5B）。 */
    var draftModelPath: String
        get() = p.getString(KEY_DRAFT_MODEL_PATH, "") ?: ""
        set(value) = p.edit().putString(KEY_DRAFT_MODEL_PATH, value).apply()

    /** 导入的本地模型存放目录：App 专属外部目录（无权限要求，且是真实路径，GenieX 可直接加载）。 */
    fun modelsDir(context: android.content.Context): java.io.File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return java.io.File(base, "models").apply { if (!exists()) mkdirs() }
    }

    private const val KEY_DOWNLOADS = "catalog_download_ids"

    /** 记录「内置模型 id → DownloadManager 任务 id」，用于重启后继续显示进度。 */
    fun recordDownload(modelId: String, downloadId: Long) {
        p.edit().putLong("$KEY_DOWNLOADS.$modelId", downloadId).apply()
    }

    fun downloadId(modelId: String): Long? =
        p.getLong("$KEY_DOWNLOADS.$modelId", -1L).takeIf { it > 0 }

    fun clearDownload(modelId: String) {
        p.edit().remove("$KEY_DOWNLOADS.$modelId").apply()
    }

    /** 回环服务基地址，供 UI 展示与排障。 */
    fun baseUrl(): String = "http://127.0.0.1:$port/v1"
}
