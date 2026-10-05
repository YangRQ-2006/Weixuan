package cn.yangrq.weixuan.local

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 可一键下载的内置模型（GGUF）——「模型市场」数据源。
 *
 * 全部落在 App 专属外部目录 `getExternalFilesDir()/models`（无需存储权限，且是真实文件路径，
 * 可直接交给 GenieX 加载；推理与数据全部留在端侧）。
 *
 * 下载走系统 [DownloadManager]：由系统负责后台保活、断点续传、通知栏进度与失败重试——
 * 大模型（10~20GB）用 App 内自建线程下载极易被后台限制/杀进程，这是实测「下载不动」的主因之一。
 *
 * 条目文件名与体积均经 hf-mirror API 实测核实（`/api/models/<repo>/tree/main`）。
 */
data class LocalCatalogModel(
    val id: String,
    val title: String,
    val summary: String,
    val fileName: String,
    val sizeMb: Long,
    val urls: List<String>,
    val tier: ModelTier = ModelTier.MAIN,
    /**
     * 多模态视觉塔（**伴生文件**，2026-10-04 新增）。
     *
     * 视觉模型（Qwen3-VL / Gemma-3 等）需要两个文件：语言模型 + CLIP 视觉塔。
     * 运行时靠 `LlamaServerProcess` 的 stem() 规则配对 ——
     * 把两侧文件名的量化后缀（`-Q4_K_M` / `-Q8_0` / `-F16` …）去掉后互相前缀匹配即视为一对。
     * 所以伴生文件名必须与主模型名同源（例：`Qwen3VL-4B-Instruct-Q4_K_M.gguf`
     * ↔ `mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf`）。**多个 VL 模型可共存，不会串配。**
     */
    val companionFileName: String? = null,
    val companionSizeMb: Long = 0,
    val companionUrls: List<String> = emptyList(),
    /** 该模型是否具备视觉能力（决定市场页是否打「多模态」标）。 */
    val multimodal: Boolean = false,
) {
    /** 记录用：以 MB 表示的目标体积。 */
    val sizeBytes: Long get() = sizeMb * 1024L * 1024L

    /** 主模型 + 伴生文件的合计体积（MB）。 */
    val totalSizeMb: Long get() = sizeMb + companionSizeMb
}

/** 模型市场分层（轻量 / 主力 / 高阶）。 */
enum class ModelTier(val label: String) {
    /** **3B 以下**：响应最快、内存占用最小，适合快速问答与轻量任务。 */
    DRAFT("轻量级 · 3B 以下"),

    /** **3B ~ 4B**：日常系统级 Agent 的推荐区间，速度与工具调用能力平衡。 */
    MAIN("主力 · 3B ~ 4B"),

    /**
     * **4B 以上**：复杂多步任务、质量优先。
     *
     * 需要约 6GB 可用内存，16GB 机型建议先清理后台再加载。
     */
    FLAGSHIP("高阶 · 4B 以上"),
}

data class ModelDownloadStatus(
    val state: DownloadState,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val reason: Int = 0,
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

enum class DownloadState { NONE, PENDING, RUNNING, PAUSED, SUCCEEDED, FAILED }

object LocalModelCatalog {

    private const val TAG = "EtaLocalModelCatalog"
    private const val MODEL_DIR = "models"

    private const val MS = "https://modelscope.cn/models/unsloth"
    private const val HF = "https://hf-mirror.com/unsloth"

    /** hf-mirror 根（实测可从国内/沙箱直连，huggingface.co 直连会超时）。 */
    private const val HF_BASE = "https://hf-mirror.com"

    private fun links(repo: String, file: String): List<String> = listOf(
        "$HF/$repo/resolve/main/$file",
        "$MS/$repo/resolve/master/$file",
    )

    /**
     * 第三方作者（非 unsloth）的 GGUF 直链。
     *
     * 注意：`enqueue` 只用 `urls.first()`，所以第三方源必须把 hf-mirror 排第一。
     * 现用于 Qwen2.5 系列 / InternLM2.5（bartowski 仓库）。
     */
    private fun linksFrom(owner: String, repo: String, file: String): List<String> =
        listOf("$HF_BASE/$owner/$repo/resolve/main/$file")

    /** 体积为 hf-mirror API 实测字节数换算。 */
    val MODELS: List<LocalCatalogModel> = listOf(
        // ══ 轻量级 · 3B 以下 ═════════════════════════════════════════════
        LocalCatalogModel(
            id = "qwen3-0.6b-q4km",
            title = "Qwen3-0.6B · Q4_K_M（最小体积）",
            summary = "378MB｜极轻量思考模型；响应最快、内存占用最小，适合快速问答",
            fileName = "Qwen3-0.6B-Q4_K_M.gguf",
            sizeMb = 378,
            urls = links("Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_K_M.gguf"),
            tier = ModelTier.DRAFT,
        ),
        LocalCatalogModel(
            id = "gemma-3-1b-it-q4km",
            title = "Gemma-3-1B-it · Q4_K_M",
            summary = "768MB｜Google 1B 指令模型；体积小、中英文基础问答够用",
            fileName = "gemma-3-1b-it-Q4_K_M.gguf",
            sizeMb = 768,
            urls = links("gemma-3-1b-it-GGUF", "gemma-3-1b-it-Q4_K_M.gguf"),
            tier = ModelTier.DRAFT,
        ),
        LocalCatalogModel(
            id = "qwen2.5-1.5b-instruct-q4km",
            title = "Qwen2.5-1.5B-Instruct · Q4_K_M",
            summary = "940MB｜通义千问 1.5B；中文对话流畅，轻量档里中文最强的一档",
            fileName = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            sizeMb = 940,
            urls = linksFrom("bartowski", "Qwen2.5-1.5B-Instruct-GGUF", "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"),
            tier = ModelTier.DRAFT,
        ),
        LocalCatalogModel(
            id = "qwen3-1.7b-q4km",
            title = "Qwen3-1.7B · Q4_K_M",
            summary = "1.06GB｜1.7B 思考型小模型；轻量对话，速度与可用性兼顾",
            fileName = "Qwen3-1.7B-Q4_K_M.gguf",
            sizeMb = 1056,
            urls = links("Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf"),
            tier = ModelTier.DRAFT,
        ),
        LocalCatalogModel(
            id = "deepseek-r1-1.5b-q4km",
            title = "DeepSeek-R1-Distill-Qwen-1.5B · Q4_K_M",
            summary = "1.07GB｜1.5B 推理蒸馏；边想边答，适合轻量推理类问题",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            sizeMb = 1066,
            urls = links("DeepSeek-R1-Distill-Qwen-1.5B-GGUF", "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"),
            tier = ModelTier.DRAFT,
        ),
        // ══ 主力 · 3B ~ 4B（日常 Agent 的推荐区间） ══════════════════════
        LocalCatalogModel(
            id = "qwen2.5-3b-instruct-q4km",
            title = "Qwen2.5-3B-Instruct · Q4_K_M",
            summary = "1.84GB｜通义千问 3B；主力档里最省内存，中文与指令跟随都稳",
            fileName = "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            sizeMb = 1840,
            urls = linksFrom("bartowski", "Qwen2.5-3B-Instruct-GGUF", "Qwen2.5-3B-Instruct-Q4_K_M.gguf"),
            tier = ModelTier.MAIN,
        ),
        LocalCatalogModel(
            id = "llama-3.2-3b-q4km",
            title = "Llama-3.2-3B-Instruct · Q4_K_M",
            summary = "1.93GB｜Meta 3B 指令小模型；英文轻量通用",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeMb = 1926,
            urls = links("Llama-3.2-3B-Instruct-GGUF", "Llama-3.2-3B-Instruct-Q4_K_M.gguf"),
            tier = ModelTier.MAIN,
        ),
        LocalCatalogModel(
            id = "gemma-3-4b-it-q4km",
            title = "Gemma-3-4B-it · Q4_K_M",
            summary = "2.38GB｜Google 4B 指令模型；中英文均衡、多轮对话自然",
            fileName = "gemma-3-4b-it-Q4_K_M.gguf",
            sizeMb = 2375,
            urls = links("gemma-3-4b-it-GGUF", "gemma-3-4b-it-Q4_K_M.gguf"),
            tier = ModelTier.MAIN,
        ),
        LocalCatalogModel(
            id = "phi-4-mini-q4km",
            title = "Phi-4-mini-instruct · Q4_K_M",
            summary = "2.38GB｜微软 3.8B；推理 / 数学见长的小钢炮",
            fileName = "Phi-4-mini-instruct-Q4_K_M.gguf",
            sizeMb = 2376,
            urls = links("Phi-4-mini-instruct-GGUF", "Phi-4-mini-instruct-Q4_K_M.gguf"),
            tier = ModelTier.MAIN,
        ),
        LocalCatalogModel(
            id = "spark-x2.5-4b-q4km",
            title = "Spark-X2.5-4B · Q4_K_M（推荐）",
            summary = "2.42GB｜**Agent 专用**模型（原厂适配 Codex / Claude Code / OpenClaw " +
                "等 harness）。滑动窗口注意力（窗口 512）让长上下文 decode 几乎不衰减 —— " +
                "本机实测 **17.4 t/s**，比 Qwen3-4B（14.6 t/s）快 19%，且 5140 token 的 Agent " +
                "prompt 下优势更大（全注意力模型会掉到 7 t/s 量级）。纯文本，无视觉。",
            fileName = "Spark-X2.5-4B-Q4_K_M.gguf",
            sizeMb = 2480,
            urls = linksFrom("XHToken", "Spark-X2.5-4B-GGUF", "Spark-X2.5-4B-Q4_K_M.gguf") +
                listOf("$MS/XHToken/Spark-X2.5-4B-GGUF/resolve/master/Spark-X2.5-4B-Q4_K_M.gguf"),
            tier = ModelTier.MAIN,
        ),
        LocalCatalogModel(
            id = "qwen3-4b-q4km",
            title = "Qwen3-4B · Q4_K_M",
            summary = "2.38GB｜4B 均衡档；工具调用准确率实测 80%+，日常系统级 Agent 首选",
            fileName = "Qwen3-4B-Q4_K_M.gguf",
            sizeMb = 2382,
            urls = links("Qwen3-4B-GGUF", "Qwen3-4B-Q4_K_M.gguf"),
            tier = ModelTier.MAIN,
        ),
        LocalCatalogModel(
            id = "qwen3-vl-4b-instruct-q4km",
            title = "Qwen3-VL-4B-Instruct · Q4_K_M（多模态）",
            summary = "2.38GB + 视觉塔 0.43GB｜**能看图**：截图理解、界面识别、图片问答",
            fileName = "Qwen3VL-4B-Instruct-Q4_K_M.gguf",
            sizeMb = 2381,
            urls = linksFrom("Qwen", "Qwen3-VL-4B-Instruct-GGUF", "Qwen3VL-4B-Instruct-Q4_K_M.gguf"),
            // 视觉塔必须与主模型同源命名：stem() 去掉量化后缀后两侧都是
            // "Qwen3VL-4B-Instruct" → 配对成功（这正是本机在用的那套命名）。
            companionFileName = "mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf",
            companionSizeMb = 432,
            companionUrls = linksFrom(
                "Qwen", "Qwen3-VL-4B-Instruct-GGUF", "mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf",
            ),
            multimodal = true,
            tier = ModelTier.MAIN,
        ),
        // ══ 高阶 · 4B 以上（质量优先，需 6GB 级可用内存） ══════════════════
        LocalCatalogModel(
            id = "deepseek-r1-7b-q4km",
            title = "DeepSeek-R1-Distill-Qwen-7B · Q4_K_M",
            summary = "4.36GB｜7B 推理蒸馏；推理力与速度平衡",
            fileName = "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
            sizeMb = 4466,
            urls = links("DeepSeek-R1-Distill-Qwen-7B-GGUF", "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf"),
            tier = ModelTier.FLAGSHIP,
        ),
        LocalCatalogModel(
            id = "qwen2.5-7b-instruct-q4km",
            title = "Qwen2.5-7B-Instruct · Q4_K_M",
            summary = "4.36GB｜通义千问 7B；中文写作与指令跟随强，工具调用稳定",
            fileName = "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            sizeMb = 4466,
            urls = linksFrom("bartowski", "Qwen2.5-7B-Instruct-GGUF", "Qwen2.5-7B-Instruct-Q4_K_M.gguf"),
            tier = ModelTier.FLAGSHIP,
        ),
        LocalCatalogModel(
            id = "internlm2.5-7b-chat-q4km",
            title = "InternLM2.5-7B-Chat · Q4_K_M",
            summary = "4.39GB｜书生·浦语 7B；长文与中文工具调用见长",
            fileName = "internlm2_5-7b-chat-Q4_K_M.gguf",
            sizeMb = 4494,
            urls = linksFrom("bartowski", "internlm2_5-7b-chat-GGUF", "internlm2_5-7b-chat-Q4_K_M.gguf"),
            tier = ModelTier.FLAGSHIP,
        ),
        LocalCatalogModel(
            id = "deepseek-r1-0528-qwen3-8b-q4km",
            title = "DeepSeek-R1-0528-Qwen3-8B · Q4_K_M",
            summary = "4.68GB｜8B 档推理力最强的一支；复杂多步任务优先",
            fileName = "DeepSeek-R1-0528-Qwen3-8B-Q4_K_M.gguf",
            sizeMb = 4794,
            urls = links("DeepSeek-R1-0528-Qwen3-8B-GGUF", "DeepSeek-R1-0528-Qwen3-8B-Q4_K_M.gguf"),
            tier = ModelTier.FLAGSHIP,
        ),
        LocalCatalogModel(
            id = "qwen3-8b-q4km",
            title = "Qwen3-8B · Q4_K_M",
            summary = "4.80GB｜Qwen3 8B；工具调用与中文表现稳，质量优先选它",
            fileName = "Qwen3-8B-Q4_K_M.gguf",
            sizeMb = 4795,
            urls = links("Qwen3-8B-GGUF", "Qwen3-8B-Q4_K_M.gguf"),
            tier = ModelTier.FLAGSHIP,
        ),
        // ── 已下架条目（2026-10-04 开源整理，勿再加回）───────────────
        // · Qwen3-30B-A3B（MoE，17.7GB）：本机实测 MoE 流式不可行——专家扫描范围不受
        //   --ubatch 控制，长 prompt 每轮触达近乎全部专家，实测 139GB 读取 / 0 token；
        //   且体积远超可用内存。MoE 引擎已从代码中移除，保留条目即虚假宣传。
        // · MiMo-V2.6-Distill-Qwen-9B：线性注意力（32 层里 24 层无 KV），而 HTP 算子白名单
        //   没有 GATED_DELTA_NET / SSM_CONV / SSM_SCAN —— **NPU 结构性接不住**，
        //   只能落 CPU（慢到不可用）。虽 KV 极省，仍不属可用模型。
        // ── 2026-10-04 新增 6 个（均经下载链接实测 / 见下方说明）──────────
        // 入选标准：① 标准 dense 架构（HTP 算子白名单覆盖标准 attention + MLP，
        //   Gemma-3 的滑窗注意力亦已实测通过）；② Q4_K_M 单文件 ≤4.8GB，8B 级需 6GB 可用内存；
        //   ③ hf-mirror 上文件真实存在（HEAD 200 / Range 206 实测）。
        // 许可证：Qwen2.5 / InternLM2.5 为 Apache-2.0，DeepSeek-R1 蒸馏为 MIT，
        //   Gemma-3 适用 Google Gemma 条款 —— 均只提供**下载链接**，不随本仓库分发模型权重。
        // ⚠️ 诚实标注：新增条目**未逐个在本机 NPU 上做过加载实测**（每次需下载 1~5GB 才能验），
        //   架构上与已实测通过的模型同类，但如遇加载失败请回报 issue。
    )

    fun find(id: String): LocalCatalogModel? = MODELS.firstOrNull { it.id == id }

    fun modelsDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, MODEL_DIR).apply { if (!exists()) mkdirs() }
    }

    /**
     * 模型实际存放位置：**内部存储优先**（2026-09-30）。
     *
     * 为什么：外部目录 `/storage/emulated/...` 是 **FUSE**。BMoe 引擎在该路径上
     * `O_DIRECT` 打开成功但读到错误数据 → 自动回退 buffered I/O → 专家读全走页缓存
     * → 每 token 数百次大缺页（BMoe 官方基准：页缓存 314~1894 次 vs O_DIRECT 6~10 次，
     * 同内核下 0.948 → 0.156 s/tok）。实测后果：13.4GB 的 MoE 加载被拖过桥的 180 秒
     * 预算 → 返回 503 → Agent 进入「模型请求暂时中断，N 秒后重试」链，同时整机卡。
     * 内部目录 `filesDir/models` 是**原生 f2fs**，O_DIRECT 可用。
     * 迁移由 [LocalSettings.migrateModelsToInternal] 完成（走 /data/media 别名 rename，
     * 同分区瞬时）；无 root 时静默退回外部目录，功能不受影响，只是慢。
     */
    fun localFile(context: Context, model: LocalCatalogModel): File {
        val internal = File(LocalSettings.internalModelsDir(context), model.fileName)
        if (internal.isFile && internal.length() > 0) return internal
        return File(modelsDir(context), model.fileName)
    }

    fun isDownloaded(context: Context, model: LocalCatalogModel): Boolean {
        if (!localFile(context, model).let { it.exists() && it.length() > 0 }) return false
        // 多模态模型：视觉塔也必须在位，否则引擎会退化成纯文本（用户以为"图片识别坏了"）。
        val companion = model.companionFileName ?: return true
        val internal = File(LocalSettings.internalModelsDir(context), companion)
        if (internal.isFile && internal.length() > 0) return true
        return File(modelsDir(context), companion).let { it.isFile && it.length() > 0 }
    }

    /** 伴生文件（视觉塔）在 DownloadManager 里的独立记录键。 */
    fun companionKey(model: LocalCatalogModel): String = "${model.id}#mmproj"

    /** 已下载模型列表：内部 + 外部合并，同名以内部为准（内部优先）。 */
    fun downloadedFiles(context: Context, catalogOnly: Boolean = false): List<File> {
        val catalogNames = MODELS.map { it.fileName }.toSet()
        val internal = LocalSettings.internalModelsDir(context).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
        val internalNames = internal.map { it.name }.toSet()
        val external = modelsDir(context).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
            .filter { it.name !in internalNames }
        return (internal + external)
            // 视觉塔（mmproj-*.gguf）是主模型的**伴生文件**，不是可加载模型。
            // 不过滤的话它会被当成一个模型列出，用户点「加载」必然失败（它是 CLIP 塔，不是语言模型）。
            // 2026-10-04 修正：装了 Qwen3-VL / Gemma-3 这类多模态模型的用户都会踩到。
            .filter { !it.name.startsWith("mmproj-", ignoreCase = true) }
            .filter { !catalogOnly || it.name in catalogNames }
            .sortedBy { it.name }
    }

    // ---------------------------------------------------------------- 下载

    /** 把模型交给系统 DownloadManager 下载（后台保活 + 断点续传 + 通知栏进度）。 */
    fun enqueue(context: Context, model: LocalCatalogModel): Long {
        modelsDir(context)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(model.urls.first()))
            .setTitle(model.title)
            .setDescription("微玄 · 端侧模型下载（可后台继续）")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, "$MODEL_DIR/${model.fileName}")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = manager.enqueue(request)
        LocalSettings.recordDownload(model.id, id)
        // 多模态伴生文件（视觉塔）：用派生 key 单独记一个下载任务，与主模型同时开始。
        // 注意伴生文件名**不能改名** —— 运行时靠 stem() 前缀匹配把两者配对（见 data class 注释）。
        model.companionFileName?.let { companion ->
            val companionUrl = model.companionUrls.firstOrNull()
            if (companionUrl != null) {
                runCatching {
                    val req = DownloadManager.Request(Uri.parse(companionUrl))
                        .setTitle("${model.title} · 视觉塔")
                        .setDescription("微玄 · 多模态视觉塔（可后台继续）")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setDestinationInExternalFilesDir(context, null, "$MODEL_DIR/$companion")
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(false)
                    LocalSettings.recordDownload(companionKey(model), manager.enqueue(req))
                }.onFailure { Log.w(TAG, "视觉塔入队失败：${it.message}") }
            }
        }
        return id
    }

    fun status(context: Context, model: LocalCatalogModel): ModelDownloadStatus {
        val id = LocalSettings.downloadId(model.id) ?: return ModelDownloadStatus(DownloadState.NONE, 0, 0)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) {
                LocalSettings.clearDownload(model.id)
                return ModelDownloadStatus(DownloadState.NONE, 0, 0)
            }
            val state = when (
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            ) {
                DownloadManager.STATUS_PENDING -> DownloadState.PENDING
                DownloadManager.STATUS_RUNNING -> DownloadState.RUNNING
                DownloadManager.STATUS_PAUSED -> DownloadState.PAUSED
                DownloadManager.STATUS_SUCCESSFUL -> DownloadState.SUCCEEDED
                else -> DownloadState.FAILED
            }
            val downloaded = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            )
            val total = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            )
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return ModelDownloadStatus(
                state = state,
                downloadedBytes = downloaded.coerceAtLeast(0),
                totalBytes = if (total > 0) total else model.sizeBytes,
                reason = reason,
            )
        }
    }

    fun cancel(context: Context, model: LocalCatalogModel) {
        val id = LocalSettings.downloadId(model.id) ?: return
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { manager.remove(id) }
        LocalSettings.clearDownload(model.id)
        runCatching { localFile(context, model).delete() }
    }

    fun forget(model: LocalCatalogModel) = LocalSettings.clearDownload(model.id)

    /**
     * 删除已下载文件（连同下载记录）。
     *
     * **同时清理配对的视觉塔**（2026-10-04 修正）：多模态模型是两个文件，
     * 只删主模型会留下 mmproj 孤儿文件（Qwen3-VL 那对是 432MB），用户既看不到也删不掉。
     * 配对规则与运行时 `LlamaServerProcess` 一致 —— 把两侧文件名的量化后缀去掉后互相前缀匹配。
     */
    fun delete(context: Context, file: File): Boolean {
        MODELS.firstOrNull { it.fileName == file.name }?.let { model ->
            forget(model)
            model.companionFileName?.let { companion ->
                LocalSettings.clearDownload(companionKey(model))
                listOf(File(LocalSettings.internalModelsDir(context), companion), File(modelsDir(context), companion))
                    .filter { it.isFile }
                    .forEach { runCatching { it.delete() } }
            }
        }
        // 兜底：即便是用户手动导入、不在市场目录里的 VL 模型，也把同源的视觉塔一并清掉，
        // 避免"删了模型但空间没释放"。
        pairedMmprojFiles(context, file).forEach { runCatching { it.delete() } }
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /**
     * 找出与 [mainFile] 配对的视觉塔文件（可能不存在）。
     *
     * 与运行时的配对规则保持**同一套语义**：去掉量化后缀（`-Q4_K_M` / `-Q8_0` / `-F16` …）后
     * 两侧互相前缀匹配即视为一对。例：
     * `Qwen3VL-4B-Instruct-Q4_K_M.gguf` ↔ `mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf`
     */
    private fun pairedMmprojFiles(context: Context, mainFile: File): List<File> {
        val main = stripQuantSuffix(mainFile.nameWithoutExtension)
        if (main.isEmpty()) return emptyList()
        return listOf(LocalSettings.internalModelsDir(context), modelsDir(context))
            .flatMap { dir -> dir.listFiles().orEmpty().toList() }
            .filter { it.isFile && it.name.startsWith("mmproj-", ignoreCase = true) }
            .filter { candidate ->
                val tower = stripQuantSuffix(candidate.nameWithoutExtension.removePrefix("mmproj-"))
                tower.isNotEmpty() && (tower == main || main.startsWith(tower) || tower.startsWith(main))
            }
    }

    /** 去掉 GGUF 的量化后缀，用于视觉塔与主模型配对。 */
    private fun stripQuantSuffix(name: String): String =
        name.replace(Regex("-(Q\\d[^.]*|IQ\\d[^.]*|F16|F32|BF16)$"), "")
}
