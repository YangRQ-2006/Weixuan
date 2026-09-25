package cn.yangrq.weixuan.ui.screens.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.yangrq.weixuan.R
import cn.yangrq.weixuan.ui.components.EtaArrowPreference
import cn.yangrq.weixuan.ui.components.EtaPreferenceColors
import cn.yangrq.weixuan.ui.components.EtaPreferenceDivider
import cn.yangrq.weixuan.ui.components.EtaPreferenceGroup
import cn.yangrq.weixuan.ui.components.EtaPreferenceGroupTitle
import cn.yangrq.weixuan.ui.components.EtaPreferenceIcon
import cn.yangrq.weixuan.ui.components.MiuixScaffoldPage
import cn.yangrq.weixuan.ui.components.color
import cn.yangrq.weixuan.ui.components.label
import cn.yangrq.weixuan.ui.model.PermissionHealthAction
import cn.yangrq.weixuan.ui.model.PermissionHealthItemUi
import cn.yangrq.weixuan.ui.model.PermissionHealthUiState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PermissionHealthScreen(
    state: PermissionHealthUiState,
    onAction: (PermissionHealthAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    MiuixScaffoldPage(
        title = stringResource(R.string.ui_permission_health_3048bb),
        onBack = { onAction(PermissionHealthAction.NavigateBack) },
        modifier = modifier,
    ) {
        item(key = "title") {
            EtaPreferenceGroupTitle(stringResource(R.string.ui_permissions_and_status_35f368))
        }
        item(key = "card") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                state.items.forEachIndexed { index, item ->
                    if (index > 0) EtaPreferenceDivider()
                    PermissionItemRow(
                        item = item,
                        onActionClick = { onAction(PermissionHealthAction.OpenItemAction(item.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionItemRow(
    item: PermissionHealthItemUi,
    onActionClick: () -> Unit,
) {
    val icon = when (item.id) {
        "accessibility" -> Icons.Rounded.AccessibilityNew
        "overlay" -> Icons.Rounded.Layers
        "model" -> Icons.Rounded.Memory
        "terminal" -> Icons.Rounded.Terminal
        "notification" -> Icons.Rounded.Notifications
        "root" -> Icons.Rounded.Key
        "shizuku" -> Icons.Rounded.Memory
        "xposed" -> Icons.Rounded.AccountTree
        "background" -> Icons.Rounded.History
        "app_list" -> Icons.Rounded.Dashboard
        "location" -> Icons.Rounded.LocationOn
        "notification_history" -> Icons.Rounded.NotificationsActive
        "usage_access" -> Icons.Rounded.QueryStats
        "notifications" -> Icons.Rounded.Notifications
        else -> Icons.Rounded.Shield
    }

    EtaArrowPreference(
        title = item.title,
        summary = item.summary.takeIf { it.isNotBlank() },
        startAction = {
            EtaPreferenceIcon(icon = icon, tint = EtaPreferenceColors.Blue)
        },
        endActions = {
            Text(
                text = item.status.label(),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = item.status.color(),
            )
        },
        onClick = onActionClick,
    )
}
