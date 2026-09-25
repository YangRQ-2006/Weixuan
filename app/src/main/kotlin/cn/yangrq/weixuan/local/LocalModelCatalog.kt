package cn.yangrq.weixuan.local

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
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
) {
    /** 记录用：以 MB 表示的目标体积。 */
    val sizeBytes: Long get() = sizeMb * 1024L * 1024L
}

/** 模型市场分层（轻量草稿 / 均衡主力 / 高阶旗舰）。 */
enum class ModelTier(val label: String) {
    /** ≤3B：首选推测解码 draft，也可做超轻主模型。 */
    DRAFT("轻量草稿 · 推测解码提速"),

    /** 3~8B：日常系统级 Agent 的速度/质量平衡档。 */
    MAIN("均衡主力 · 日常 Agent"),

    /** ≥10B：复杂多步任务、质量优先。 */
    FLAGSHIP("高阶旗舰 · 复杂任务"),
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

    private fun links(repo: String, file: String): List<String> = listOf(
        "$HF/$repo/resolve/main/$file",
        "$MS/$repo/resolve/master/$file",
    )

    /** 体积为 hf-mirror API 实测字节数换算。 */
    val MODELS: List<LocalCatalogModel> = listOf(
        // ── 轻量草稿（draft 首选） ─────────────────────────────────────
        LocalCatalogModel(
            id = "qwen3-0.6b-q4km",
            title = "Qwen3-0.6B · Q4_K_M（推荐草稿）",
            summary = "378MB｜极轻量思考模型；配合 4B+ 主模型推测解码提速 1.5~2x，也可做超轻主模型",
            fileName = "Qwen3-0.6B-Q4_K_M.gguf",
            sizeMb = 378,
            urls = links("Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_K_M.gguf"),
            tier = ModelTier.DRAFT,
        ),
        LocalCatalogModel(
            id = "deepseek-r1-1.5b-q4km",
            title = "DeepSeek-R1-Distill-Qwen-1.5B · Q4_K_M",
            summary = "1.07GB｜1.5B 推理蒸馏；做 draft 模型配合大模型推测解码，实测可提速 1.5~2x",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            sizeMb = 1066,
            urls = links("DeepSeek-R1-Distill-Qwen-1.5B-GGUF", "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"),
            tier = ModelTier.DRAFT,
        ),
        LocalCatalogModel(
            id = "qwen3-1.7b-q4km",
            title = "Qwen3-1.7B · Q4_K_M",
            summary = "1.06GB｜1.7B 思考型小模型；草稿 / 轻量对话两用",
            fileName = "Qwen3-1.7B-Q4_K_M.gguf",
            sizeMb = 1056,
            urls = links("Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf"),
            tier = ModelTier.DRAFT,
        ),
        LocalCatalogModel(
            id = "llama-3.2-3b-q4km",
            title = "Llama-3.2-3B-Instruct · Q4_K_M",
            summary = "1.93GB｜Meta 3B 指令小模型；英文轻量通用 / 备选草稿",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeMb = 1926,
            urls = links("Llama-3.2-3B-Instruct-GGUF", "Llama-3.2-3B-Instruct-Q4_K_M.gguf"),
            tier = ModelTier.DRAFT,
        ),
        // ── 均衡主力 ────────────────────────────────────────────────
        LocalCatalogModel(
            id = "qwen3-4b-q4km",
            title = "Qwen3-4B · Q4_K_M",
            summary = "2.38GB｜4B 均衡档，速度快，适合日常系统级 Agent 与工具调用",
            fileName = "Qwen3-4B-Q4_K_M.gguf",
            sizeMb = 2382,
            urls = links("Qwen3-4B-GGUF", "Qwen3-4B-Q4_K_M.gguf"),
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
            id = "gemma-3-4b-it-q4km",
            title = "Gemma-3-4B-it · Q4_K_M",
            summary = "2.38GB｜Google 4B 指令模型；中英文均衡、多轮对话自然",
            fileName = "gemma-3-4b-it-Q4_K_M.gguf",
            sizeMb = 2375,
            urls = links("gemma-3-4b-it-GGUF", "gemma-3-4b-it-Q4_K_M.gguf"),
            tier = ModelTier.MAIN,
        ),
        LocalCatalogModel(
            id = "qwen3-8b-q4km",
            title = "Qwen3-8B · Q4_K_M（推荐主力）",
            summary = "4.80GB｜8B 主力档；工具调用与中文表现稳，建议配 0.6B 草稿提速",
            fileName = "Qwen3-8B-Q4_K_M.gguf",
            sizeMb = 4795,
            urls = links("Qwen3-8B-GGUF", "Qwen3-8B-Q4_K_M.gguf"),
            tier = ModelTier.MAIN,
        ),
        LocalCatalogModel(
            id = "deepseek-r1-7b-q4km",
            title = "DeepSeek-R1-Distill-Qwen-7B · Q4_K_M",
            summary = "4.36GB｜7B 主力档，速度与推理力平衡，适合日常系统级 Agent",
            fileName = "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
            sizeMb = 4466,
            urls = links("DeepSeek-R1-Distill-Qwen-7B-GGUF", "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf"),
            tier = ModelTier.MAIN,
        ),
        // ── 高阶旗舰 ────────────────────────────────────────────────
        LocalCatalogModel(
            id = "qwen3-14b-q4km",
            title = "Qwen3-14B · Q4_K_M",
            summary = "8.59GB｜14B，复杂多步任务明显更稳（建议配 0.6B 草稿提速）",
            fileName = "Qwen3-14B-Q4_K_M.gguf",
            sizeMb = 8585,
            urls = links("Qwen3-14B-GGUF", "Qwen3-14B-Q4_K_M.gguf"),
            tier = ModelTier.FLAGSHIP,
        ),
        LocalCatalogModel(
            id = "deepseek-r1-14b-q4km",
            title = "DeepSeek-R1-Distill-Qwen-14B · Q4_K_M",
            summary = "8.36GB｜14B 推理蒸馏，复杂多步任务明显更稳（建议配 1.5B draft 提速）",
            fileName = "DeepSeek-R1-Distill-Qwen-14B-Q4_K_M.gguf",
            sizeMb = 8572,
            urls = links("DeepSeek-R1-Distill-Qwen-14B-GGUF", "DeepSeek-R1-Distill-Qwen-14B-Q4_K_M.gguf"),
            tier = ModelTier.FLAGSHIP,
        ),
        LocalCatalogModel(
            id = "qwen3-30b-a3b-q4km",
            title = "Qwen3-30B-A3B · Q4_K_M（MoE 省算力）",
            summary = "17.7GB｜MoE 仅激活 3B；带宽有限机型跑大模型的最优解",
            fileName = "Qwen3-30B-A3B-Q4_K_M.gguf",
            sizeMb = 17697,
            urls = links("Qwen3-30B-A3B-GGUF", "Qwen3-30B-A3B-Q4_K_M.gguf"),
            tier = ModelTier.FLAGSHIP,
        ),
        LocalCatalogModel(
            id = "deepseek-r1-32b-q4km",
            title = "DeepSeek-R1-Distill-Qwen-32B · Q4_K_M（20B+ 旗舰）",
            summary = "约 19.9GB｜32B 蒸馏，质量档首选；必须配 1.5B draft + SWA 滑窗才可接受",
            fileName = "DeepSeek-R1-Distill-Qwen-32B-Q4_K_M.gguf",
            sizeMb = 19900,
            urls = links("DeepSeek-R1-Distill-Qwen-32B-GGUF", "DeepSeek-R1-Distill-Qwen-32B-Q4_K_M.gguf"),
            tier = ModelTier.FLAGSHIP,
        ),
        LocalCatalogModel(
            id = "deepseek-r1-32b-q2k",
            title = "DeepSeek-R1-Distill-Qwen-32B · Q2_K（省空间）",
            summary = "约 11.6GB｜32B 低比特量化，显存/内存吃紧时的折中档（质量下降明显）",
            fileName = "DeepSeek-R1-Distill-Qwen-32B-Q2_K.gguf",
            sizeMb = 11600,
            urls = links("DeepSeek-R1-Distill-Qwen-32B-GGUF", "DeepSeek-R1-Distill-Qwen-32B-Q2_K.gguf"),
            tier = ModelTier.FLAGSHIP,
        ),
    )

    fun find(id: String): LocalCatalogModel? = MODELS.firstOrNull { it.id == id }

    fun modelsDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, MODEL_DIR).apply { if (!exists()) mkdirs() }
    }

    fun localFile(context: Context, model: LocalCatalogModel): File =
        File(modelsDir(context), model.fileName)

    fun isDownloaded(context: Context, model: LocalCatalogModel): Boolean =
        localFile(context, model).let { it.exists() && it.length() > 0 }

    fun downloadedFiles(context: Context, catalogOnly: Boolean = false): List<File> {
        val catalogNames = MODELS.map { it.fileName }.toSet()
        return modelsDir(context)
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
            ?.filter { !catalogOnly || it.name in catalogNames }
            ?.sortedBy { it.name }
            ?: emptyList()
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

    /** 删除已下载文件（连同记录）。 */
    fun delete(context: Context, file: File): Boolean {
        MODELS.firstOrNull { it.fileName == file.name }?.let { forget(it) }
        return runCatching { file.delete() }.getOrDefault(false)
    }
}
