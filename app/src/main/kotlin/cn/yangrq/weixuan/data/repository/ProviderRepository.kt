package cn.yangrq.weixuan.data.repository

import android.content.Context
import cn.yangrq.weixuan.data.datastore.SettingsDataStore
import cn.yangrq.weixuan.data.db.EtaDatabase
import cn.yangrq.weixuan.data.db.ProviderWithModelsSeed
import cn.yangrq.weixuan.data.db.toDomain
import cn.yangrq.weixuan.data.db.toEntity
import cn.yangrq.weixuan.data.db.toModelEntities
import cn.yangrq.weixuan.data.model.AnthropicProviderSetting
import cn.yangrq.weixuan.data.model.CustomProviderSetting
import cn.yangrq.weixuan.data.model.Model
import cn.yangrq.weixuan.data.model.OpenAiCompatibleProviderSetting
import cn.yangrq.weixuan.data.model.ProviderSetting
import cn.yangrq.weixuan.data.model.Settings
import cn.yangrq.weixuan.data.model.selectedOrFirstModel
import cn.yangrq.weixuan.data.model.withApiKey
import cn.yangrq.weixuan.data.model.withModels
import cn.yangrq.weixuan.data.model.withSortOrder
import cn.yangrq.weixuan.data.provider.BuiltinProviders
import cn.yangrq.weixuan.data.provider.OfficialModelCatalog
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal object ProviderRepository {
    @Volatile
    private lateinit var applicationContext: Context

    fun init(context: Context) {
        if (!::applicationContext.isInitialized) {
            applicationContext = context.applicationContext
        }
    }

    fun providersFlow(): Flow<List<ProviderSetting>> =
        dao().providersFlow().map { providers ->
            providers
                .map { it.toDomain() }
                .sortedBy(ProviderSetting::sortOrder)
        }

    fun settingsFlow(): Flow<Settings> =
        SettingsDataStore.settingsFlow()

    suspend fun settings(): Settings =
        SettingsDataStore.settings()

    suspend fun allProviders(): List<ProviderSetting> =
        dao().providers()
            .map { it.toDomain() }
            .sortedBy(ProviderSetting::sortOrder)

    suspend fun providerById(id: String): ProviderSetting? =
        dao().providerById(id)?.toDomain()

    suspend fun providerByModelId(modelId: String): ProviderSetting? =
        dao().providerByModelId(modelId)?.toDomain()

    suspend fun addProvider(provider: ProviderSetting): ProviderSetting {
        val nextOrder = (allProviders().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val added = provider.withSortOrder(nextOrder)
        replaceProvider(added)
        repairSelection()
        return added
    }

    suspend fun updateProvider(provider: ProviderSetting) {
        require(dao().updateProvider(provider.toEntity()) == 1) { "Provider 不存在" }
        repairSelection()
    }

    internal suspend fun replaceModels(providerId: String, models: List<Model>) {
        val provider = requireNotNull(providerById(providerId)) { "Provider 不存在" }
        dao().replaceModels(
            providerId = providerId,
            models = provider.withModels(models).toModelEntities(),
        )
    }

    suspend fun deleteProvider(id: String) {
        val provider = providerById(id) ?: return
        if (provider.isBuiltIn) return
        dao().deleteProvider(id)
        SettingsDataStore.clearSelectedModelIdForProvider(id)
        repairSelection()
    }

    suspend fun copyProvider(id: String): ProviderSetting? {
        val source = providerById(id) ?: return null
        val nextOrder = (allProviders().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val copy = source.deepCopy(
            id = newId(),
            name = "${source.name} 副本",
            sortOrder = nextOrder,
            builtIn = false,
        )
        replaceProvider(copy)
        repairSelection()
        return copy
    }

    suspend fun resetBuiltIn(id: String) {
        val builtIn = BuiltinProviders.providerById(id) ?: return
        val current = providerById(id)
        val restored = seedOfficialModelsIfEmpty(
            current
            ?.let { builtIn.withApiKey(it.apiKey).withSortOrder(it.sortOrder) }
            ?: builtIn
        )
        replaceProvider(restored)
        repairSelection()
    }

    /**
     * 微玄：彻底移除联网模型。
     *
     * 历史版本内置的云端 Provider（OpenAI / Anthropic / 百炼 / DeepSeek / Kimi / MiniMax …）
     * 以及用户自建的远程 Provider，统一在此清理；只保留指向本机回环的本地推理服务。
     */
    private suspend fun purgeRemoteProviders() {
        allProviders()
            .filterNot { provider -> isLoopbackBaseUrl(provider.baseUrl) }
            .forEach { provider -> runCatching { deleteProvider(provider.id) } }
    }

    private fun isLoopbackBaseUrl(baseUrl: String): Boolean {
        val normalized = baseUrl.trim().lowercase()
        return normalized.contains("127.0.0.1") ||
            normalized.contains("localhost") ||
            normalized.contains("[::1]")
    }

    suspend fun ensureBuiltInsMerged() {
        purgeRemoteProviders()
        // 清理迁移（2026-09-25「完全离线」产品化）：内置联网提供商已从 BuiltinProviders.PROVIDERS
        // 移除，此处同步删除 DB 中残留的历史内置记录（仅删内置项，用户自建提供商不受影响）。
        val keepIds = BuiltinProviders.PROVIDERS.map { it.id }.toSet()
        allProviders()
            .filter { it.isBuiltIn && it.id !in keepIds }
            .forEach { deleteProvider(it.id) }
        val current = allProviders()
        if (current.isEmpty()) {
            insertProviders(BuiltinProviders.PROVIDERS.map(::seedOfficialModelsIfEmpty))
            repairSelection()
            return
        }

        val existingIds = current.mapTo(mutableSetOf()) { it.id }
        val missing = BuiltinProviders.PROVIDERS.filterNot { it.id in existingIds }
        if (missing.isNotEmpty()) {
            insertProviders(missing.map(::seedOfficialModelsIfEmpty))
            repairSelection()
        } else {
            repairSelection()
        }
    }

    suspend fun repairSelection(): Settings {
        val providers = allProviders()
        val settings = SettingsDataStore.settings()
        val selectedProvider = providers.firstOrNull { it.id == settings.selectedProviderId && it.isEnabled }
            ?: providers.firstOrNull { it.isEnabled }
        val activeModel = selectedProvider
            ?.takeIf { it.id == settings.selectedProviderId }
            ?.models
            ?.firstOrNull { it.id == settings.selectedModelId && it.isEnabled }
        val rememberedModelId = selectedProvider?.let {
            SettingsDataStore.selectedModelIdForProvider(it.id)
        }
        val selectedModel = activeModel ?: selectedProvider?.selectedOrFirstModel(rememberedModelId)
        val repaired = settings.copy(
            selectedProviderId = selectedProvider?.id,
            selectedModelId = selectedModel?.id,
        )
        SettingsDataStore.setSelection(repaired.selectedProviderId, repaired.selectedModelId)
        return repaired
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun dao() =
        EtaDatabase.get(appContext()).providerDao()

    private fun appContext(): Context {
        check(::applicationContext.isInitialized) {
            "ProviderRepository.init(context) must be called in Application.onCreate()"
        }
        return applicationContext
    }

    private suspend fun replaceProvider(provider: ProviderSetting) {
        dao().replaceProvider(
            provider = provider.toEntity(),
            models = provider.toModelEntities(),
        )
    }

    private suspend fun insertProviders(providers: List<ProviderSetting>) {
        dao().insertProvidersWithModels(
            providers.map { provider ->
                ProviderWithModelsSeed(
                    provider = provider.toEntity(),
                    models = provider.toModelEntities(),
                )
            }
        )
    }

    private fun seedOfficialModelsIfEmpty(provider: ProviderSetting): ProviderSetting {
        if (provider.models.isNotEmpty()) return provider
        val seededModels = OfficialModelCatalog.modelsForProvider(provider)
        return if (seededModels.isEmpty()) provider else provider.withModels(seededModels)
    }

    private fun ProviderSetting.deepCopy(
        id: String,
        name: String,
        sortOrder: Int,
        builtIn: Boolean,
    ): ProviderSetting {
        val copiedModels = models.mapIndexed { index, model ->
            model.copy(id = newId(), isBuiltIn = builtIn, sortOrder = index)
        }
        return when (this) {
            is OpenAiCompatibleProviderSetting -> copy(
                id = id,
                name = name,
                sortOrder = sortOrder,
                isBuiltIn = builtIn,
                models = copiedModels,
            )

            is AnthropicProviderSetting -> copy(
                id = id,
                name = name,
                sortOrder = sortOrder,
                isBuiltIn = builtIn,
                models = copiedModels,
            )

            is CustomProviderSetting -> copy(
                id = id,
                name = name,
                sortOrder = sortOrder,
                isBuiltIn = builtIn,
                models = copiedModels,
            )
        }
    }
}
