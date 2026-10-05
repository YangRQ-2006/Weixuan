package cn.yangrq.weixuan.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/**
 * 模块配置中枢。
 *
 * - Hook 进程（system_server / SystemUI / Google / 系统助手等）在模块加载时调用
 *   [attachRemote]，缓存框架提供的只读 [SharedPreferences]；之后所有拦截回调用 [isEnabled]
 *   读取当前进程持有的 remote preferences。
 * - Eta Runtime 自己消费的开关保存在 App 私有配置中，不依赖 Xposed Service。
 * - Hook 消费的开关通过 [remotePreferencesForUi] 写入 RemotePreferences；
 *   XposedService 未就绪时不提供本地假 fallback。
 *
 * 基于 libxposed API 102 的 [io.github.libxposed.api.XposedInterface.getRemotePreferences]
 * 与 service 102 的 [XposedService.getRemotePreferences]，两端共用同一 group。
 */
internal object Prefs {

    /** 远程配置组名，UI 写入与 Hook 读取必须一致。 */
    const val GROUP = "eta_prefs"

    private const val LOCAL_AGENT_GROUP = "eta_agent_preferences"

    /** 所有功能开关 key。默认值按功能风险独立定义。 */
    object Keys {
        const val POWER_KEY_ASSISTANT_TARGET = "power_key_assistant_target"
        // 兼容旧版布尔协议；新 UI 不再写入，缺少三态配置时 true 仍表示 Gemini。
        const val POWER_KEY_TAKEOVER = "power_key_takeover"
        const val ASSISTANT_AUTO_CONFIG = "assistant_auto_config"
        const val HOTWORD_SELF_HEAL = "hotword_self_heal"
        const val GESTURE_BAR_CIRCLE_TO_SEARCH = "gesture_bar_circle_to_search"
        const val DOUBLE_FINGER_CIRCLE_TO_SEARCH = "double_finger_circle_to_search"
        const val LOCKSCREEN_VOICE_COMMAND = "lockscreen_voice_command"
        const val SCREEN_ON_VOICE_COMMAND = "screen_on_voice_command"
        const val AGENT_CUSTOM_MODEL = "agent_custom_model"
        const val AGENT_REQUIRE_PREFIX = "agent_require_prefix"
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"
        const val AGENT_BROWSER_TOOLS = "agent_browser_tools"
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"

        /**
         * 纯文本模式（免工具直答）：不向模型发送任何工具定义。
         *
         * 动机（2026-10-05 实测）：Agent prompt 实测 5140 token，其中约 4800 是工具 schema；
         * 带工具时首轮 prefill 要 9.05 秒、decode 也只有 7.17 t/s（5.1k 上下文）。
         * 关掉工具后 prompt 掉到几百 token，首字延迟与整体速度都显著改善，
         * 代价是助手不能再操作手机（纯聊天/问答）。
         */
        const val AGENT_PLAIN_TEXT = "agent_plain_text"

        /**
         * 工具按需发现（速度优先的工具模式）。
         *
         * 动机（2026-10-05 实测）：运行时 57 个工具常驻 prompt ≈ 5900 token，而 ctx 只有 6144
         * ⇒ decode 被长上下文拖到 4.2 t/s、首字还要付 ~8.8s 全量 prefill。
         * 开启后只常驻「核心工具 + 已发现工具 + find_tools」，其余工具在需要时通过 find_tools 现查现挂，
         * 常驻工具块降到约 1.5k token 量级 —— **能力不减**（任何工具都能被找到并调用），
         * 代价是冷门工具多一轮往返（+1~2s）。实现见 `AgentToolDiscovery`。
         */
        const val AGENT_TOOL_DISCOVERY = "agent_tool_discovery"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"

        /** 全部布尔开关及其默认值。 */
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            POWER_KEY_TAKEOVER to false,
            ASSISTANT_AUTO_CONFIG to false,
            HOTWORD_SELF_HEAL to false,
            GESTURE_BAR_CIRCLE_TO_SEARCH to true,
            DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
            LOCKSCREEN_VOICE_COMMAND to false,
            SCREEN_ON_VOICE_COMMAND to false,
            AGENT_CUSTOM_MODEL to true,
            AGENT_REQUIRE_PREFIX to false,
            AGENT_TERMINAL_TOOLS to true,
            AGENT_BROWSER_TOOLS to true,
            AGENT_DEVICE_DIRECT_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
            AGENT_THINKING_ENABLED to true,
            AGENT_PLAIN_TEXT to false,
            AGENT_TOOL_DISCOVERY to false
        )

        /** 由 Eta Runtime 最终裁决、不要求 Xposed 框架在线的开关。 */
        val LOCAL_AGENT_KEYS: Set<String> = setOf(
            AGENT_TERMINAL_TOOLS,
            AGENT_BROWSER_TOOLS,
            AGENT_DEVICE_DIRECT_TOOLS,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
            AGENT_THINKING_ENABLED,
            AGENT_PLAIN_TEXT,
            AGENT_TOOL_DISCOVERY,
        )
    }

    /** Hook 进程缓存的只读 remote preferences，由 ModuleMain 在 onModuleLoaded 注入。 */
    @Volatile
    private var remote: SharedPreferences? = null

    @Volatile
    private var localAgent: SharedPreferences? = null

    /** App 进程调用：初始化不依赖 Xposed Service 的 Agent 配置。 */
    fun initLocal(context: Context) {
        if (localAgent == null) {
            synchronized(this) {
                if (localAgent == null) {
                    localAgent = context.applicationContext.getSharedPreferences(
                        LOCAL_AGENT_GROUP,
                        Context.MODE_PRIVATE,
                    )
                }
            }
        }
    }

    /** Hook 进程调用：缓存框架提供的只读 SharedPreferences。 */
    fun attachRemote(prefs: SharedPreferences?) {
        remote = prefs
    }

    /** Hook 进程监听框架下发的配置变化；listener 必须由调用方在进程生命周期内强引用。 */
    fun registerRemoteListener(listener: SharedPreferences.OnSharedPreferenceChangeListener): Boolean {
        val preferences = remote ?: return false
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return true
    }

    /**
     * 读取布尔开关。remote 不可用（框架未注入或调用失败）时回退各功能自己的默认值；
     * 默认值与设置页展示保持一致。
     */
    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        val preferences = if (key in Keys.LOCAL_AGENT_KEYS) localAgent ?: remote else remote
        return preferences?.getBoolean(key, default) ?: default
    }

    fun getString(key: String): String {
        return remote?.getString(key, "") ?: ""
    }

    fun powerAssistantTarget(): PowerAssistantTarget = powerAssistantTarget(remote)

    fun powerAssistantTarget(preferences: SharedPreferences?): PowerAssistantTarget {
        val persistedValue = runCatching {
            preferences?.getString(Keys.POWER_KEY_ASSISTANT_TARGET, null)
        }.getOrNull()
        val legacyDefault = Keys.BOOLEAN_DEFAULTS.getValue(Keys.POWER_KEY_TAKEOVER)
        val legacyTakeover = runCatching {
            preferences?.getBoolean(Keys.POWER_KEY_TAKEOVER, legacyDefault)
        }.getOrNull() ?: legacyDefault
        return PowerAssistantTarget.resolve(persistedValue, legacyTakeover)
    }

    /**
     * UI 进程获取可写的 RemotePreferences。
     *
     * [XposedService.getRemotePreferences] 的 commit 会同步等待 binder 提交到 LSPosed
     * 数据库，失败返回 false；service 未就绪时返回 null，让 UI 保持不可写。
     */
    fun remotePreferencesForUi(service: XposedService?): SharedPreferences? =
        runCatching { service?.getRemotePreferences(GROUP) }.getOrNull()

    /** Eta 设置页与 Runtime 使用的本地 Agent 配置，不依赖 LSPosed。 */
    fun localAgentPreferences(): SharedPreferences? = localAgent

    /**
     * 首次升级优先把已有 RemotePreferences 值迁入本地；之后本地值是事实源，并在框架
     * 可用时回写远端，让仍在目标进程中组装请求的 Hook 入口拿到一致的初始配置。
     */
    fun reconcileAgentPreferences(service: XposedService?) {
        val local = localAgent ?: return
        val remotePreferences = remotePreferencesForUi(service) ?: return
        val localEditor = local.edit()
        val remoteEditor = remotePreferences.edit()
        var updateLocal = false
        var updateRemote = false

        Keys.LOCAL_AGENT_KEYS.forEach { key ->
            val default = Keys.BOOLEAN_DEFAULTS.getValue(key)
            when {
                local.contains(key) -> {
                    remoteEditor.putBoolean(key, local.getBoolean(key, default))
                    updateRemote = true
                }
                remotePreferences.contains(key) -> {
                    localEditor.putBoolean(key, remotePreferences.getBoolean(key, default))
                    updateLocal = true
                }
            }
        }
        if (updateLocal) localEditor.commit()
        if (updateRemote) runCatching { remoteEditor.commit() }
    }
}

/**
 * 「本地推理服务器」模式（2026-10-05）的偏好契约 —— **在此集中定义键名与默认值**。
 *
 * 为什么不并进 [Prefs]：`Prefs` 管的是 Xposed Hook 消费的 RemotePreferences（跨进程、由
 * LSPosed 下发，见 [Prefs.GROUP]），而服务器模式是 Eta Runtime 自己的本地设置（与其它
 * 本地模型设置一起存于 `eta_local_model` 文件，只由 App 进程读写）。两者生命周期与消费方
 * 完全不同，混在一起会把本地开关也拖进 Hook 的初始化链路。这里只声明**契约**（键名 + 默认值），
 * 实际读写由 [cn.yangrq.weixuan.local.LocalSettings] 完成，供 [cn.yangrq.weixuan.local.LlamaServerProcess]
 * 与「本地模型」设置页共同消费。
 *
 * ⚠️ **默认值即「与旧行为逐字节一致」的保证**：默认关闭（opt-in）、只绑 `127.0.0.1`、
 * 端口 `18787`、单槽 `-np 1`、无 API Key —— 正好等于 [cn.yangrq.weixuan.local.LlamaServerProcess]
 * 改造前写死的参数。任何一处默认值被改动都会改变升级用户的既有行为，务必同步更新此注释与
 * `docs/SERVER_MODE.md`。
 */
internal object LocalServerPrefs {
    /** 服务器模式总开关。**默认 false**：不开启时 llama-server 完全按旧参数启动。 */
    const val KEY_ENABLED = "local_server_enabled"

    /** 绑定范围。false=仅本机 127.0.0.1（默认）；true=绑 0.0.0.0（局域网可见）。 */
    const val KEY_LAN = "local_server_lan"

    /** 监听端口。默认 18787（= 改造前的写死端口，也是内置 Provider baseUrl 的端口）。 */
    const val KEY_PORT = "local_server_port"

    /** 并发槽数（llama.cpp `-np`）。默认 1（= 改造前的写死值）。 */
    const val KEY_SLOTS = "local_server_slots"

    /** 调用鉴权 key。默认空 = 不追加 `--api-key`（= 改造前的行为）。 */
    const val KEY_API_KEY = "local_server_api_key"

    const val DEFAULT_ENABLED = false
    const val DEFAULT_LAN = false
    const val DEFAULT_PORT = 18787
    const val DEFAULT_SLOTS = 1
    const val DEFAULT_API_KEY = ""

    /** `-np` 取值范围：多槽会按槽数倍增 KV cache（实测单槽 f16 KV ≈147KB/token），4 足够个人低频使用。 */
    const val MIN_SLOTS = 1
    const val MAX_SLOTS = 4

    /** 端口合法范围（避开特权端口与常见保留段）。 */
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    // ── 功耗与热保护（2026-10-05）────────────────────────────────────────────
    //
    // 动机：服务器模式与「聊天」不同 —— 它是**持续负载**（模型常驻内存 + 请求可能随时进来，
    // 甚至被同 WiFi 设备连续打），比有间隙的聊天更容易把 SoC 顶到高温、触发系统级热节流
    // 甚至热失控。因此给服务器模式配一个**热断路器**：温度过高就把本地推理服务暂停
    // （卸载模型 → 立刻止热），温度降下来再自动恢复。
    //
    // ★ 最高优先约束：**热保护只在服务器模式开启（local_server_enabled=true）时生效**。
    //   看门狗（见 LlamaServerProcess）在启动前先判 `LocalSettings.localServerEnabled`，
    //   未开启服务器模式的用户**根本不会启动任何轮询协程**，行为与今天一字节不变。
    //   开关默认 true 是为了「一开服务器模式就有保护」，而不是让未开启者也受影响。
    //
    // 迟滞（hysteresis）：暂停阈值(85) 与恢复阈值(70) 之间留出 15°C 的**死区**，
    // 避免温度在单一阈值附近抖动导致引擎「暂停→恢复→暂停」反复开关（每次开关都要
    // 重新加载 GB 级模型，代价极高）。必须满足 恢复阈值 < 暂停阈值。

    /** 热保护总开关（服务器模式专用）。默认 true，仅在 [KEY_ENABLED]=true 时被消费。 */
    const val KEY_THERMAL_GUARD = "local_server_thermal_guard"

    /** 暂停阈值（°C）：温度**高于**它即卸载模型止热。默认 85。 */
    const val KEY_TEMP_PAUSE_C = "local_server_temp_pause_c"

    /** 恢复阈值（°C）：暂停后温度**低于**它才重新拉起引擎。默认 70（必须 < 暂停阈值）。 */
    const val KEY_TEMP_RESUME_C = "local_server_temp_resume_c"

    const val DEFAULT_THERMAL_GUARD = true
    const val DEFAULT_TEMP_PAUSE_C = 85
    const val DEFAULT_TEMP_RESUME_C = 70

    /** 阈值合法范围（°C）：低于 40 会误伤日常使用，高于 120 无意义。 */
    const val MIN_TEMP_C = 40
    const val MAX_TEMP_C = 120

    /**
     * 热保护偏好的**读写实现**就地放在本对象里，而不是搬进
     * [cn.yangrq.weixuan.local.LocalSettings]。
     *
     * 原因：热保护只被「服务器模式看门狗」（LlamaServerProcess）与「本地推理服务器」设置页
     * 消费，把**契约**（键名 / 默认值）与这两个小访问器放在同一处最好维护；它们与
     * [cn.yangrq.weixuan.local.LocalSettings] **共用同一个 SharedPreferences 文件**
     * （`eta_local_model`，见 [LocalSettings.FILE]），因此任何入口读到/写到的都是同一份值
     * （同一进程内 getSharedPreferences 返回同一实例，无缓存不一致问题）。
     */
    private const val PREFS_FILE = "eta_local_model"

    private fun serverPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** 热保护是否开启（默认 true）。调用方须自行确认服务器模式已开启。 */
    fun thermalGuardEnabled(context: Context): Boolean =
        serverPrefs(context).getBoolean(KEY_THERMAL_GUARD, DEFAULT_THERMAL_GUARD)

    fun setThermalGuardEnabled(context: Context, enabled: Boolean) {
        serverPrefs(context).edit().putBoolean(KEY_THERMAL_GUARD, enabled).apply()
    }

    /** 暂停阈值，越界值回落到默认 85，避免非法值让断路器永不触发或误触发。 */
    fun tempPauseC(context: Context): Int =
        serverPrefs(context).getInt(KEY_TEMP_PAUSE_C, DEFAULT_TEMP_PAUSE_C)
            .coerceIn(MIN_TEMP_C, MAX_TEMP_C)

    fun setTempPauseC(context: Context, value: Int) {
        serverPrefs(context).edit()
            .putInt(KEY_TEMP_PAUSE_C, value.coerceIn(MIN_TEMP_C, MAX_TEMP_C)).apply()
    }

    /** 恢复阈值，越界值回落到默认 70。 */
    fun tempResumeC(context: Context): Int =
        serverPrefs(context).getInt(KEY_TEMP_RESUME_C, DEFAULT_TEMP_RESUME_C)
            .coerceIn(MIN_TEMP_C, MAX_TEMP_C)

    fun setTempResumeC(context: Context, value: Int) {
        serverPrefs(context).edit()
            .putInt(KEY_TEMP_RESUME_C, value.coerceIn(MIN_TEMP_C, MAX_TEMP_C)).apply()
    }
}
