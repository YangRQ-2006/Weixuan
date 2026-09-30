package cn.yangrq.weixuan.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cn.yangrq.weixuan.ui.design.XuanColors
import cn.yangrq.weixuan.ui.design.XuanGlyph
import cn.yangrq.weixuan.ui.design.XuanGlyphType
import cn.yangrq.weixuan.ui.design.XuanSpace
import cn.yangrq.weixuan.ui.design.XuanStroke
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 微玄分类色（2026-09-30 品牌化）。
 *
 * 上游为 ColorOS/HyperOS 式鲜艳四色（MIUI 蓝 #0080FF、橙 #FF7700、过饱和绿/黄），
 * 是"满屏彩色图标墙"的来源。这里改为微玄「墨玄」体系的低饱和五色，并按深浅自动切换：
 * 玄紫（模型/推理）、竹青（工具/技能）、鎏金（记忆/上下文）、青黛（权限/系统）、朱砂（危险）。
 */
internal object EtaPreferenceColors {
    private val isLightPalette: Boolean
        @Composable get() = MiuixTheme.colorScheme.background.luminance() > 0.5f

    val XuanViolet: Color @Composable get() = if (isLightPalette) Color(0xFF5B4EC2) else Color(0xFF9B8CFF)
    val Bamboo: Color @Composable get() = if (isLightPalette) Color(0xFF3E7A5E) else Color(0xFF7FB3A0)
    val Gilded: Color @Composable get() = if (isLightPalette) Color(0xFFB08A2E) else Color(0xFFE8C56A)
    val Indigo: Color @Composable get() = if (isLightPalette) Color(0xFF4A5A72) else Color(0xFF8FA3BF)
    val Cinnabar: Color @Composable get() = if (isLightPalette) Color(0xFFB5453A) else Color(0xFFE07A6E)

    // 上游命名别名：保留以兼容既有 60+ 处调用点，后续迁移到 Xuan* 后删除。
    @Suppress("unused")
    val Blue: Color @Composable get() = XuanViolet
    val Green: Color @Composable get() = Bamboo
    val Orange: Color @Composable get() = Gilded
    val Yellow: Color @Composable get() = Cinnabar
}

internal object EtaPreferenceDefaults {
    val SidePadding = XuanSpace.page
    val GroupSpacing = XuanSpace.groupGap
    val IconSize = XuanSpace.iconSlot
    val IconTextGap = XuanSpace.iconTextGap
    val ContentStart = SidePadding + IconSize + IconTextGap
    fun contentStart(hasLeading: Boolean) = if (hasLeading) ContentStart else SidePadding

    /** 微玄：通栏设置行高 56dp（上游为 52dp 的卡片行）。 */
    val RowMinHeight = XuanSpace.row
}

/**
 * 微玄：设置/管理页不再覆写主题色。
 *
 * 上游此处会在「非 Monet + 浅色」时把 background 改成 #F0F1F2、primary 改成 MIUI 蓝 #0080FF，
 * 导致微玄的玄紫品牌色在浅色设置页被整体吃掉（2026-09-30 修复）。
 * 现在颜色一律来自 AgentAppTheme 的微玄色板，本函数保留为设计系统 token 的挂载点。
 */
@Composable
internal fun EtaPreferenceTheme(content: @Composable () -> Unit) {
    content()
}

/** 分区墨线：微玄用一条发丝墨线代替 HyperOS 的分组卡片边界。 */
@Composable
private fun SectionRule(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(XuanStroke.hairline)
            .background(XuanColors.inkLineSoft),
    )
}

/**
 * 微玄（2026-09-30）：设置分组从「大圆角卡片」改为「通栏分区」。
 *
 * 上游是 ColorOS/HyperOS 的"分组卡片 + 圆角底"范式，是"最像 ETA"的结构符号之一。
 * 微玄改为通栏纸面：去掉卡片背景与圆角，用上下两条发丝墨线划出分区，
 * 让页面读起来像一页连续的文字，而不是一堆漂浮的卡片。
 *
 * 参数保持兼容：`insideMargin` 继续生效（转为容器内边距），`colors` 保留签名但不再使用
 * （卡片配色对通栏分区无意义），后续统一迁移到 XuanSection。
 */
@Composable
internal fun EtaPreferenceGroup(
    modifier: Modifier = Modifier
        .padding(horizontal = EtaPreferenceDefaults.SidePadding)
        .padding(bottom = EtaPreferenceDefaults.GroupSpacing),
    insideMargin: PaddingValues = PaddingValues(0.dp),
    @Suppress("UNUSED_PARAMETER") colors: CardColors = CardDefaults.defaultColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(insideMargin),
    ) {
        SectionRule()
        Column(modifier = Modifier.fillMaxWidth(), content = content)
        SectionRule()
    }
}

/**
 * 微玄（2026-09-30）：分组标题加「签」标记。
 *
 * 上游是 MIUI 式"32dp 缩进的灰色小字标题"；微玄改为「一枚玄紫竖签 + 标题」，
 * 与页边距对齐，形成书页分节的节奏。
 */
@Composable
internal fun EtaPreferenceGroupTitle(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(
            start = EtaPreferenceDefaults.SidePadding,
            end = EtaPreferenceDefaults.SidePadding,
            top = 6.dp,
            bottom = 10.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = 12.dp)
                .background(XuanColors.primary),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 微玄：设置页图标支持自绘「爻线」图形（XuanGlyphType）。
 * 与下方 ImageVector 重载并列，便于逐项把 Material 图标换成微玄图形语言。
 */
@Composable
internal fun EtaPreferenceIcon(
    glyph: XuanGlyphType,
    tint: Color = MiuixTheme.colorScheme.onBackground,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier.size(XuanSpace.iconSlot),
        contentAlignment = Alignment.Center,
    ) {
        XuanGlyph(
            type = glyph,
            modifier = Modifier.size(22.dp),
            tint = if (enabled) tint else MiuixTheme.colorScheme.disabledOnSurface,
        )
    }
}

@Composable
internal fun EtaPreferenceIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.onBackground,
    enabled: Boolean = true,
) {
    // 满幅轮廓稍作光学校正，但所有图标都占相同宽度，保证正文和分割线对齐。
    val glyphSize = when (icon) {
        Icons.Rounded.Extension, Icons.Rounded.TheaterComedy, Icons.AutoMirrored.Rounded.MenuBook -> 22.dp
        else -> EtaPreferenceDefaults.IconSize
    }
    Box(modifier.size(EtaPreferenceDefaults.IconSize), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(glyphSize),
            tint = if (enabled) tint else MiuixTheme.colorScheme.disabledOnSurface,
        )
    }
}

/** 行内墨线：0.5dp 发丝线 + 暖灰墨色（上游为 0.33dp 的纯透明度灰）。 */
@Composable
internal fun EtaPreferenceDivider(hasLeading: Boolean = true, modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(
            start = EtaPreferenceDefaults.contentStart(hasLeading),
            end = EtaPreferenceDefaults.SidePadding,
        ),
        thickness = XuanStroke.hairline,
        color = XuanColors.inkLineSoft,
    )
}
