package cn.yangrq.weixuan.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 微玄适配层组件（Xuan*）
 * ---------------------------------------------------------------------------
 * 设计原则：**薄封装**。对外只暴露微玄 token（XuanColors / XuanShape /
 * XuanSpace / XuanStroke / XuanMotion / XuanType），内部翻译成 Miuix 参数。
 *
 * 为什么需要这一层：Miuix 没有 Shapes / Spacing / Motion token，组件默认圆角与
 * 内边距都是只读常量，无法一处改全局；同时它自称 experimental、API 可能变更。
 * 把「微玄语言」收敛在这一层，底层换库时只需改这里。
 *
 * 既有 Eta* 组件（EtaCard / EtaControls / EtaPreferenceStyle 家族）已同步改用这些
 * token（内部改造，不迁移 60+ 处调用点），因此设置页/对话框/卡片的观感已全局统一。
 */

/** 墨线：一条发丝线，微玄用它代替卡片边界与毛玻璃分隔。 */
@Composable
internal fun XuanRule(
    modifier: Modifier = Modifier,
    color: Color = XuanColors.inkLineSoft,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(XuanStroke.hairline)
            .background(color),
    )
}

/** 题记：品牌衬线 + 宽字距 —— 用于「玄之又玄，众妙之门」这类品牌语。 */
@Composable
internal fun XuanMotto(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontFamily = XuanType.serif,
            fontSize = 12.sp,
            letterSpacing = 2.5.sp,
            fontWeight = FontWeight.Medium,
        ),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
    )
}

/** 分节标题：玄紫竖签 + 标题（可带右侧动作）。 */
@Composable
internal fun XuanSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = XuanSpace.page, end = XuanSpace.page, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = 12.dp)
                .background(XuanColors.primary),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (trailing != null) {
            Spacer(modifier = Modifier.weight(1f))
            trailing()
        }
    }
}

/** 标签（签）：小圆角 + 细描边，取代上游 8dp 圆角的彩色 chip。 */
@Composable
internal fun XuanLabel(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    filled: Boolean = false,
) {
    val color = accent ?: MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(
        modifier = modifier
            .background(if (filled) color.copy(alpha = 0.14f) else Color.Transparent, XuanShape.xs)
            .border(
                width = XuanStroke.hairline,
                color = if (filled) Color.Transparent else color.copy(alpha = 0.45f),
                shape = XuanShape.xs,
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = 11.sp, letterSpacing = 0.4.sp),
            color = color,
        )
    }
}

/** 实况指标：等宽 + 流金（tok/s、首字延迟、内存、NPU 状态）。 */
@Composable
internal fun XuanMetric(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    accent: Color = XuanColors.gilded,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            text = value,
            style = TextStyle(
                fontFamily = XuanType.mono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = accent,
        )
        if (unit != null) {
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = unit,
                style = TextStyle(fontSize = 10.sp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
