package cn.yangrq.weixuan.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object Home : AppRoute

    @Serializable
    data object Chat : AppRoute

    @Serializable
    data object Browser : AppRoute

    @Serializable
    data object Terminal : AppRoute

    @Serializable
    data object Tools : AppRoute

    @Serializable
    data object Skills : AppRoute

    @Serializable
    data object Characters : AppRoute

    @Serializable
    data class CharacterDetail(val characterId: String) : AppRoute

    @Serializable
    data class CharacterEditor(val characterId: String? = null) : AppRoute

    @Serializable
    data object CharacterPersona : AppRoute

    @Serializable
    data class CharacterMemory(val characterId: String) : AppRoute

    @Serializable
    data object Permissions : AppRoute

    @Serializable
    data object SystemEnhance : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object AppearanceSettings : AppRoute

    @Serializable
    data object DataBackup : AppRoute

    @Serializable
    data object Memory : AppRoute

    @Serializable
    data object LinuxEnvironment : AppRoute

    @Serializable
    data object SharedFolders : AppRoute

    @Serializable
    data object Workspace : AppRoute

    @Serializable
    data class LinuxFiles(val distribution: String) : AppRoute

    @Serializable
    data object ModelProviders : AppRoute

    @Serializable
    data object McpServers : AppRoute

    @Serializable
    data class McpServerDetail(val serverId: String) : AppRoute

    @Serializable
    data class ModelProviderDetail(val providerId: String) : AppRoute

    @Serializable
    data class ModelProviderNew(val providerType: NewProviderType) : AppRoute

    /** 本地模型（GenieX NPU/GPU）配置页。 */
    @Serializable
    data object LocalModel : AppRoute

    /**
     * 模型市场（2026-10-04 从 [LocalModel] 页剥离为独立页面）。
     *
     * 原先把「引擎状态 / 主模型设置 / 模型管理 / 模型市场」全塞在一页（742 行），
     * 既要配引擎又要挑模型。现在市场独立成页，从「设置 → 模型市场」进入。
     */
    @Serializable
    data object ModelMarket : AppRoute

    /**
     * 图标对比预览（爻线自绘 vs Tabler 底座），仅设计验收用。
     *
     * 入口：设置页 →「图标系统 → 图标对比预览」。
     */
    @Serializable
    data object IconPreview : AppRoute
}

@Serializable
enum class NewProviderType { OpenAiCompatible, Anthropic }
