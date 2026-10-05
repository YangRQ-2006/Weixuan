package cn.yangrq.weixuan.ui.pages.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.yangrq.weixuan.local.DownloadState
import cn.yangrq.weixuan.local.GenieXLocalEngine
import cn.yangrq.weixuan.local.LocalCatalogModel
import cn.yangrq.weixuan.local.LocalModelCatalog
import cn.yangrq.weixuan.local.ModelDownloadStatus
import cn.yangrq.weixuan.local.ModelTier
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import cn.yangrq.weixuan.local.LocalBackend
import cn.yangrq.weixuan.local.ModelCompat
import cn.yangrq.weixuan.local.LocalEngineStatus
import cn.yangrq.weixuan.local.LocalModelEntry
import cn.yangrq.weixuan.local.LocalPerfTuner
import cn.yangrq.weixuan.local.LocalServerHost
import cn.yangrq.weixuan.local.LocalSettings
import cn.yangrq.weixuan.ui.components.EtaArrowPreference
import cn.yangrq.weixuan.ui.components.EtaDropdownPreference
import cn.yangrq.weixuan.ui.components.EtaPreferenceColors
import cn.yangrq.weixuan.ui.components.EtaPreferenceDivider
import cn.yangrq.weixuan.ui.components.EtaPreferenceGroup
import cn.yangrq.weixuan.ui.components.EtaPreferenceGroupTitle
import cn.yangrq.weixuan.ui.components.EtaPreferenceIcon
import cn.yangrq.weixuan.ui.components.EtaSwitchPreference
import cn.yangrq.weixuan.ui.components.EtaTextButton
import cn.yangrq.weixuan.ui.components.EtaWindowDialog
import cn.yangrq.weixuan.ui.components.MiuixScaffoldPage
import cn.yangrq.weixuan.ui.design.XuanGlyphType
import java.io.File
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 本地模型（GenieX NPU/GPU）配置页 —— 三段式布局：
 *
 * 1. 【顶部】模型设置：主模型 / 草稿模型 选择（含加载、卸载）；
 * 2. 【中部】模型市场：按「轻量草稿 / 均衡主力 / 高阶旗舰」分层的一键下载目录；
 * 3. 【底部】模型管理：模型文件（.gguf）的设为主/草稿、删除、导入，以及 GenieX 模型中心管理。
 *
 * 模型本身由 Eta 原生 Provider 列表里的「本地模型（GenieX NPU）」条目选中使用。
 */
@Composable
internal fun LocalModelScreen(
    onBack: () -> Unit,
    onOpenMarket: () -> Unit,
    onOpenServer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val engineState by GenieXLocalEngine.state.collectAsState()

    var serverEnabled by remember { mutableStateOf(LocalSettings.serverEnabled) }
    var autoLoad by remember { mutableStateOf(LocalSettings.autoLoad) }
    var mainPath by remember { mutableStateOf(LocalSettings.customModelPath) }
    var draftPath by remember { mutableStateOf(LocalSettings.draftModelPath) }
    var draftOn by remember { mutableStateOf(LocalSettings.draftEnabled) }
    var centerModel by remember { mutableStateOf(LocalSettings.modelName) }
    var thinkingOn by remember { mutableStateOf(LocalSettings.thinkingEnabled) }
    // （MoE 流式引擎已移除：该架构对长 prompt 的 Agent 负载不收敛，详见 2026-09-30 结论）
    var selfBuiltOn by remember { mutableStateOf(LocalSettings.useSelfBuiltEngine) }
    // ── 自建 runtime 的推理后端（2026-10-02）─────────────────────────────────
    // 可选列表由**设备探测**决定，而不是硬编码：让 llama-server 自己报一遍可用设备
    //（--list-devices），只把本机真能跑起来的口味摆到 UI 上，避免"选了 GPU 结果起不来"。
    var backendId by remember { mutableStateOf(LocalSettings.localBackend) }
    var backendOptions by remember { mutableStateOf(LocalBackend.entries.toList()) }
    // 兼容性判定（2026-10-02，抄自 PocketOrca）：后端 × 量化格式 × 设备
    var compatByBackend by remember { mutableStateOf(emptyMap<String, ModelCompat.Verdict>()) }
    var quantDesc by remember { mutableStateOf("") }
    val selectedBackend = LocalBackend.fromId(backendId)
    LaunchedEffect(Unit) {
        // 三个口味**并发**探测：串行时要依次 dlopen 44MB 的 Vulkan 插件，页面会明显卡顿，
        // 而且中途被回收（组合被销毁）就会只探到一半，UI 停在「全部可选」的初值上。
        backendOptions = withContext(Dispatchers.IO) {
            LocalBackend.entries
                .map { backend -> async { backend to LocalBackend.isAvailable(appContext, backend) } }
                .awaitAll()
                .filter { it.second }
                .map { it.first }
                .ifEmpty { listOf(LocalBackend.NPU_HTP, LocalBackend.CPU) }
        }
        // 曾选中的口味被探测判定为不可用时，回落到 NPU，避免用户对着一片红字
        if (backendOptions.none { it.id == backendId }) {
            val fallback = LocalBackend.DEFAULT
            backendId = fallback.id
            LocalSettings.localBackend = fallback.id
        }
    }
    // 兼容性判定：模型换了或后端表变了就重算（读 GGUF 头部 + 设备矩阵，放 IO）
    LaunchedEffect(mainPath, backendOptions) {
        withContext(Dispatchers.IO) {
            val quant = ModelCompat.quantOf(mainPath.takeIf { it.isNotBlank() }?.let { java.io.File(it) })
            quantDesc = quant?.describe() ?: ""
            compatByBackend = backendOptions.associate { it.id to ModelCompat.check(it, quant) }
        }
    }
    var models by remember { mutableStateOf<List<LocalModelEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var catalogFiles by remember { mutableStateOf(listOf<File>()) }
    var catalogStatus by remember { mutableStateOf(mapOf<String, ModelDownloadStatus>()) }
    var actionFile by remember { mutableStateOf<File?>(null) }

    // 注：本地推理服务器（2026-10-05）的配置已**剥离**为独立二级页 LocalServerScreen，
    // 从「设置 → 本地推理服务器」进入（本页只保留一个入口，见下方「模型」分节旁边）。
    // 原先的 srvEnabled/srvLan/srvPort/srvSlots/srvApiKey/端口弹窗/Key 弹窗全部迁走，
    // 避免两处重复配置。

    fun reloadCatalogFiles() {
        catalogFiles = LocalModelCatalog.downloadedFiles(appContext)
    }

    fun refreshCatalogStatus() {
        catalogStatus = LocalModelCatalog.MODELS.associate { model ->
            model.id to LocalModelCatalog.status(appContext, model)
        }
    }

    fun downloadCatalogModel(model: LocalCatalogModel) {
        runCatching { LocalModelCatalog.enqueue(appContext, model) }
            .onSuccess {
                message = "已交给系统下载（通知栏可见、后台继续、断点续传）：${model.title}"
                refreshCatalogStatus()
            }
            .onFailure { message = "下载启动失败：${it.message}" }
    }

    fun importModel(uri: android.net.Uri) {
        scope.launch {
            busy = true
            message = "正在导入模型文件…"
            val result = runCatching {
                val resolver = appContext.contentResolver
                val display = runCatching {
                    resolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                    }
                }.getOrNull()
                val safeName = (display ?: "")
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .ifBlank { "weixuan-${System.currentTimeMillis()}.gguf" }
                    .let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
                val target = File(LocalSettings.modelsDir(appContext), safeName)
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        val total = resolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
                        val buffer = ByteArray(1 shl 20)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) {
                                message = "正在导入 ${safeName}… ${(copied * 100 / total)}%"
                            }
                        }
                    }
                } ?: error("无法读取所选文件")
                target.absolutePath
            }
            result
                .onSuccess { path ->
                    val sizeMb = File(path).length() / 1024 / 1024
                    reloadCatalogFiles()
                    message = "已导入 ${File(path).name}（${sizeMb}MB），点击该文件可设为主模型 / 草稿模型"
                }
                .onFailure { message = "导入失败：${it.message}" }
            busy = false
        }
    }

    val pickModelFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    fun refresh() {
        scope.launch {
            models = GenieXLocalEngine.refreshInstalledModels()
        }
    }

    LaunchedEffect(Unit) {
        GenieXLocalEngine.initialize(appContext)
        refresh()
        reloadCatalogFiles()
        refreshCatalogStatus()
        if (serverEnabled) {
            val port = LocalServerHost.startAndSync(GenieXLocalEngine, LocalSettings.port)
            message = if (port > 0) {
                "服务地址：http://127.0.0.1:$port/v1"
            } else {
                "服务启动失败：候选端口均被占用"
            }
        }
        // 轮询系统下载状态与已下载文件列表（DownloadManager 在 App 外异步推进）
        while (true) {
            delay(2000)
            refreshCatalogStatus()
            reloadCatalogFiles()
        }
    }

    // ── 选择器数据：主模型 / 草稿模型 ─────────────────────────────────
    val fileOptions = catalogFiles.map { "${it.nameWithoutExtension}（${it.length() / 1048576}MB）" }
    // 注：曾于 2026-09-28 试验 QAIRT(AI Hub 预编译 bundle) 与 GenieX llama.cpp 两条路径，
    // 因依赖链过深且触发原生崩溃（JNI GetIntField 类型错误 -> SIGABRT）已全部移除，
    // 回到自建 llama.cpp 引擎（稳定 10+ tok/s）。
    val mainItems = listOf("GenieX 模型中心 · $centerModel") + fileOptions
    val mainIndex = (catalogFiles.indexOfFirst { it.absolutePath == mainPath } + 1).coerceAtLeast(0)
    val draftItems = listOf(
        "不使用草稿模型",
        "自动 · ${LocalPerfTuner.DRAFT_MODEL_NAME}（模型中心）",
    ) + fileOptions
    val draftIndex = when {
        !draftOn -> 0
        draftPath.isBlank() -> 1
        else -> (catalogFiles.indexOfFirst { it.absolutePath == draftPath } + 2).coerceAtLeast(0)
    }

    MiuixScaffoldPage(title = "本地模型", onBack = onBack) {
        // ══ ① 顶部：模型设置（主模型 / 草稿模型） ══════════════════════
        item {
            EtaPreferenceGroupTitle("模型设置 · 主模型与草稿模型")
        }
        item {
            EtaPreferenceGroup {
                EtaDropdownPreference(
                    title = "主模型",
                    summary = if (mainPath.isBlank()) {
                        "空 = 使用模型中心（$centerModel）；也可选已下载的 .gguf 文件"
                    } else {
                        "已选文件：${File(mainPath).name}"
                    },
                    items = mainItems.map { DropdownItem(text = it) },
                    selectedIndex = mainIndex,
                    enabled = !busy,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Model, tint = EtaPreferenceColors.Blue)
                    },
                    onSelectedIndexChange = { idx ->
                        val picked = catalogFiles.getOrNull(idx - 1)
                        mainPath = picked?.absolutePath ?: ""
                        LocalSettings.customModelPath = mainPath
                        message = if (picked != null) {
                            "主模型已设为 ${picked.name}，点「加载模型」生效"
                        } else {
                            "主模型改用 GenieX 模型中心（$centerModel）"
                        }
                    },
                )
                EtaPreferenceDivider()
                EtaDropdownPreference(
                    title = "草稿模型（推测解码提速 1.5~2x）",
                    summary = when {
                        !draftOn -> "已关闭：不使用草稿模型"
                        draftPath.isBlank() -> "自动：模型中心 ${LocalPerfTuner.DRAFT_MODEL_NAME}（不存在时无草稿）"
                        else -> "已选文件：${File(draftPath).name}"
                    },
                    items = draftItems.map { DropdownItem(text = it) },
                    selectedIndex = draftIndex,
                    enabled = !busy,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Play, tint = EtaPreferenceColors.Green)
                    },
                    onSelectedIndexChange = { idx ->
                        when {
                            idx == 0 -> {
                                draftOn = false
                                LocalSettings.draftEnabled = false
                                message = "已关闭推测解码"
                            }
                            idx == 1 -> {
                                draftOn = true
                                draftPath = ""
                                LocalSettings.draftEnabled = true
                                LocalSettings.draftModelPath = ""
                                message = "草稿模型：自动（模型中心 ${LocalPerfTuner.DRAFT_MODEL_NAME}）"
                            }
                            else -> {
                                val picked = catalogFiles.getOrNull(idx - 2) ?: return@EtaDropdownPreference
                                draftOn = true
                                draftPath = picked.absolutePath
                                LocalSettings.draftEnabled = true
                                LocalSettings.draftModelPath = picked.absolutePath
                                message = "草稿模型已设为 ${picked.name}"
                            }
                        }
                    },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = "加载模型",
                    summary = (if (mainPath.isBlank()) "$centerModel · ${LocalSettings.modelPrecision}" else File(mainPath).name) +
                        (if (engineState.status == LocalEngineStatus.READY) "（已加载）" else ""),
                    enabled = !busy,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Play, tint = EtaPreferenceColors.Green)
                    },
                    onClick = {
                        scope.launch {
                            busy = true
                            message = "正在加载模型…"
                            val result = GenieXLocalEngine.loadModel()
                            message = result.exceptionOrNull()?.message ?: "模型加载完成"
                            busy = false
                        }
                    },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = "卸载模型释放内存",
                    summary = "推理结束后建议卸载，避免系统回收进程",
                    enabled = engineState.status == LocalEngineStatus.READY,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Stop, tint = EtaPreferenceColors.Orange)
                    },
                    onClick = {
                        scope.launch {
                            GenieXLocalEngine.unload()
                            message = "已卸载模型"
                        }
                    },
                )
                EtaPreferenceDivider(hasLeading = false)
                Text(
                    text = "引擎状态：${engineState.status.label()}" +
                        (engineState.modelName.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""),
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    text = buildString {
                        append("上下文：${engineState.nCtx} token")
                        if (engineState.computeUnit.isNotBlank()) append("｜算力单元：${engineState.computeUnit}")
                        append("｜推测解码：${if (engineState.speculativeDecoding) "开" else "关"}")
                        if (engineState.lastSpeedTokensPerSecond > 0.0) {
                            append("｜实测：%.1f tok/s".format(engineState.lastSpeedTokensPerSecond))
                        }
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Text(
                    text = "服务地址：${LocalSettings.baseUrl()}（在「模型提供方」页选中「本地模型（GenieX NPU）」即可使用）",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (message.isNotBlank()) {
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote1,
                        color = EtaPreferenceColors.Orange,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }

        item {
            EtaPreferenceGroupTitle("运行设置")
        }
        item {
            EtaPreferenceGroup {
                EtaSwitchPreference(
                    title = "用自建 llama.cpp 引擎",
                    summary = "开＝自建 runtime（GGUF，KV 量化/前缀复用等参数完全可控）；" +
                        "关＝GenieX/QAIRT（AI Hub 预编译 bundle，NPU-only 峰值路径）",
                    checked = selfBuiltOn,
                    onCheckedChange = { enabled ->
                        selfBuiltOn = enabled
                        LocalSettings.useSelfBuiltEngine = enabled
                    },
                )
                EtaPreferenceDivider()
                EtaDropdownPreference(
                    title = "推理后端（自建引擎）",
                    summary = if (!selfBuiltOn) {
                        "当前用 GenieX 引擎，此项不生效"
                    } else {
                        val v = compatByBackend[backendId]
                        buildString {
                            append(selectedBackend.detail)
                            if (v != null) append(" ｜ ").append(ModelCompat.badge(v))
                        }
                    },
                    items = backendOptions.map { DropdownItem(text = it.label) },
                    selectedIndex = backendOptions.indexOfFirst { it.id == backendId }.coerceAtLeast(0),
                    enabled = selfBuiltOn && !busy,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Model, tint = EtaPreferenceColors.Blue)
                    },
                    onSelectedIndexChange = { index ->
                        val picked = backendOptions.getOrNull(index)
                            ?: return@EtaDropdownPreference
                        backendId = picked.id
                        LocalSettings.localBackend = picked.id
                        // 换口味后设备集合可能变化，重新探测
                        LocalBackend.invalidateProbe()
                        message = "推理后端已切换为「${picked.label}」，重新加载模型后生效"
                    },
                )
                EtaPreferenceDivider()
                EtaSwitchPreference(
                    title = "启用本地回环服务",
                    summary = "开启后 Agent 可通过 127.0.0.1:${LocalSettings.port} 调用本地模型",
                    checked = serverEnabled,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Model, tint = EtaPreferenceColors.Blue)
                    },
                    onCheckedChange = { enabled ->
                        serverEnabled = enabled
                        LocalSettings.serverEnabled = enabled
                        if (enabled) {
                            scope.launch {
                                val port = LocalServerHost.startAndSync(GenieXLocalEngine, LocalSettings.port)
                                message = if (port > 0) {
                                    "服务地址：http://127.0.0.1:$port/v1"
                                } else {
                                    "服务启动失败：候选端口均被占用"
                                }
                            }
                        } else {
                            LocalServerHost.stop()
                            message = ""
                        }
                    },
                )
                // MoE 流式引擎开关已移除（2026-09-30）：该引擎在 3.4k tokens 的 Agent prompt 上
                // 预填不收敛（实测 139GB 读取 / 2 分钟 / 0 token），改用 dense + NPU 路线。
                EtaPreferenceDivider()
                EtaPreferenceDivider()
                EtaSwitchPreference(
                    title = "思考模式",
                    summary = "关闭后本地模型直接作答，速度大幅提升（复杂推理质量略降）",
                    checked = thinkingOn,
                    onCheckedChange = { enabled ->
                        thinkingOn = enabled
                        LocalSettings.thinkingEnabled = enabled
                    },
                )
                EtaPreferenceDivider()
                EtaSwitchPreference(
                    title = "启动后自动加载模型",
                    summary = "占用较多内存，仅在需要时开启",
                    checked = autoLoad,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Play, tint = EtaPreferenceColors.Green)
                    },
                    onCheckedChange = { enabled ->
                        autoLoad = enabled
                        LocalSettings.autoLoad = enabled
                    },
                )
            }
        }

        // ── 本地推理服务器入口（2026-10-05 已剥离为独立二级页 LocalServerScreen）──
        // 原先服务器模式的配置塞在本页里，用户反馈"藏得太深"，现已单独成页；本页只留入口。
        item {
            EtaPreferenceGroupTitle("本地推理服务器")
        }
        item {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = "本地推理服务器（把手机当成私有 AI 服务器）",
                    summary = if (LocalSettings.localServerEnabled) {
                        "已开启 · ${LocalSettings.localServerHost()}:${LocalSettings.localServerPort} · 点此管理"
                    } else {
                        "已关闭 · 配置绑定范围 / 端口 / 并发槽数 / API Key"
                    },
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Globe, tint = EtaPreferenceColors.Blue)
                    },
                    onClick = onOpenServer,
                )
            }
        }

        // ══ ② 模型市场入口（2026-10-04 已剥离为独立页面 ModelMarketScreen）══
        item {
            EtaPreferenceGroupTitle("模型")
        }
        item {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = "模型市场",
                    summary = "浏览并下载端侧模型（共 ${LocalModelCatalog.MODELS.size} 个，" +
                        "含可看图的多模态模型）",
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Download, tint = EtaPreferenceColors.Blue)
                    },
                    onClick = onOpenMarket,
                )
            }
        }

        // ══ ③ 底部：模型管理 ══════════════════════════════════════════
        item {
            EtaPreferenceGroupTitle("模型管理 · 模型文件（点击条目设为主/草稿或删除）")
        }
        item {
            EtaPreferenceGroup {
                if (catalogFiles.isEmpty()) {
                    Text(
                        text = "models 目录暂无 .gguf 文件：可从上方「模型市场」下载，或点下方「导入」。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                } else {
                    catalogFiles.forEachIndexed { index, file ->
                        if (index > 0) EtaPreferenceDivider()
                        val sizeMb = file.length() / 1024 / 1024
                        val tags = buildList {
                            if (file.absolutePath == mainPath) add("主模型")
                            if (file.absolutePath == draftPath) add("草稿")
                        }
                        EtaArrowPreference(
                            title = file.name,
                            summary = "${sizeMb}MB" + if (tags.isNotEmpty()) {
                                " · 当前${tags.joinToString(" / ")}"
                            } else {
                                " · 点击设为主模型 / 草稿模型或删除"
                            },
                            enabled = !busy,
                            startAction = {
                                EtaPreferenceIcon(
                                    glyph = XuanGlyphType.Model,
                                    tint = if (tags.isNotEmpty()) EtaPreferenceColors.Green else EtaPreferenceColors.Blue,
                                )
                            },
                            onClick = { actionFile = file },
                        )
                    }
                }
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = "导入 .gguf 模型文件",
                    summary = "从文件管理器选择（保留原文件名），导入后点击条目即可设为主/草稿",
                    enabled = !busy,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Download, tint = EtaPreferenceColors.Blue)
                    },
                    onClick = { pickModelFile.launch(arrayOf("*/*")) },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = "清空自定义模型路径",
                    summary = "主模型改回 GenieX 模型中心（$centerModel），并移除草稿设置",
                    enabled = !busy,
                    onClick = {
                        mainPath = ""
                        draftPath = ""
                        LocalSettings.customModelPath = ""
                        LocalSettings.draftModelPath = ""
                        message = "已清空自定义模型路径"
                    },
                )
            }
        }

        item {
            EtaPreferenceGroupTitle("模型管理 · GenieX 模型中心（本机已安装）")
        }
        item {
            EtaPreferenceGroup {
                if (models.isEmpty()) {
                    Text(
                        text = "尚未检测到模型中心安装记录；从上方市场下载的文件不依赖模型中心。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                } else {
                    models.forEachIndexed { index, entry ->
                        if (index > 0) EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = entry.displayName,
                            summary = (entry.modelPath ?: "未安装") +
                                (if (entry.name == centerModel) " · 当前模型中心模型" else ""),
                            onClick = {
                                centerModel = entry.name
                                LocalSettings.modelName = entry.name
                                message = "已切换模型中心模型：${entry.name}"
                            },
                        )
                    }
                }
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = "刷新列表",
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Refresh, tint = EtaPreferenceColors.Blue)
                    },
                    onClick = { refresh() },
                )
            }
        }
    }

    // ── 模型文件操作面板（设为主 / 设为草稿 / 删除） ─────────────────────
    actionFile?.let { file ->
        ModelFileActionDialog(
            file = file,
            isMain = file.absolutePath == mainPath,
            isDraft = file.absolutePath == draftPath,
            onSetMain = {
                mainPath = file.absolutePath
                LocalSettings.customModelPath = file.absolutePath
                message = "已设为主模型：${file.name}，点「加载模型」生效"
            },
            onSetDraft = {
                draftPath = file.absolutePath
                draftOn = true
                LocalSettings.draftModelPath = file.absolutePath
                LocalSettings.draftEnabled = true
                message = "已设为草稿模型：${file.name}"
            },
            onDelete = {
                LocalModelCatalog.delete(appContext, file)
                if (mainPath == file.absolutePath) {
                    mainPath = ""
                    LocalSettings.customModelPath = ""
                }
                if (draftPath == file.absolutePath) {
                    draftPath = ""
                    LocalSettings.draftModelPath = ""
                }
                reloadCatalogFiles()
                refreshCatalogStatus()
                message = "已删除：${file.name}"
            },
            onDismiss = { actionFile = null },
        )
    }
}

@Composable
private fun ModelFileActionDialog(
    file: File,
    isMain: Boolean,
    isDraft: Boolean,
    onSetMain: () -> Unit,
    onSetDraft: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val sizeMb = file.length() / 1024 / 1024
    EtaWindowDialog(
        show = true,
        title = file.name,
        summary = "${sizeMb}MB · .gguf 模型文件",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EtaTextButton(
                text = if (isMain) "✓ 当前主模型" else "设为主模型",
                enabled = !isMain,
                onClick = {
                    onSetMain()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            EtaTextButton(
                text = if (isDraft) "✓ 当前草稿模型" else "设为草稿模型（推测解码）",
                enabled = !isDraft,
                onClick = {
                    onSetDraft()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            EtaTextButton(
                text = if (confirmDelete) "再点一次确认删除" else "删除文件（释放 ${sizeMb}MB）",
                onClick = {
                    if (confirmDelete) {
                        onDelete()
                        onDismiss()
                    } else {
                        confirmDelete = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            EtaTextButton(
                text = "关闭",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun LocalEngineStatus.label(): String = when (this) {
    LocalEngineStatus.UNINITIALIZED -> "未初始化"
    LocalEngineStatus.READY_TO_LOAD -> "就绪（未加载模型）"
    LocalEngineStatus.LOADING -> "加载中"
    LocalEngineStatus.READY -> "已就绪"
    LocalEngineStatus.ERROR -> "异常"
}
