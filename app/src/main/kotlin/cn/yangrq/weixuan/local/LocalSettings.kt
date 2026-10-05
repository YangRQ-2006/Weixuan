package cn.yangrq.weixuan.local

import android.content.Context
import android.content.SharedPreferences
import cn.yangrq.weixuan.config.LocalServerPrefs

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
    const val KEY_LOCAL_BACKEND = "local_backend"
    const val KEY_THINKING = "thinking_enabled"
    const val KEY_SELF_BUILT = "self_built_engine"
    const val KEY_QAIRT_BUNDLE = "qairt_bundle_enabled"
    const val KEY_GENIEX_LLAMA = "geniex_llama_enabled"
    const val KEY_QAIRT_REVERTED = "qairt_reverted_v2"
    const val KEY_CUSTOM_MODEL_PATH = "custom_model_path"
    const val KEY_CUSTOM_TOKENIZER_PATH = "custom_tokenizer_path"

    const val DEFAULT_PORT = 18787
    const val DEFAULT_MODEL_NAME = "Qwen/Qwen3-8B"
    const val DEFAULT_PRECISION = "Q4_0"
    const val COMPUTE_UNIT_HYBRID = "hybrid"
    const val COMPUTE_UNIT_NPU = "npu"

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * 仅为解析「模型目录」而保留的 applicationContext（不持 Activity，无泄漏风险）。
     * 2026-09-30 新增：让 [customModelPath] 的 getter 能做「内部存储优先」重定向，
     * 从而让**所有**加载入口（GenieXLocalEngine / EtaApp 自动加载）一次性受益，
     * 无需改调用点。
     */
    @Volatile
    private var appCtx: Context? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
        // 一次性回退（2026-09-28）：QAIRT/GenieX 路线在非系统 App 上依赖链过深且有原生
        // 崩溃风险（JNI GetIntField 类型错误 -> SIGABRT），已放弃。此处强制恢复自建
        // llama.cpp 引擎，避免用户此前测试残留的 QAIRT 选中状态在开机自动加载时再次崩溃。
        runCatching {
            val sp = prefs ?: return@runCatching
            if (!sp.getBoolean(KEY_QAIRT_REVERTED, false)) {
                // ① 清掉 QAIRT/GenieX 选中状态，恢复自建引擎
                sp.edit()
                    .putBoolean(KEY_QAIRT_BUNDLE, false)
                    .putBoolean(KEY_GENIEX_LLAMA, false)
                    .putBoolean(KEY_SELF_BUILT, true)
                    .putBoolean(KEY_QAIRT_REVERTED, true)
                    .apply()
                // ② 修正被 QAIRT 试验改坏的模型路径：测试期曾把 customModelPath 指向
                //    bundle 内的 genie_config.json（该 bundle 已随试验数据删除）。
                //    若路径已失效或指向 bundle 配置，回退为已下载的 4B GGUF。
                val cmp = sp.getString(KEY_CUSTOM_MODEL_PATH, "").orEmpty()
                val invalid = cmp.isNotBlank() && (
                    cmp.endsWith("genie_config.json") ||
                        cmp.contains("qwen3_4b-genie") ||
                        !java.io.File(cmp).exists()
                    )
                if (invalid) {
                    val dir = java.io.File(
                        context.getExternalFilesDir(null) ?: context.filesDir,
                        "models",
                    )
                    val fallback = java.io.File(dir, DEFAULT_GGUF_NAME)
                    val target = when {
                        fallback.isFile -> fallback.absolutePath
                        else -> dir.listFiles()?.firstOrNull {
                            it.isFile && it.name.endsWith(".gguf") && it.length() > 0
                        }?.absolutePath.orEmpty()
                    }
                    sp.edit().putString(KEY_CUSTOM_MODEL_PATH, target).apply()
                }
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

    // ── 本地推理服务器模式（2026-10-05）───────────────────────────────────────
    // 键名与默认值**集中定义**在 [cn.yangrq.weixuan.config.LocalServerPrefs]（见那里的注释）。
    // 默认值刻意等于 [LlamaServerProcess] 改造前写死的参数（127.0.0.1 / 18787 / -np 1 / 无 key），
    // 因此**不开启服务器模式时行为与旧版逐字节一致**；服务器模式是 opt-in（默认关）。

    /** 服务器模式总开关。**默认 false**：不开启则 llama-server 按旧参数启动。 */
    var localServerEnabled: Boolean
        get() = p.getBoolean(LocalServerPrefs.KEY_ENABLED, LocalServerPrefs.DEFAULT_ENABLED)
        set(value) = p.edit().putBoolean(LocalServerPrefs.KEY_ENABLED, value).apply()

    /** false=仅本机 127.0.0.1（默认）；true=绑 0.0.0.0（同 WiFi 可见）。 */
    var localServerLan: Boolean
        get() = p.getBoolean(LocalServerPrefs.KEY_LAN, LocalServerPrefs.DEFAULT_LAN)
        set(value) = p.edit().putBoolean(LocalServerPrefs.KEY_LAN, value).apply()

    /** 监听端口，默认 18787。越界值回落到默认端口，避免非法端口把子进程打挂。 */
    var localServerPort: Int
        get() = p.getInt(LocalServerPrefs.KEY_PORT, LocalServerPrefs.DEFAULT_PORT)
            .takeIf { it in LocalServerPrefs.MIN_PORT..LocalServerPrefs.MAX_PORT }
            ?: LocalServerPrefs.DEFAULT_PORT
        set(value) = p.edit().putInt(
            LocalServerPrefs.KEY_PORT,
            value.coerceIn(LocalServerPrefs.MIN_PORT, LocalServerPrefs.MAX_PORT),
        ).apply()

    /** 并发槽数（llama.cpp `-np`），默认 1，范围 1..4。 */
    var localServerSlots: Int
        get() = p.getInt(LocalServerPrefs.KEY_SLOTS, LocalServerPrefs.DEFAULT_SLOTS)
            .coerceIn(LocalServerPrefs.MIN_SLOTS, LocalServerPrefs.MAX_SLOTS)
        set(value) = p.edit().putInt(
            LocalServerPrefs.KEY_SLOTS,
            value.coerceIn(LocalServerPrefs.MIN_SLOTS, LocalServerPrefs.MAX_SLOTS),
        ).apply()

    /** API Key，默认空。非空时 [LlamaServerProcess] 会追加 `--api-key <key>`。 */
    var localServerApiKey: String
        get() = p.getString(LocalServerPrefs.KEY_API_KEY, LocalServerPrefs.DEFAULT_API_KEY).orEmpty()
        set(value) = p.edit().putString(LocalServerPrefs.KEY_API_KEY, value).apply()

    /** 期望绑定的 host：局域网开 => 0.0.0.0，否则 127.0.0.1。仅在服务器模式开启时被采用。 */
    fun localServerHost(): String = if (localServerLan) "0.0.0.0" else "127.0.0.1"

    /** 生成一个 32 位十六进制随机 API Key 并保存（供「一键生成」与「开局域网自动兜底」）。 */
    fun generateLocalServerApiKey(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        val key = bytes.joinToString("") { "%02x".format(it) }
        localServerApiKey = key
        return key
    }

    /**
     * 取「可用」的 API Key：**局域网开启且当前为空时自动生成一个并保存**。
     *
     * 这是安全兜底——局域网裸奔等于同 WiFi 下任何人都能白嫖你的手机算力/烧你的电，
     * 所以只要绑定到 0.0.0.0 就必须有一个 key。仅本机（或已有 key）时原样返回。
     */
    fun ensureLocalServerApiKey(): String {
        val existing = localServerApiKey
        if (!localServerLan) return existing
        if (existing.isNotBlank()) return existing
        return generateLocalServerApiKey()
    }

    /**
     * 服务器对外发布的 base_url 提示（本机回环）。用于设置页展示「该填什么 base_url」。
     * 注意端口用服务器模式端口（[localServerPort]），而非 GenieX 路径的 [port]。
     */
    fun localServerLocalBaseUrl(): String = "http://127.0.0.1:$localServerPort/v1"

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

    /**
     * 自建 runtime 的后端口味（2026-10-02 新增）。
     *
     * 只影响 [LlamaServerProcess]（自建 llama.cpp 引擎）；GenieX 路线仍由 [computeUnit] 决定。
     * 之所以必须显式选择而不是「让它自己挑」：llama.cpp 未给 `--device` 时会把注册表里
     * 所有 GPU 型设备（本项目 nativeLibraryDir 里同时有 Vulkan 与 Hexagon）一起纳入并按显存
     * 逐层切分 → 跨设备张量拷贝把 NPU 的收益吃光。详见 [LocalBackend] 的类注释。
     */
    var localBackend: String
        get() = p.getString(KEY_LOCAL_BACKEND, LocalBackend.DEFAULT.id) ?: LocalBackend.DEFAULT.id
        set(value) = p.edit().putString(KEY_LOCAL_BACKEND, value).apply()

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

    /**
     * 自定义 .gguf 路径（与 GenieX 模型中心互斥，二者取一）。
     *
     * **内部存储优先（2026-09-30）**：若内部目录 `filesDir/models` 里存在同名模型，读取时
     * 直接返回内部路径。之所以把重定向放在 getter 里——所有加载入口
     * （`GenieXLocalEngine`、`EtaApp` 自动加载兜底）都读这个属性，
     * 一处改动即可全覆盖，避免漏掉某个调用点造成「有的走 f2fs、有的还在 FUSE」。
     * 附带好处：用户偏好里即使仍存着旧的 `/storage/emulated/...` 路径也不会失效。
     */
    var customModelPath: String
        get() {
            val raw = p.getString(KEY_CUSTOM_MODEL_PATH, "") ?: ""
            if (raw.isEmpty()) return raw
            val ctx = appCtx ?: return raw
            return runCatching {
                val name = java.io.File(raw).name
                if (name.isEmpty()) return@runCatching raw
                val internal = java.io.File(internalModelsDir(ctx), name)
                if (internal.isFile && internal.length() > 0) internal.absolutePath else raw
            }.getOrDefault(raw)
        }
        set(value) = p.edit().putString(KEY_CUSTOM_MODEL_PATH, value).apply()

    /** 默认 GGUF 文件名：4B 在 NPU 上 1–2 秒响应，作为日常默认与失效兜底。 */
    const val DEFAULT_GGUF_NAME = "Spark-X2.5-4B-Q4_K_M.gguf"

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

    /**
     * 模型内部存储目录（`filesDir/models`）——**原生 f2fs，非 FUSE**。
     *
     * 这是让 llama.cpp 的 `O_DIRECT` 真正生效的唯一可靠位置：外部目录
     * `/storage/emulated/...` 是 FUSE，引擎在该路径上 O_DIRECT 打开成功但读到错误数据，
     * 只能退回 buffered I/O → 每 token 数百次大缺页（官方基准 314~1894 vs 6~10）。
     * 注意：本函数**不创建目录**（会被 [customModelPath] 的 getter 调用，不能有副作用）。
     */
    fun internalModelsDir(context: android.content.Context): java.io.File =
        java.io.File(context.filesDir, "models")

    /**
     * Context-free 便捷入口：用 [init] 时缓存的 `appCtx`。
     *
     * 给拿不到 Context 的调用点用（例如 `AgentModelRetry` 的首启引导检查）。
     * 尚未初始化时返回 null —— 调用方应把 null 当作"不确定"而不是"没有模型"。
     */
    fun internalModelsDirOrNull(): java.io.File? = appCtx?.let { internalModelsDir(it) }

    /**
     * 把外部目录（FUSE）里的 .gguf 迁到内部存储，使 O_DIRECT 生效。幂等、可失败即返回。
     *
     * 关键细节：源路径必须用 `/data/media/0/...` 别名，**不能**用 `/storage/emulated/0/...`。
     * 两者指向同一份文件，但后者要过 FUSE 守护进程——那样 rename 会退化成「跨设备拷贝」
     * （十几 GB 要拷几分钟且可能被中断）；前者与 `/data/user` 同属 /data 分区，rename 是
     * **瞬时**的。迁完必须 chown/chmod，否则文件仍是 media_rw 属主，App 读不到。
     * 无 root 时静默跳过（退回 FUSE，功能不受影响，只是慢）。
     *
     * @return 成功迁走的文件数
     */
    fun migrateModelsToInternal(context: android.content.Context): Int {
        val app = context.applicationContext
        val extDir = modelsDir(app)
        val intDir = internalModelsDir(app).apply { if (!exists()) mkdirs() }
        val pending = extDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) && it.length() > 0L }
            .filter { !java.io.File(intDir, it.name).exists() }
        if (pending.isEmpty()) return 0
        val extBase = extDir.absolutePath
        val mediaBase = extBase.replaceFirst("^/storage/emulated/0/".toRegex(), "/data/media/0/")
        if (mediaBase == extBase) return 0   // 不在预期位置（可能已回落到 filesDir）→ 不动
        val uid = android.os.Process.myUid()
        var moved = 0
        for (f in pending) {
            val src = "$mediaBase/${f.name}"
            val dst = "${intDir.absolutePath}/${f.name}"
            val shell = "mv -f '$src' '$dst' 2>/dev/null && chown $uid:$uid '$dst' && chmod 600 '$dst'"
            val ok = runCatching {
                ProcessBuilder("/system/bin/su", "-c", shell)
                    .redirectErrorStream(true).start().waitFor() == 0
            }.getOrDefault(false)
            if (!ok) break
            val moved0 = java.io.File(dst)
            if (moved0.isFile && moved0.length() > 0) moved++ else break
        }
        return moved
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
