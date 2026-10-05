package cn.yangrq.weixuan.ui.pages.local

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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.provider.Settings
import cn.yangrq.weixuan.config.LocalServerPrefs
import cn.yangrq.weixuan.local.GenieXLocalEngine
import cn.yangrq.weixuan.local.LlamaServerProcess
import cn.yangrq.weixuan.local.LocalEngineStatus
import cn.yangrq.weixuan.local.LocalResourceGuard
import cn.yangrq.weixuan.local.LocalServerHost
import cn.yangrq.weixuan.local.LocalServerKeepAlive
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
import cn.yangrq.weixuan.ui.design.XuanGlyph
import cn.yangrq.weixuan.ui.design.XuanGlyphType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * **本地推理服务器**（2026-10-05 从 [LocalModelScreen] 剥离为独立二级设置页）。
 *
 * 剥离理由：上一轮把「把手机当成 OpenAI 兼容服务器」的配置塞在「本地模型」页里，
 * 用户反馈"藏得太深"——要配引擎的人本来就要在一页里同时面对主/草稿模型、后端口味、
 * 市场入口、模型管理，再叠一层服务器小节能找到才怪。现在服务器模式单独成页，
 * 从「设置 → 本地推理服务器」这个显著位置进入。
 *
 * 页面分节：
 * 1. 【顶部】服务器状态卡：开关态 / 实际监听地址 / 当前槽数 / 是否需要 API Key；
 * 2. 【连接设置】开关 · 绑定范围 · 端口 · 并发槽数 · API Key；
 * 3. 【怎么接入】可直接复制的 `base_url`、模型名说明、一行自检 `curl`；
 * 4. 【能力边界】如实告知 llama.cpp 的单/少槽特性（`-np 4` ≠ 4 倍吞吐）；
 * 5. 【安全】仅本机零暴露 / 开局域网必须配 Key / 同 WiFi 可烧电提醒。
 *
 * 图标全部使用项目自有的 [XuanGlyph]（[XuanGlyphType]），**不引入 material-icons-extended**。
 */
@Composable
internal fun LocalServerScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val engineState by GenieXLocalEngine.state.collectAsState()

    // ── 服务器设置（读自 LocalSettings，默认值见 config/LocalServerPrefs）──
    var srvEnabled by remember { mutableStateOf(LocalSettings.localServerEnabled) }
    var srvLan by remember { mutableStateOf(LocalSettings.localServerLan) }
    var srvPort by remember { mutableStateOf(LocalSettings.localServerPort) }
    var srvSlots by remember { mutableStateOf(LocalSettings.localServerSlots) }
    var srvApiKey by remember { mutableStateOf(LocalSettings.localServerApiKey) }

    // ── 功耗与热保护（服务器模式专用，2026-10-05）────────────────────────
    // 偏好键/默认值见 config/LocalServerPrefs；读写走同对象里的小访问器
    //（与 LocalSettings 共用 eta_local_model 文件，因此读到的就是同一份值）。
    val context = LocalContext.current
    var srvThermalGuard by remember { mutableStateOf(LocalServerPrefs.thermalGuardEnabled(context)) }
    var srvTempPause by remember { mutableStateOf(LocalServerPrefs.tempPauseC(context)) }
    var srvTempResume by remember { mutableStateOf(LocalServerPrefs.tempResumeC(context)) }
    var socTempC by remember { mutableStateOf(0f) }
    var tempEditDialog by remember { mutableStateOf(0) } // 0=关闭；1=暂停阈值；2=恢复阈值
    var tempDraft by remember { mutableStateOf("") }
    // 断路器状态由 LlamaServerProcess 的看门狗维护（正常 / 已因高温暂停）
    val breakerState by LlamaServerProcess.thermalGuardStateFlow.collectAsState()

    // ── 保活状态（2026-10-05）：前台服务 / 唤醒锁 / 看门狗是否活跃、root 加固是否施加 ──
    val keepAlive by LocalServerKeepAlive.status.collectAsState()
    LaunchedEffect(Unit) { LocalServerKeepAlive.refresh() }

    // 实时温度：直接读 thermal zone（与看门狗同源），页面在前台时每 5 秒刷新一次
    LaunchedEffect(srvEnabled) {
        while (true) {
            socTempC = runCatching { LocalResourceGuard().maxTemperatureC() }.getOrDefault(0f)
            delay(5_000)
        }
    }

    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    var portDialog by remember { mutableStateOf(false) }
    var portDraft by remember { mutableStateOf(LocalSettings.localServerPort.toString()) }
    var apiKeyDialog by remember { mutableStateOf(false) }

    // 派生展示值：实际监听 host/port、base_url、是否已启动、是否需要 Key
    val listenHost = if (srvEnabled && srvLan) "0.0.0.0" else "127.0.0.1"
    val localBaseUrl = "http://127.0.0.1:$srvPort/v1"
    val lanBaseUrl = "http://<手机内网IP>:$srvPort/v1"
    val needApiKey = srvEnabled && srvLan
    val serverRunning = LocalServerHost.isRunning || engineState.status == LocalEngineStatus.READY

    /** 服务器设置改动后：若引擎在跑，提示需重启才生效（llama-server 启动参数已固定）。 */
    fun markServerSettingChanged() {
        if (engineState.status == LocalEngineStatus.READY) {
            message = "服务器设置已保存：需重启引擎后生效（可点「重启引擎应用设置」）"
        }
    }

    /** 一键把并发槽数升到指定值（槽位优化动作）。 */
    fun raiseSlots(target: Int) {
        val value = target.coerceIn(LocalServerPrefs.MIN_SLOTS, LocalServerPrefs.MAX_SLOTS)
        srvSlots = value
        LocalSettings.localServerSlots = value
        message = "并发槽数已升到 $value 槽（KV 内存随槽数翻倍），重启引擎后生效"
        markServerSettingChanged()
    }

    fun copyToClipboard(text: String, label: String) {
        clipboardManager.setText(AnnotatedString(text))
        message = "$label 已复制到剪贴板"
    }

    MiuixScaffoldPage(title = "本地推理服务器", onBack = onBack) {
        // ══ ① 顶部：服务器状态卡 ═══════════════════════════════════════
        item {
            EtaPreferenceGroupTitle("服务器状态")
        }
        item {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = if (srvEnabled) "已开启" else "已关闭",
                    summary = if (srvEnabled) {
                        "llama-server 按下方设置对外监听；改设置需重启引擎生效"
                    } else {
                        "未开启：实际仍按默认参数启动（仅本机 127.0.0.1:18787、单槽、不鉴权）"
                    },
                    startAction = {
                        EtaPreferenceIcon(
                            glyph = XuanGlyphType.Globe,
                            tint = if (srvEnabled) EtaPreferenceColors.Green else EtaPreferenceColors.Blue,
                        )
                    },
                    onClick = {},
                )
                EtaPreferenceDivider()
                StatusText(
                    label = "实际监听地址",
                    value = if (srvEnabled) "http://$listenHost:$srvPort/v1" else "http://127.0.0.1:18787/v1（默认）",
                )
                StatusText(label = "当前并发槽数", value = "$srvSlots 槽")
                StatusText(
                    label = "是否需要 API Key",
                    value = when {
                        needApiKey -> "需要（局域网模式，必配）"
                        srvEnabled -> "不需要（仅本机）"
                        else -> "不需要（未开启）"
                    },
                )
                StatusText(
                    label = "引擎状态",
                    value = engineState.status.serverStatusLabel() +
                        (engineState.modelName.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""),
                )
                if (message.isNotBlank()) {
                    EtaPreferenceDivider()
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote1,
                        color = EtaPreferenceColors.Orange,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // ══ ② 连接设置 ═════════════════════════════════════════════════
        item {
            EtaPreferenceGroupTitle("连接设置")
        }
        item {
            EtaPreferenceGroup {
                EtaSwitchPreference(
                    title = "服务器模式",
                    summary = if (srvEnabled) {
                        "已开启：把手机当作 OpenAI 兼容的私有推理服务器（默认仅本机，数据不出设备）"
                    } else {
                        "关闭：行为与默认一致（仅本机 127.0.0.1:18787、单槽、不鉴权）"
                    },
                    checked = srvEnabled,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Globe, tint = EtaPreferenceColors.Blue)
                    },
                    onCheckedChange = { enabled ->
                        srvEnabled = enabled
                        LocalSettings.localServerEnabled = enabled
                        if (enabled && srvLan && LocalSettings.localServerApiKey.isBlank()) {
                            srvApiKey = LocalSettings.ensureLocalServerApiKey()
                            message = "已开启局域网：自动生成了 API Key（同 WiFi 设备调用需携带）"
                        }
                        markServerSettingChanged()
                    },
                )
                EtaPreferenceDivider()
                EtaDropdownPreference(
                    title = "绑定范围",
                    summary = if (srvLan) {
                        "局域网可见：同 WiFi 设备可访问 0.0.0.0（必须配 API Key）"
                    } else {
                        "仅本机：只有本机 App 能访问 127.0.0.1（默认，最安全）"
                    },
                    items = listOf(
                        DropdownItem(text = "仅本机 127.0.0.1"),
                        DropdownItem(text = "局域网 0.0.0.0"),
                    ),
                    selectedIndex = if (srvLan) 1 else 0,
                    enabled = srvEnabled,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Wifi, tint = EtaPreferenceColors.Blue)
                    },
                    onSelectedIndexChange = { index ->
                        val lan = index == 1
                        srvLan = lan
                        LocalSettings.localServerLan = lan
                        if (lan && srvEnabled && LocalSettings.localServerApiKey.isBlank()) {
                            srvApiKey = LocalSettings.ensureLocalServerApiKey()
                            message = "已开启局域网：自动生成了 API Key（同 WiFi 设备调用需携带）"
                        }
                        markServerSettingChanged()
                    },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = "端口",
                    summary = "$srvPort（默认 18787，范围 ${LocalServerPrefs.MIN_PORT}-${LocalServerPrefs.MAX_PORT}）",
                    enabled = srvEnabled,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Swap, tint = EtaPreferenceColors.Blue)
                    },
                    onClick = {
                        portDraft = srvPort.toString()
                        portDialog = true
                    },
                )
                EtaPreferenceDivider()
                EtaDropdownPreference(
                    title = "并发槽数（-np）",
                    summary = if (srvSlots > 1) {
                        "多槽并发：每槽 KV 内存与解码开销倍增，总吞吐并不会线性翻倍"
                    } else {
                        "单槽：个人低频使用推荐（第二个客户端会排队等待）"
                    },
                    items = (LocalServerPrefs.MIN_SLOTS..LocalServerPrefs.MAX_SLOTS).map {
                        DropdownItem(text = "$it 槽")
                    },
                    selectedIndex = (srvSlots - LocalServerPrefs.MIN_SLOTS)
                        .coerceIn(0, LocalServerPrefs.MAX_SLOTS - LocalServerPrefs.MIN_SLOTS),
                    enabled = srvEnabled,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Device, tint = EtaPreferenceColors.Blue)
                    },
                    onSelectedIndexChange = { index ->
                        val value = (index + LocalServerPrefs.MIN_SLOTS)
                            .coerceIn(LocalServerPrefs.MIN_SLOTS, LocalServerPrefs.MAX_SLOTS)
                        srvSlots = value
                        LocalSettings.localServerSlots = value
                        markServerSettingChanged()
                    },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = "API Key",
                    summary = if (srvApiKey.isBlank()) {
                        "未设置（仅本机时可不设；开局域网会自动生成）"
                    } else {
                        maskServerApiKey(srvApiKey)
                    },
                    enabled = srvEnabled,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Clipboard, tint = EtaPreferenceColors.Orange)
                    },
                    onClick = { apiKeyDialog = true },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = "重启引擎应用设置",
                    summary = "改端口 / 绑定范围 / 槽数 / Key 后，需重启 llama-server 才会生效",
                    enabled = engineState.status == LocalEngineStatus.READY,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Refresh, tint = EtaPreferenceColors.Green)
                    },
                    onClick = {
                        scope.launch {
                            busy = true
                            message = "正在重启引擎…"
                            GenieXLocalEngine.unload()
                            val result = GenieXLocalEngine.loadModel()
                            message = result.exceptionOrNull()?.message
                                ?: "引擎已重启，服务器设置已生效"
                            busy = false
                        }
                    },
                )
            }
        }

        // ══ ②′ 功耗与热保护 ═════════════════════════════════════════════
        item {
            EtaPreferenceGroupTitle("功耗与热保护")
        }
        item {
            EtaPreferenceGroup {
                EtaSwitchPreference(
                    title = "高温自动暂停（热保护）",
                    summary = when {
                        !srvEnabled -> "服务器模式未开启时不生效（热保护只保护持续负载的服务器模式）"
                        srvThermalGuard ->
                            "已开启：SoC 温度 > ${srvTempPause}°C 自动卸载模型止热，" +
                                "降到 ${srvTempResume}°C 以下自动恢复"
                        else -> "已关闭：过热时不暂停（不建议——服务器是持续负载，比聊天更容易过热）"
                    },
                    checked = srvThermalGuard,
                    enabled = srvEnabled,
                    startAction = {
                        EtaPreferenceIcon(
                            glyph = XuanGlyphType.Pulse,
                            tint = if (srvThermalGuard && srvEnabled) {
                                EtaPreferenceColors.Green
                            } else {
                                EtaPreferenceColors.Orange
                            },
                        )
                    },
                    onCheckedChange = { enabled ->
                        srvThermalGuard = enabled
                        LocalServerPrefs.setThermalGuardEnabled(context, enabled)
                        message = if (enabled) "已开启热保护：高温自动暂停，降温自动恢复" else "已关闭热保护"
                    },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = "暂停阈值",
                    summary = "${srvTempPause}°C（默认 85）：温度高于它即卸载模型止热",
                    enabled = srvEnabled && srvThermalGuard,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Stop, tint = EtaPreferenceColors.Orange)
                    },
                    onClick = {
                        tempDraft = srvTempPause.toString()
                        tempEditDialog = 1
                    },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = "恢复阈值",
                    summary = "${srvTempResume}°C（默认 70）：暂停后低于它才重新拉起引擎（须 < 暂停阈值）",
                    enabled = srvEnabled && srvThermalGuard,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Play, tint = EtaPreferenceColors.Green)
                    },
                    onClick = {
                        tempDraft = srvTempResume.toString()
                        tempEditDialog = 2
                    },
                )
                EtaPreferenceDivider()
                StatusText(
                    label = "当前 SoC 温度",
                    value = if (socTempC > 0f) "${socTempC.toInt()}°C" else "读取中 / 无法读取",
                )
                StatusText(
                    label = "断路器状态",
                    value = when {
                        !srvEnabled -> "未启用（服务器模式关闭）"
                        breakerState == LlamaServerProcess.ThermalGuardState.PAUSED_HOT -> "已暂停（因高温）"
                        else -> "正常"
                    },
                )
                EtaPreferenceDivider(hasLeading = false)
                Text(
                    text = "服务器模式是持续负载：模型常驻内存 + 请求随时可能进来（含局域网设备连续调用），" +
                        "比有间隙的聊天更容易把 SoC 顶到高温。默认 85°C 暂停 / 70°C 恢复（中间留 15°C 死区，" +
                        "避免在阈值附近反复开关引擎）。温度读取失败时**不会**暂停（宁可不保护，也不误停服务）。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // ══ ②″ 保活（后台存活）═══════════════════════════════════════════
        item {
            EtaPreferenceGroupTitle("保活（后台存活）")
        }
        item {
            EtaPreferenceGroup {
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = "关闭电池优化（推荐）",
                    summary = "打开系统「电池优化」设置，把「微玄」设为不优化 / 无限制；" +
                        "否则后台可能被系统冻结，服务器会时断时续",
                    enabled = srvEnabled,
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Bell, tint = EtaPreferenceColors.Blue)
                    },
                    onClick = {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        val launched = runCatching { context.startActivity(intent) }.isSuccess
                        message = if (launched) {
                            "已打开电池优化设置：请把「微玄」改为「不优化 / 无限制」"
                        } else {
                            "无法直接打开该页面，请手动到 系统设置 → 电池 → 电池优化 中关闭微玄的优化"
                        }
                    },
                )
                EtaPreferenceDivider()
                StatusText(
                    label = "前台服务",
                    value = when {
                        !srvEnabled -> "未启用（服务器模式关闭）"
                        keepAlive.serviceActive -> "已运行（常驻通知）"
                        else -> "未运行"
                    },
                )
                StatusText(
                    label = "唤醒锁",
                    value = when {
                        !srvEnabled -> "未启用（服务器模式关闭）"
                        keepAlive.wakeLockHeld -> "已持有（仅引擎运行期间，1 小时自动续期）"
                        else -> "未持有"
                    },
                )
                StatusText(
                    label = "看门狗",
                    value = when {
                        !srvEnabled -> "未启用（服务器模式关闭）"
                        keepAlive.watchdogActive -> "运行中（~20 秒巡检一次）"
                        else -> "未运行"
                    },
                )
                StatusText(
                    label = "自动拉起引擎",
                    value = "${keepAlive.restartCount} 次" +
                        if (keepAlive.gaveUp) "（连续失败已放弃，请手动检查）" else "",
                )
                StatusText(
                    label = "root 加固",
                    value = when {
                        keepAlive.rootApplied -> "已施加（${keepAlive.rootDetail}）"
                        else -> "未施加（无 root 或未授权；无 root 保活由前台服务 + 看门狗承担）"
                    },
                )
                EtaPreferenceDivider(hasLeading = false)
                Text(
                    text = "服务器模式开启时：前台服务常驻 + 引擎在跑时才持唤醒锁（引擎停止立即释放，" +
                        "且最长 1 小时自动到期，不做无条件 24/7 持锁——那会持续耗电发热）；" +
                        "看门狗被系统杀掉后自动拉起（指数退避，连续失败 5 次后停止并通知）。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                EtaPreferenceDivider(hasLeading = false)
                Text(
                    text = "小米 / 澎湃（HyperOS）需手动设置（系统限制，应用无法自动完成）：\n" +
                        "① 设置 → 应用设置 → 应用管理 → 微玄 → 省电策略 → 无限制；\n" +
                        "② 同页 → 自启动 → 允许；\n" +
                        "③ 最近任务卡片下拉「加锁」，避免一键清理误杀；\n" +
                        "④ 设置 → 电池 → 应用智能省电 → 微玄 → 无限制。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // ── 槽位优化：单槽告警 + 一键升到 2 ─────────────────────────────
        if (srvEnabled && srvSlots == 1) {
            item {
                EtaPreferenceGroupTitle("槽位优化建议")
            }
            item {
                EtaPreferenceGroup {
                    Text(
                        text = "⚠️ 单槽：第二个客户端会排队等待（实测第二个客户端打 /v1/models 会拿到空返回）。" +
                            "若你有同机多 App / 局域网多设备同时调用，建议升到 2~4 槽。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = EtaPreferenceColors.Orange,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = "一键升到 2 槽",
                        summary = "缓解排队；代价是 KV 内存约翻倍（4B 单流速度也会随并发下降），重启引擎后生效",
                        startAction = {
                            EtaPreferenceIcon(glyph = XuanGlyphType.Plus, tint = EtaPreferenceColors.Green)
                        },
                        onClick = { raiseSlots(2) },
                    )
                }
            }
        }
        if (srvEnabled && srvSlots > 1) {
            item {
                Text(
                    text = "当前 $srvSlots 槽：KV cache 内存按槽数约翻倍（`-np 4` ≠ 4 倍吞吐），" +
                        "多请求并发时每槽速度明显下降；内存吃紧时请调回 1 槽。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // ══ ③ 怎么接入（最实用）════════════════════════════════════════
        item {
            EtaPreferenceGroupTitle("怎么接入")
        }
        item {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = "复制本机 base_url",
                    summary = localBaseUrl + "（同一台手机上的其它 App 用它）",
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Clipboard, tint = EtaPreferenceColors.Blue)
                    },
                    onClick = { copyToClipboard(localBaseUrl, "本机 base_url") },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = "复制局域网 base_url",
                    summary = lanBaseUrl + "（另一台设备/电脑；<手机内网IP> 换成手机在设置→关于手机→状态信息里的 192.168.x.x）",
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Clipboard, tint = EtaPreferenceColors.Orange)
                    },
                    onClick = { copyToClipboard(lanBaseUrl, "局域网 base_url") },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = "复制自检命令",
                    summary = "curl -s http://127.0.0.1:$srvPort/v1/models　（确认服务在跑、拿到模型名 id）",
                    startAction = {
                        EtaPreferenceIcon(glyph = XuanGlyphType.Terminal, tint = EtaPreferenceColors.Green)
                    },
                    onClick = {
                        copyToClipboard(
                            "curl -s http://127.0.0.1:$srvPort/v1/models",
                            "自检命令",
                        )
                    },
                )
                EtaPreferenceDivider(hasLeading = false)
                Text(
                    text = "模型名：llama-server 一次只加载一个模型，模型名以 GET /v1/models 返回的 id 为准" +
                        "（通常是所加载 .gguf 的基名）——先用上面自检命令确认，再把 id 填进客户端。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    text = "完整接入示例（含 Authorization 头）见 docs/SERVER_MODE.md。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        // ══ ④ 能力边界（如实告知）══════════════════════════════════════
        item {
            EtaPreferenceGroupTitle("能力边界（如实告知）")
        }
        item {
            EtaPreferenceGroup {
                Text(
                    text = "· llama.cpp 是单/少槽服务，不是高并发后端：`-np 4` 不等于 4 倍吞吐。\n" +
                        "· 4B 量化模型单流约 9~17 tok/s（随上下文长度、参数波动）。\n" +
                        "· 多槽并发时每个槽的速度都会明显下降（共享同一份权重与算力），KV 内存按槽数倍增。\n" +
                        "· 适合：个人/低频调用、局域网内自己或家人偶尔用、同机其它 App 提供私有接口。\n" +
                        "· 不适合：多人高强度同时调用、需要稳定 QPS 的服务器级负载。\n" +
                        "· 冷启动要加载模型（数秒~数十秒），长 prompt 的 prefill 也需时间。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // ══ ⑤ 安全 ═════════════════════════════════════════════════════
        item {
            EtaPreferenceGroupTitle("安全")
        }
        item {
            EtaPreferenceGroup {
                Text(
                    text = "· 仅本机（127.0.0.1）时对外零暴露，外部一律访问不到。\n" +
                        "· 开局域网必须配 API Key：绑 0.0.0.0 后同一个 WiFi 下任何人都能访问你的端口，" +
                        "不设 Key 等于把手机算力/电量/模型敞开门（设置页会强制/自动生成 Key）。\n" +
                        "· 局域网 = 同 WiFi 下任何人可烧你的电，用完记得关开关或调回「仅本机」。\n" +
                        "· API Key 等同密码：不要贴到公开处；分享给他人后可随时「重新生成」。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }

    // ── 温度阈值编辑弹窗（暂停 / 恢复共用） ──────────────────────────────
    if (tempEditDialog != 0) {
        val isPause = tempEditDialog == 1
        EtaWindowDialog(
            show = true,
            title = if (isPause) "暂停阈值" else "恢复阈值",
            summary = "范围 ${LocalServerPrefs.MIN_TEMP_C}-${LocalServerPrefs.MAX_TEMP_C}°C；" +
                "恢复阈值必须小于暂停阈值（迟滞死区）",
            onDismissRequest = { tempEditDialog = 0 },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = tempDraft,
                    onValueChange = { input -> tempDraft = input.filter { it.isDigit() }.take(3) },
                    label = "温度（°C）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                EtaTextButton(
                    text = "保存",
                    onClick = {
                        val value = tempDraft.toIntOrNull()
                        when {
                            value == null ||
                                value !in LocalServerPrefs.MIN_TEMP_C..LocalServerPrefs.MAX_TEMP_C ->
                                message = "温度不合法：需在 " +
                                    "${LocalServerPrefs.MIN_TEMP_C}-${LocalServerPrefs.MAX_TEMP_C}°C 之间"
                            isPause && value <= srvTempResume ->
                                message = "暂停阈值必须高于恢复阈值（当前恢复 = ${srvTempResume}°C）"
                            !isPause && value >= srvTempPause ->
                                message = "恢复阈值必须低于暂停阈值（当前暂停 = ${srvTempPause}°C）"
                            else -> {
                                if (isPause) {
                                    srvTempPause = value
                                    LocalServerPrefs.setTempPauseC(context, value)
                                } else {
                                    srvTempResume = value
                                    LocalServerPrefs.setTempResumeC(context, value)
                                }
                                message = if (isPause) {
                                    "暂停阈值已设为 ${value}°C"
                                } else {
                                    "恢复阈值已设为 ${value}°C"
                                }
                                tempEditDialog = 0
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                EtaTextButton(
                    text = "取消",
                    onClick = { tempEditDialog = 0 },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // ── 端口编辑弹窗 ────────────────────────────────────────────────────
    if (portDialog) {
        EtaWindowDialog(
            show = true,
            title = "监听端口",
            summary = "范围 ${LocalServerPrefs.MIN_PORT}-${LocalServerPrefs.MAX_PORT}，默认 18787",
            onDismissRequest = { portDialog = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = portDraft,
                    onValueChange = { input -> portDraft = input.filter { it.isDigit() }.take(5) },
                    label = "端口",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                EtaTextButton(
                    text = "保存",
                    onClick = {
                        val value = portDraft.toIntOrNull()
                        if (value != null &&
                            value in LocalServerPrefs.MIN_PORT..LocalServerPrefs.MAX_PORT
                        ) {
                            srvPort = value
                            LocalSettings.localServerPort = value
                            message = "端口已设为 $value，重启引擎后生效"
                            markServerSettingChanged()
                            portDialog = false
                        } else {
                            message = "端口不合法：需在 " +
                                "${LocalServerPrefs.MIN_PORT}-${LocalServerPrefs.MAX_PORT} 之间"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                EtaTextButton(
                    text = "取消",
                    onClick = { portDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // ── API Key 管理弹窗 ─────────────────────────────────────────────────
    if (apiKeyDialog) {
        EtaWindowDialog(
            show = true,
            title = "API Key",
            summary = "客户端在 Authorization=[REDACTED] <key> 中携带",
            onDismissRequest = { apiKeyDialog = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (srvApiKey.isBlank()) {
                        "尚未设置（开局域网会自动生成一个）"
                    } else {
                        srvApiKey
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
                EtaTextButton(
                    text = if (srvApiKey.isBlank()) "一键生成随机 Key" else "重新生成",
                    onClick = {
                        srvApiKey = LocalSettings.generateLocalServerApiKey()
                        message = "已生成新的 API Key，重启引擎后生效"
                        markServerSettingChanged()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                EtaTextButton(
                    text = "复制到剪贴板",
                    enabled = srvApiKey.isNotBlank(),
                    onClick = { copyToClipboard(srvApiKey, "API Key") },
                    modifier = Modifier.fillMaxWidth(),
                )
                EtaTextButton(
                    text = "清空（局域网下会强制重新生成）",
                    enabled = srvApiKey.isNotBlank() && !srvLan,
                    onClick = {
                        srvApiKey = ""
                        LocalSettings.localServerApiKey = ""
                        message = "已清空 API Key"
                        markServerSettingChanged()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                EtaTextButton(
                    text = "关闭",
                    onClick = { apiKeyDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 状态卡里的一行「标签：值」，值与标签同色系、值用弱化色，避免喧宾夺主。 */
@Composable
private fun StatusText(label: String, value: String) {
    Text(
        text = "$label：$value",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** API Key 展示掩码：只露头尾各 4 位，避免设置页明文长串外露。 */
private fun maskServerApiKey(key: String): String =
    if (key.length <= 10) "••••••" else "${key.take(4)}…${key.takeLast(4)}"

/** 引擎状态中文标签（本页自持一份，避免依赖其它文件的 file-private 扩展函数）。 */
private fun LocalEngineStatus.serverStatusLabel(): String = when (this) {
    LocalEngineStatus.UNINITIALIZED -> "未初始化"
    LocalEngineStatus.READY_TO_LOAD -> "就绪（未加载模型）"
    LocalEngineStatus.LOADING -> "加载中"
    LocalEngineStatus.READY -> "已就绪"
    LocalEngineStatus.ERROR -> "异常"
}
