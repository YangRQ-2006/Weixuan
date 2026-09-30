package cn.yangrq.weixuan.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.utils.PressFeedbackType

internal object EtaCardDefaults {
    /**
     * 微玄全局卡片圆角总开关（= XuanShape.md）。
     *
     * 上游 Eta 沿用 HyperOS 的大圆角「方圆形」（24dp），视觉偏软；
     * 微玄走「墨玄/留白」路线：24dp → 16dp（2026-09-28）→ 14dp（2026-09-30 令牌化），
     * 用更克制的圆角 + 细描边体现书卷气的安静感。改此处即全 App 卡片与设置分组生效。
     */
    val CornerRadius = 14.dp
}

@Composable
internal fun EtaCard(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        cornerRadius = EtaCardDefaults.CornerRadius,
        insideMargin = insideMargin,
        colors = colors,
        content = content,
    )
}

@Composable
internal fun EtaCard(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(),
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    showIndication: Boolean = false,
    holdDownState: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        cornerRadius = EtaCardDefaults.CornerRadius,
        insideMargin = insideMargin,
        colors = colors,
        pressFeedbackType = pressFeedbackType,
        showIndication = showIndication,
        holdDownState = holdDownState,
        onClick = onClick,
        onLongPress = onLongPress,
        content = content,
    )
}
