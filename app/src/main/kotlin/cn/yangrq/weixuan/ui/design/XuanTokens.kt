package cn.yangrq.weixuan.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 微玄 ·「墨玄」设计令牌（Design Tokens）
 * ---------------------------------------------------------------------------
 * 定位：**一张会思考的宣纸**。
 * 与 HyperOS / ColorOS 的「圆润玻璃卡片 + 彩色图标墙」相对，微玄走
 * 「墨色/宣纸底 + 0.5dp 墨线 + 通栏分区 + 极简排版 + 少量流金点缀」。
 *
 * 三条准则：
 *  1. 纸感优于玻璃 —— 默认无毛玻璃、无大投影，靠底色差 + 墨线分层。
 *  2. 少色优于多色 —— 主色只有玄紫，强调只有流金，分类色收敛为五色。
 *  3. 字重优于装饰 —— 用排版层级与留白建立节奏，而非圆角卡片与彩色图标。
 *
 * 说明：Miuix 只提供 Colors / TextStyles 两类 token，没有 Shapes / Spacing /
 * Motion 抽象，组件默认圆角与内边距都是只读常量。这里补齐缺失的令牌层，
 * 供微玄自有组件（Xuan*）与既有 Eta* 组件内部使用。
 */
object XuanShape {
    /** 印章式直角：用于品牌标记（分组标题竖线、方印按钮）。 */
    val seal = RoundedCornerShape(2.dp)

    /** 标签 / 徽标。 */
    val xs = RoundedCornerShape(6.dp)

    /** 工具行 / 内嵌块。 */
    val sm = RoundedCornerShape(10.dp)

    /** 内容卡（全局卡片总开关的取值）。 */
    val md = RoundedCornerShape(14.dp)

    /** 浮层 / 弹窗 / 输入槽。 */
    val lg = RoundedCornerShape(18.dp)

    val pill = RoundedCornerShape(percent = 50)

    /** 会话「签条」：左下角收窄，像一枚签条贴在纸上。 */
    val verseBubble = RoundedCornerShape(
        topStart = 14.dp,
        topEnd = 14.dp,
        bottomEnd = 14.dp,
        bottomStart = 3.dp,
    )

    /** 内嵌详情块：跟随内容卡的克制风格。 */
    val inset = RoundedCornerShape(10.dp)
}

object XuanSpace {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 28.dp

    /** 页面左右边距（手机 / 宽屏）。 */
    val page: Dp = 16.dp
    val pageWide: Dp = 24.dp

    /** 组与组之间的呼吸。 */
    val groupGap: Dp = 20.dp

    /** 通栏设置行高（上游为 52dp 卡片行）。 */
    val row: Dp = 56.dp

    /** 普通列表行高。 */
    val rowCompact: Dp = 52.dp

    /** 分类图标槽宽（保证正文与分割线对齐）。 */
    val iconSlot: Dp = 24.dp

    /** 图标与正文间距。 */
    val iconTextGap: Dp = 16.dp
}

object XuanStroke {
    /** 墨线：分割线 / 描边统一粗细。 */
    val hairline: Dp = 0.5.dp

    /** 选中态描边。 */
    val selected: Dp = 1.dp
}

object XuanMotion {
    const val fast = 120
    const val base = 220
    const val emphasis = 320

    /** 「玄缓」：缓入缓出，无回弹 —— 替代 Miuix 的小米弹簧手感。 */
    val easing: Easing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)
}

object XuanType {
    /** 品牌衬线：用于题记、品牌标记、关键数字以外的强调。 */
    val serif: FontFamily = FontFamily.Serif

    /** 指标等宽：tok/s、首字延迟、内存等实况数字。 */
    val mono: FontFamily = FontFamily.Monospace
}

/**
 * 微玄语义色。
 *
 * 大部分颜色直接取自 AgentAppTheme 注入的微玄色板（玄墨 / 留白），
 * 这里只补三件套：分类色（深浅自适应）、墨线、流金强调。
 */
object XuanColors {
    val isLightPalette: Boolean
        @Composable get() = MiuixTheme.colorScheme.background.luminance() > 0.5f

    val surface: Color @Composable get() = MiuixTheme.colorScheme.surface
    val surfaceContainer: Color @Composable get() = MiuixTheme.colorScheme.surfaceContainer
    val onSurface: Color @Composable get() = MiuixTheme.colorScheme.onSurface
    val primary: Color @Composable get() = MiuixTheme.colorScheme.primary
    val outline: Color @Composable get() = MiuixTheme.colorScheme.outline

    /** 墨线：分割线 / 描边（暖灰 in 浅色，极暗 in 深色）。 */
    val inkLine: Color @Composable get() = if (isLightPalette) Color(0xFFD8D3C8) else Color(0xFF3A3A47)

    /** 弱墨线。 */
    val inkLineSoft: Color @Composable get() = inkLine.copy(alpha = 0.55f)

    /** 玄紫（模型 / 推理 / 主入口）。 */
    val xuanViolet: Color @Composable get() = if (isLightPalette) Color(0xFF5B4EC2) else Color(0xFF9B8CFF)

    /** 竹青（工具 / 技能 / 成功）。 */
    val bamboo: Color @Composable get() = if (isLightPalette) Color(0xFF3E7A5E) else Color(0xFF7FB3A0)

    /** 流金（记忆 / 上下文 / 关键数字 / 强调）。 */
    val gilded: Color @Composable get() = if (isLightPalette) Color(0xFFB08A2E) else Color(0xFFE8C56A)

    /** 青黛（权限 / 系统 / 次要信息）。 */
    val indigo: Color @Composable get() = if (isLightPalette) Color(0xFF4A5A72) else Color(0xFF8FA3BF)

    /** 朱砂（错误 / 删除 / 停止）。 */
    val cinnabar: Color @Composable get() = if (isLightPalette) Color(0xFFB5453A) else Color(0xFFE07A6E)
}
