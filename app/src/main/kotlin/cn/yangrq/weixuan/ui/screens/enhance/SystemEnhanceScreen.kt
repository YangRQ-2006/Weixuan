package cn.yangrq.weixuan.ui.screens.enhance

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.yangrq.weixuan.R
import cn.yangrq.weixuan.ui.app.description
import cn.yangrq.weixuan.ui.app.rememberDeviceCapabilities
import cn.yangrq.weixuan.ui.components.EtaCard
import cn.yangrq.weixuan.ui.components.EtaPreference
import cn.yangrq.weixuan.ui.components.EtaPreferenceColors
import cn.yangrq.weixuan.ui.components.EtaPreferenceDivider
import cn.yangrq.weixuan.ui.components.EtaPreferenceGroup
import cn.yangrq.weixuan.ui.components.EtaPreferenceGroupTitle
import cn.yangrq.weixuan.ui.components.EtaPreferenceIcon
import cn.yangrq.weixuan.ui.components.EtaTextButton
import cn.yangrq.weixuan.ui.components.MiuixScaffoldPage
import cn.yangrq.weixuan.ui.model.AgentSystemEnhanceAction
import cn.yangrq.weixuan.ui.design.XuanGlyphType

@Composable
fun SystemEnhanceScreen(
    onAction: (AgentSystemEnhanceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val capabilities = rememberDeviceCapabilities()
    val canRequestRoot = capabilities.root.suPresent && !capabilities.root.isGranted
    MiuixScaffoldPage(
        title = stringResource(R.string.capability_enhancements),
        onBack = { onAction(AgentSystemEnhanceAction.NavigateBack) },
        modifier = modifier,
    ) {
        item(key = "access") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                EtaPreference(
                    title = "Root",
                    summary = capabilities.root.description(context),
                    startAction = { EtaPreferenceIcon(XuanGlyphType.Permission, tint = EtaPreferenceColors.Yellow) },
                    endActions = {
                        EtaTextButton(
                            text = stringResource(
                                if (canRequestRoot) R.string.capability_root_request
                                else R.string.capability_root_refresh,
                            ),
                            enabled = !capabilities.root.isChecking,
                            onClick = {
                                onAction(
                                    if (canRequestRoot) AgentSystemEnhanceAction.RequestRoot
                                    else AgentSystemEnhanceAction.RefreshRoot,
                                )
                            },
                        )
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaPreference(
                    title = stringResource(R.string.capability_xposed_service),
                    summary = stringResource(
                        if (capabilities.xposedConnected) R.string.capability_xposed_connected
                        else R.string.capability_xposed_disconnected,
                    ),
                    startAction = { EtaPreferenceIcon(XuanGlyphType.Mcp, tint = EtaPreferenceColors.Blue) },
                )
            }
        }
        item(key = "framework-help") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                EtaPreference(
                    title = stringResource(R.string.capability_xposed_help),
                    summary = stringResource(R.string.capability_xposed_help_summary),
                    startAction = { EtaPreferenceIcon(XuanGlyphType.Memory, tint = EtaPreferenceColors.Blue) },
                )
            }
        }
        item(key = "root-title") { EtaPreferenceGroupTitle(stringResource(R.string.capability_root_features)) }
        item(key = "root-features") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                EtaPreference(
                    title = stringResource(R.string.capability_root_device),
                    summary = stringResource(R.string.capability_root_device_summary),
                    startAction = { EtaPreferenceIcon(XuanGlyphType.Permission, tint = EtaPreferenceColors.Blue) },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaPreference(
                    title = stringResource(R.string.capability_root_data),
                    summary = stringResource(R.string.capability_root_data_summary),
                    startAction = { EtaPreferenceIcon(XuanGlyphType.Permission, tint = EtaPreferenceColors.Blue) },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaPreference(
                    title = stringResource(R.string.capability_root_linux),
                    summary = stringResource(R.string.capability_root_linux_summary),
                    startAction = { EtaPreferenceIcon(XuanGlyphType.Terminal, tint = EtaPreferenceColors.Green) },
                )
            }
        }
        item(key = "hook-title") { EtaPreferenceGroupTitle(stringResource(R.string.capability_system_features)) }
        item(key = "hook-features") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                EtaPreference(
                    title = stringResource(R.string.capability_hook_assistants),
                    summary = stringResource(R.string.capability_hook_assistants_summary),
                    startAction = { EtaPreferenceIcon(XuanGlyphType.Model, tint = EtaPreferenceColors.Green) },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaPreference(
                    title = stringResource(R.string.capability_hook_google),
                    summary = stringResource(R.string.capability_hook_google_summary),
                    startAction = { EtaPreferenceIcon(XuanGlyphType.Search, tint = EtaPreferenceColors.Blue) },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaPreference(
                    title = stringResource(R.string.capability_hook_accessibility),
                    summary = stringResource(R.string.capability_hook_accessibility_summary),
                    startAction = { EtaPreferenceIcon(XuanGlyphType.Permission, tint = EtaPreferenceColors.Blue) },
                )
            }
        }
    }
}
