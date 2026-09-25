package cn.yangrq.weixuan.data.repository

import android.content.Context
import cn.yangrq.weixuan.data.datastore.SettingsDataStore
import cn.yangrq.weixuan.data.db.EtaDatabase
import cn.yangrq.weixuan.data.model.AnthropicProviderSetting
import cn.yangrq.weixuan.data.model.CustomHeader
import cn.yangrq.weixuan.data.model.OpenAiCompatibleProviderSetting
import cn.yangrq.weixuan.data.model.ModelSource
import cn.yangrq.weixuan.data.model.ProviderSetting
import cn.yangrq.weixuan.data.model.ReasoningEffort
import cn.yangrq.weixuan.data.provider.BuiltinProviders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProviderRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        runBlocking {
            SettingsDataStore.setSelection(providerId = null, modelId = null)
        }
    }

    @Test
    fun builtInProvidersRoundTripThroughRoomWithModels() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()

        val providers = ProviderRepository.allProviders().associateBy { it.id }

        assertTrue(providers.getValue(BuiltinProviders.ANTHROPIC_ID) is AnthropicProviderSetting)
        assertEquals(
            listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5"),
            providers.getValue(BuiltinProviders.OPENAI_ID).models.map { it.modelId },
        )
        assertEquals(
            List(4) { ModelSource.CATALOG },
            providers.getValue(BuiltinProviders.OPENAI_ID).models.map { it.source },
        )
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.DEFAULT,
                ReasoningEffort.MINIMAL,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
            ),
            providers.getValue(BuiltinProviders.OPENAI_ID)
                .models
                .first()
                .reasoningCapabilities
                ?.selectableEfforts,
        )
        assertEquals(
            listOf("claude-fable-5", "claude-opus-4-8", "claude-sonnet-5"),
            providers.getValue(BuiltinProviders.ANTHROPIC_ID).models.map { it.modelId },
        )
        assertEquals(
            listOf(
                "kimi-k3",
                "kimi-k2.7-code",
                "kimi-k2.7-code-highspeed",
                "kimi-k2.6",
                "kimi-k2.5",
            ),
            providers.getValue(BuiltinProviders.KIMI_ID).models.map { it.modelId },
        )
        assertTrue(
            providers.getValue(BuiltinProviders.BAILIAN_ID).models.none {
                it.modelId == "kimi-k3"
            }
        )
    }

    @Test
    fun providerAndModelCustomHeadersSurviveRoomRoundTrip() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val updated = provider.copyForTest(
            customHeaders = listOf(CustomHeader("x-provider", "1")),
        ).let { openAi ->
            openAi.copy(
                models = openAi.models.mapIndexed { index, model ->
                    if (index == 0) {
                        model.copy(customHeaders = listOf(CustomHeader("x-model", "2")))
                    } else {
                        model
                    }
                }
            )
        }

        ProviderRepository.updateProvider(updated)
        ModelRepository.saveModel(
            provider.id,
            updated.models.first(),
        )

        val restored = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        assertEquals(listOf("x-provider"), restored.customHeaders.map { it.name })
        assertEquals(listOf("x-model"), restored.models.first().customHeaders.map { it.name })
    }

    @Test
    fun selectedRuntimeConfigUsesUpdatedProviderApiKey() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = (ProviderRepository.providerById(BuiltinProviders.OPENAI_ID) as OpenAiCompatibleProviderSetting)
            .copy(apiKey = "sk-test-key")

        ProviderRepository.updateProvider(provider)
        RuntimeConfigRepository.setSelectedProviderId(provider.id)

        val config = RuntimeConfigRepository.currentRuntimeConfig()
        requireNotNull(config)
        assertEquals(provider.id, config.providerId)
        assertEquals("sk-test-key", config.apiKey)
    }

    @Test
    fun switchingProvidersRestoresEachProvidersSelectedModel() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val openAi = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val anthropic = ProviderRepository.providerById(BuiltinProviders.ANTHROPIC_ID)!!
        val openAiModel = openAi.models[1]
        val anthropicModel = anthropic.models[1]
        SettingsDataStore.clearSelectedModelIdForProvider(openAi.id)
        SettingsDataStore.clearSelectedModelIdForProvider(anthropic.id)

        RuntimeConfigRepository.setSelectedProviderId(openAi.id)
        RuntimeConfigRepository.setSelectedModelId(openAiModel.id)
        RuntimeConfigRepository.setSelectedProviderId(anthropic.id)
        RuntimeConfigRepository.setSelectedModelId(anthropicModel.id)

        RuntimeConfigRepository.setSelectedProviderId(openAi.id)
        assertEquals(openAiModel.id, SettingsDataStore.settings().selectedModelId)

        RuntimeConfigRepository.setSelectedProviderId(anthropic.id)
        assertEquals(anthropicModel.id, SettingsDataStore.settings().selectedModelId)
    }

    @Test
    fun repairSelectionMigratesLegacyActiveModelToProviderMemory() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val model = provider.models[1]
        SettingsDataStore.clearSelectedModelIdForProvider(provider.id)
        SettingsDataStore.updateSettings {
            it.copy(selectedProviderId = provider.id, selectedModelId = model.id)
        }

        ProviderRepository.repairSelection()

        assertEquals(model.id, SettingsDataStore.selectedModelIdForProvider(provider.id))
    }
}

private fun ProviderSetting.copyForTest(
    customHeaders: List<CustomHeader>,
): OpenAiCompatibleProviderSetting =
    (this as OpenAiCompatibleProviderSetting).copy(customHeaders = customHeaders)
