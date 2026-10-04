package cn.yangrq.weixuan.ui.pages.local

import android.os.StatFs
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.yangrq.weixuan.local.DownloadState
import cn.yangrq.weixuan.local.LocalCatalogModel
import cn.yangrq.weixuan.local.LocalModelCatalog
import cn.yangrq.weixuan.local.LocalSettings
import cn.yangrq.weixuan.local.ModelDownloadStatus
import cn.yangrq.weixuan.local.ModelTier
import cn.yangrq.weixuan.ui.components.EtaArrowPreference
import cn.yangrq.weixuan.ui.components.EtaPreferenceColors
import cn.yangrq.weixuan.ui.components.EtaPreferenceDivider
import cn.yangrq.weixuan.ui.components.EtaPreferenceGroup
import cn.yangrq.weixuan.ui.components.EtaPreferenceGroupTitle
import cn.yangrq.weixuan.ui.components.EtaPreferenceIcon
import cn.yangrq.weixuan.ui.components.MiuixScaffoldPage
import cn.yangrq.weixuan.ui.design.XuanGlyphType
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * **模型市场**（2026-10-04 从 `LocalModelScreen` 剥离为独立页面）。
 *
 * 剥离理由：原来市场与"引擎状态 / 主模型设置 / 模型管理"挤在同一页（742 行），
 * 用户既要配引擎又要挑模型，信息密度过高。现在市场单独成页，从「设置 → 模型市场」进入。
 *
 * 本页只负责**挑模型 + 下载 + 删除**；「设为可加载的主模型」仍在本地模型页完成，
 * 所以下载完成后本页给一条直达入口（[onOpenLocalModels]），避免用户在两个页面间找路。
 */
@Composable
internal fun ModelMarketScreen(
    onBack: () -> Unit,
    onOpenLocalModels: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    var status by remember { mutableStateOf(mapOf<String, ModelDownloadStatus>()) }
    var downloadedIds by remember { mutableStateOf(emptySet<String>()) }
    var freeMb by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf("") }

    fun refresh() {
        status = LocalModelCatalog.MODELS.associate { it.id to LocalModelCatalog.status(appContext, it) }
        downloadedIds = LocalModelCatalog.MODELS
            .filter { LocalModelCatalog.isDownloaded(appContext, it) }
            .map { it.id }
            .toSet()
        freeMb = runCatching {
            val dir = LocalSettings.internalModelsDir(appContext)
            val stat = StatFs(if (dir.exists()) dir.absolutePath else appContext.filesDir.absolutePath)
            stat.availableBytes / 1024 / 1024
        }.getOrDefault(0L)
    }

    LaunchedEffect(Unit) { refresh() }

    /** 有活动下载时每秒刷新一次进度；无活动下载则静默（避免无谓重绘）。 */
    LaunchedEffect(status) {
        val active = status.values.any {
            it.state == DownloadState.RUNNING ||
                it.state == DownloadState.PENDING ||
                it.state == DownloadState.PAUSED
        }
        if (active) {
            delay(1000)
            refresh()
        }
    }

    MiuixScaffoldPage(title = "模型市场", onBack = onBack) {
        item {
            EtaPreferenceGroupTitle("下载源与空间")
        }
        item {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = "可用空间 ${fmtSize(freeMb)}",
                    summary = "模型存于应用私有目录（原生 f2fs，读取更快）；" +
                        "下载走系统下载器，支持后台续传",
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Model, tint = EtaPreferenceColors.Blue)
                    },
                    onClick = { refresh() },
                )
                if (message.isNotBlank()) {
                    EtaPreferenceDivider()
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }

        // 分层展示：每档给一句"该档适合谁"，避免用户在 15 个条目里盲选
        ModelTier.values().forEach { tier ->
            item {
                EtaPreferenceGroupTitle(tier.label)
            }
            item {
                EtaPreferenceGroup {
                    LocalModelCatalog.MODELS.filter { it.tier == tier }
                        .forEachIndexed { index, model ->
                            if (index > 0) EtaPreferenceDivider()
                            MarketModelRow(
                                model = model,
                                status = status[model.id],
                                downloaded = model.id in downloadedIds,
                                onDownload = {
                                    LocalModelCatalog.enqueue(appContext, model)
                                    message = "已加入下载：${model.title}" +
                                        if (model.multimodal) "（含视觉塔 ${fmtSize(model.companionSizeMb)}）" else ""
                                    refresh()
                                },
                                onCancel = {
                                    LocalModelCatalog.cancel(appContext, model)
                                    message = "已取消：${model.title}"
                                    refresh()
                                },
                            )
                        }
                }
            }
        }

        item {
            EtaPreferenceGroupTitle("下载完成后")
        }
        item {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = "去「本地模型」页设为主模型并加载",
                    summary = "市场页只管下载；加载与运行参数在本地模型页配置",
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Model, tint = EtaPreferenceColors.Green)
                    },
                    onClick = onOpenLocalModels,
                )
            }
        }

        item {
            Text(
                text = "说明：模型权重来自各自官方仓库（Qwen / Google / Microsoft / Meta / DeepSeek / 上海 AI Lab），" +
                    "本应用只提供下载链接、不随安装包分发。已实测排除 MoE 与线性注意力模型 —— " +
                    "前者在本机不可行，后者当前 NPU 不支持，因此列表里不会出现。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }

}

@Composable
private fun MarketModelRow(
    model: LocalCatalogModel,
    status: ModelDownloadStatus?,
    downloaded: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val active = status != null && (
        status.state == DownloadState.RUNNING ||
            status.state == DownloadState.PENDING ||
            status.state == DownloadState.PAUSED
        )
    val title = if (model.multimodal) "${model.title} · 可看图" else model.title
    val summary = when {
        active -> "下载中 ${(status!!.progress * 100).toInt()}%" +
            "（${fmtSize(status.downloadedBytes / 1024 / 1024)} / ${fmtSize(model.totalSizeMb)}，可退出 App）"
        status?.state == DownloadState.SUCCEEDED && !downloaded ->
            "主文件已下载，视觉塔仍在下载（多模态需两者齐全）"
        status?.state == DownloadState.FAILED -> "下载失败（code=${status.reason}），点此重试"
        downloaded -> "${fmtSize(model.sizeMb)}｜已在本机 —— 到「本地模型」页加载 / 删除，或点此重新下载覆盖"
        else -> "${model.summary}　·　需约 ${needMemGb(model)}GB 可用内存"
    }
    EtaArrowPreference(
        title = title,
        summary = summary,
        startAction = {
            EtaPreferenceIcon(
                glyph = when {
                    active -> XuanGlyphType.Download
                    downloaded -> XuanGlyphType.Model
                    else -> XuanGlyphType.Download
                },
                tint = when {
                    active -> EtaPreferenceColors.Blue
                    downloaded -> EtaPreferenceColors.Green
                    else -> EtaPreferenceColors.Blue
                },
            )
        },
        onClick = if (active) onCancel else onDownload,
    )
}

/** 体积显示：≥1024MB 用 GB，避免出现 "2382MB" 这种不直观的数。 */
private fun fmtSize(mb: Long): String =
    if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else "$mb MB"

/**
 * 可用内存需求估算：权重 + KV/运行时余量。
 * 系数来自实测（4B ≈ 4GB、8B ≈ 6GB，见 README「我的手机能跑吗」），
 * 仅用于给用户一个量级提示，实际由应用内的内存预检把关。
 */
private fun needMemGb(model: LocalCatalogModel): String =
    String.format("%.1f", (model.totalSizeMb / 1024.0) + 1.5)
