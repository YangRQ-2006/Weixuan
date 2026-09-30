package cn.yangrq.weixuan.ui.pages.providers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.yangrq.weixuan.EtaApp
import cn.yangrq.weixuan.R
import cn.yangrq.weixuan.ui.components.EtaPreferenceRow
import cn.yangrq.weixuan.data.model.ProviderSetting
import cn.yangrq.weixuan.data.model.ProviderSourceTypes
import cn.yangrq.weixuan.data.model.typeLabel
import cn.yangrq.weixuan.data.repository.ProviderRepository
import cn.yangrq.weixuan.data.repository.RuntimeConfigRepository
import cn.yangrq.weixuan.ui.components.EtaArrowPreference
import cn.yangrq.weixuan.ui.components.EtaOverlayDialog
import cn.yangrq.weixuan.ui.components.MiuixDialogActions
import cn.yangrq.weixuan.ui.components.MiuixScaffoldPage
import cn.yangrq.weixuan.ui.navigation.AppRoute
import cn.yangrq.weixuan.ui.navigation.NewProviderType
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import cn.yangrq.weixuan.ui.design.XuanGlyph
import cn.yangrq.weixuan.ui.design.XuanGlyphType

@Composable
internal fun ModelProviderListScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    var searchQuery by remember { mutableStateOf("") }
    var providerToDelete by remember { mutableStateOf<ProviderSetting?>(null) }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    val filteredProviders = remember(providers, searchQuery) {
        val query = searchQuery.trim()
        providers.filter { provider ->
            query.isBlank() ||
                provider.name.contains(query, ignoreCase = true) ||
                provider.baseUrl.contains(query, ignoreCase = true) ||
                provider.typeLabel.contains(query, ignoreCase = true)
        }
    }

    MiuixScaffoldPage(title = stringResource(R.string.ui_model_provider_e8c7f5), onBack = onBack) {
        item(key = "search") {
            InputField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.ui_search_provider_74e049),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = 8.dp),
            )
        }

        item(key = "local_section") {
            ProviderSection(title = "本地模型") {
                EtaArrowPreference(
                    title = "本地模型（GenieX NPU）",
                    summary = "下载 / 导入 .gguf、加载与卸载，推理全部在手机端完成（已移除联网模型）",
                    onClick = { onNavigate(AppRoute.LocalModel) },
                )
            }
        }

        item(key = "list_section") {
            ProviderSection(title = pluralStringResource(R.plurals.provider_configured_count, filteredProviders.size, filteredProviders.size)) {
                if (filteredProviders.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) {
                                stringResource(R.string.provider_empty)
                            } else {
                                stringResource(R.string.provider_no_matches)
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                } else {
                    filteredProviders.forEach { provider ->
                        ProviderListItem(
                            provider = provider,
                            isSelected = provider.id == selectedProviderId,
                            onOpen = { onNavigate(AppRoute.ModelProviderDetail(provider.id)) },
                            onDelete = if (!provider.isBuiltIn) {
                                { providerToDelete = provider }
                            } else {
                                null
                            },
                            onSelect = {
                                scope.launch {
                                    RuntimeConfigRepository.setSelectedProviderId(provider.id)
                                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (providerToDelete != null) {
        EtaOverlayDialog(
            show = true,
            title = stringResource(R.string.ui_remove_provider_9f848f),
            summary = stringResource(R.string.provider_delete_summary, providerToDelete?.name.orEmpty()),
            onDismissRequest = { providerToDelete = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.ui_delete_3755f5),
                destructive = true,
                onCancel = { providerToDelete = null },
                onConfirm = {
                    scope.launch {
                        providerToDelete?.let { provider ->
                            ProviderRepository.deleteProvider(provider.id)
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                        }
                        providerToDelete = null
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProviderListItem(
    provider: ProviderSetting,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
    onSelect: () -> Unit,
) {
    val opacity = if (provider.isEnabled) 1f else 0.6f
    EtaPreferenceRow(
        title = null,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onDelete)
            .graphicsLayer { alpha = opacity },
        startAction = { ProviderIcon(provider) },
        titleContent = {
            Text(
                text = provider.name,
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = provider.baseUrl,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = listOfNotNull(
                    provider.typeLabel,
                    pluralStringResource(R.plurals.provider_models_count, provider.models.size, provider.models.size),
                    stringResource(R.string.ui_built_in_09ceea).takeIf { provider.isBuiltIn },
                ).joinToString(" · "),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (!provider.isEnabled) {
                Text(
                    text = stringResource(R.string.ui_disabled_0fe5a9),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        },
        endActions = {
            IconButton(onClick = onSelect) {
                                XuanGlyph(
                    type = if (isSelected) XuanGlyphType.Check else XuanGlyphType.Stop,
                    tint = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        },
    )
}
