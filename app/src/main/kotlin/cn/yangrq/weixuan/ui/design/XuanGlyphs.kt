package cn.yangrq.weixuan.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 微玄「爻线」图标系统（Xuan Glyphs）
 * ---------------------------------------------------------------------------
 * 为什么要自绘：上游 Eta 全量使用 `material-icons-extended`（**引用 262 处**），
 * 即 Google Material 图标语义（圆润实心、2dp 描边、Google 几何），
 * 是"看起来还像 ETA/Google"的重要组成部分之一。仅换色无法改变图形语言。
 *
 * 微玄的图形语言（三条硬规则）：
 *  1. **线，不是块**：一律 24 格画布上的 1.6dp 圆头细线，除门心/星火外不做实心填充。
 *  2. **爻的骨架**：以「三横 / 断横（阴爻）/ 竖线 / 圆」为基本笔画，
 *     例如「设置」直接就是一枚爻（上横 · 阴爻 · 下横），不画齿轮。
 *  3. **留白与呼吸**：笔画不贴边（内缩 3 格以上），形状不闭合的地方就开口。
 *
 * 使用：`XuanGlyph(XuanGlyphType.Settings, modifier = Modifier.size(22.dp), tint = ...)`
 */
enum class XuanGlyphType {
    /** 玄之门：拱门 + 门内环 + 门心（品牌标记，用于空态/关于/启动）。 */
    Gate,

    /** 设置：一枚爻（阳 · 阴 · 阳），取代 Material 齿轮。 */
    Settings,

    /** 模型：枢 —— 圆环 + 中心点 + 上下引线。 */
    Model,

    /** 工具：斜杆 + 环（扳手抽象）。 */
    Tools,

    /** 技能：三条右对齐横线 + 右竖线。 */
    Skills,

    /** 权限：门框 + 门内一点（守护）。 */
    Permission,

    /** 角色：两枚交叠的圆（阴阳相合）。 */
    Character,

    /** 终端：折角符 + 底线。 */
    Terminal,

    /** 浏览器：圆 + 横弦。 */
    Browser,

    /** 记忆：纸页 + 两行字。 */
    Memory,

    /** MCP：三节点连线。 */
    Mcp,

    /** 新建会话：十字。 */
    Plus,

    /** 会话历史：圆 + 指针。 */
    History,

    /** 更多：三点竖排。 */
    More,

    /** 发送：上行箭头（配合「方印」底）。 */
    Send,

    /** 停止：方。 */
    Stop,

    /** 搜索：环 + 斜柄。 */
    Search,

    /** 完成：勾。 */
    Check,

    /** 关闭：叉。 */
    Close,

    /** 进入：右尖角。 */
    ChevronRight,

    /** 展开：下尖角。 */
    ChevronDown,

    /** 刷新：近全圆弧 + 引线。 */
    Refresh,

    /** 下载：下行箭头 + 底线。 */
    Download,

    /** 删除：两竖 + 上横。 */
    Delete,

    /** 运行：三角。 */
    Play,

    /** 语言 / 网络：圆 + 两条经线。 */
    Globe,

    /** 文件夹 / 工作区：折页框。 */
    Folder,
}

@Composable
internal fun XuanGlyph(
    type: XuanGlyphType,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    strokeWidth: Dp = 1.6.dp,
) {
    val color = if (tint == Color.Unspecified) MiuixTheme.colorScheme.onBackground else tint
    Canvas(modifier = modifier) {
        val s = size.minDimension / 24f
        drawGlyph(type, s, strokeWidth.toPx(), color)
    }
}

/** 便捷重载：直接给尺寸。 */
@Composable
internal fun XuanGlyph(
    type: XuanGlyphType,
    size: Dp,
    tint: Color = Color.Unspecified,
    strokeWidth: Dp = 1.6.dp,
) = XuanGlyph(type, Modifier.size(size), tint, strokeWidth)

private fun DrawScope.drawGlyph(type: XuanGlyphType, s: Float, w: Float, c: Color) {
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
        drawLine(
            color = c,
            start = Offset(x1 * s, y1 * s),
            end = Offset(x2 * s, y2 * s),
            strokeWidth = w,
            cap = StrokeCap.Round,
        )
    }

    fun dot(x: Float, y: Float, r: Float) {
        drawCircle(color = c, radius = r * s, center = Offset(x * s, y * s))
    }

    fun ring(x: Float, y: Float, r: Float, sw: Float = w) {
        drawCircle(
            color = c,
            radius = r * s,
            center = Offset(x * s, y * s),
            style = Stroke(width = sw),
        )
    }

    fun arc(x: Float, y: Float, r: Float, startAngle: Float, sweep: Float, sw: Float = w) {
        drawArc(
            color = c,
            startAngle = startAngle,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset((x - r) * s, (y - r) * s),
            size = Size(2f * r * s, 2f * r * s),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
    }

    fun path(block: Path.() -> Unit) {
        drawPath(
            path = Path().apply(block),
            color = c,
            style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    when (type) {
        XuanGlyphType.Gate -> {
            path {
                moveTo(7f * s, 19.5f * s)
                lineTo(7f * s, 12f * s)
                cubicTo(7f * s, 6.2f * s, 17f * s, 6.2f * s, 17f * s, 12f * s)
                lineTo(17f * s, 19.5f * s)
            }
            ring(12f, 13.2f, 3.2f)
            dot(12f, 13.2f, 1.05f)
        }

        // 一枚爻：阳 · 阴 · 阳
        XuanGlyphType.Settings -> {
            line(5.5f, 6.5f, 18.5f, 6.5f)
            line(5.5f, 12f, 10.2f, 12f)
            line(13.8f, 12f, 18.5f, 12f)
            line(5.5f, 17.5f, 18.5f, 17.5f)
        }

        XuanGlyphType.Model -> {
            ring(12f, 12f, 6.6f)
            dot(12f, 12f, 1.5f)
            line(12f, 2.6f, 12f, 5.1f)
            line(12f, 18.9f, 12f, 21.4f)
        }

        XuanGlyphType.Tools -> {
            line(6.5f, 17.5f, 13.6f, 10.4f)
            ring(16f, 8f, 3.1f)
        }

        XuanGlyphType.Skills -> {
            line(17.5f, 5.5f, 17.5f, 18.5f)
            line(8f, 7f, 17.5f, 7f)
            line(6f, 12f, 17.5f, 12f)
            line(10f, 17f, 17.5f, 17f)
        }

        XuanGlyphType.Permission -> {
            path {
                moveTo(8f * s, 20f * s)
                lineTo(8f * s, 9.5f * s)
                cubicTo(8f * s, 6f * s, 16f * s, 6f * s, 16f * s, 9.5f * s)
                lineTo(16f * s, 20f * s)
            }
            dot(12f, 13.5f, 1.35f)
        }

        XuanGlyphType.Character -> {
            ring(9.4f, 12f, 4.6f)
            ring(14.6f, 12f, 4.6f)
        }

        XuanGlyphType.Terminal -> {
            line(6.5f, 8.5f, 10.5f, 12f)
            line(10.5f, 12f, 6.5f, 15.5f)
            line(12.5f, 16.5f, 17.5f, 16.5f)
        }

        XuanGlyphType.Browser -> {
            ring(12f, 12f, 7f)
            line(5f, 12f, 19f, 12f)
        }

        XuanGlyphType.Memory -> {
            path {
                moveTo(6.5f * s, 6.5f * s)
                lineTo(17.5f * s, 6.5f * s)
                lineTo(17.5f * s, 17.5f * s)
                lineTo(6.5f * s, 17.5f * s)
                close()
            }
            line(9.5f, 10.5f, 14.5f, 10.5f)
            line(9.5f, 13.5f, 12.5f, 13.5f)
        }

        XuanGlyphType.Mcp -> {
            line(12f, 6f, 7f, 17.5f)
            line(12f, 6f, 17f, 17.5f)
            line(7f, 17.5f, 17f, 17.5f)
            dot(12f, 6f, 1.5f)
            dot(7f, 17.5f, 1.5f)
            dot(17f, 17.5f, 1.5f)
        }

        XuanGlyphType.Plus -> {
            line(12f, 6f, 12f, 18f)
            line(6f, 12f, 18f, 12f)
        }

        XuanGlyphType.History -> {
            ring(12f, 12f, 7f)
            line(12f, 12f, 12f, 7.8f)
            line(12f, 12f, 15.4f, 13.6f)
        }

        XuanGlyphType.More -> {
            dot(12f, 5.8f, 1.5f)
            dot(12f, 12f, 1.5f)
            dot(12f, 18.2f, 1.5f)
        }

        XuanGlyphType.Send -> {
            line(12f, 19f, 12f, 6f)
            line(12f, 6f, 7.8f, 10.2f)
            line(12f, 6f, 16.2f, 10.2f)
        }

        XuanGlyphType.Stop -> {
            path {
                moveTo(8f * s, 8f * s)
                lineTo(16f * s, 8f * s)
                lineTo(16f * s, 16f * s)
                lineTo(8f * s, 16f * s)
                close()
            }
        }

        XuanGlyphType.Search -> {
            ring(11f, 11f, 5.4f)
            line(15.1f, 15.1f, 19f, 19f)
        }

        XuanGlyphType.Check -> {
            line(6f, 12.6f, 10.2f, 16.6f)
            line(10.2f, 16.6f, 18f, 8f)
        }

        XuanGlyphType.Close -> {
            line(7f, 7f, 17f, 17f)
            line(17f, 7f, 7f, 17f)
        }

        XuanGlyphType.ChevronRight -> {
            line(9.8f, 6.5f, 15.2f, 12f)
            line(15.2f, 12f, 9.8f, 17.5f)
        }

        XuanGlyphType.ChevronDown -> {
            line(6.5f, 9.8f, 12f, 15.2f)
            line(12f, 15.2f, 17.5f, 9.8f)
        }

        XuanGlyphType.Refresh -> {
            arc(12f, 12f, 6.8f, startAngle = -55f, sweep = 285f)
            line(15.2f, 4.4f, 17.4f, 8.4f)
            line(17.4f, 8.4f, 13.1f, 8.9f)
        }

        XuanGlyphType.Download -> {
            line(12f, 4.5f, 12f, 14.5f)
            line(12f, 14.5f, 8f, 10.5f)
            line(12f, 14.5f, 16f, 10.5f)
            line(7f, 18.5f, 17f, 18.5f)
        }

        XuanGlyphType.Delete -> {
            line(7.5f, 7f, 16.5f, 7f)
            line(10f, 7f, 10.6f, 18.5f)
            line(14f, 7f, 13.4f, 18.5f)
            line(9.4f, 18.5f, 14.6f, 18.5f)
        }

        XuanGlyphType.Play -> {
            path {
                moveTo(8.5f * s, 6.5f * s)
                lineTo(8.5f * s, 17.5f * s)
                lineTo(17.5f * s, 12f * s)
                close()
            }
        }

        XuanGlyphType.Globe -> {
            ring(12f, 12f, 7f)
            line(5f, 12f, 19f, 12f)
            arc(12f, 12f, 3.4f, startAngle = 90f, sweep = 180f)
        }

        XuanGlyphType.Folder -> {
            path {
                moveTo(5.5f * s, 18f * s)
                lineTo(5.5f * s, 7f * s)
                lineTo(10f * s, 7f * s)
                lineTo(11.6f * s, 9.2f * s)
                lineTo(18.5f * s, 9.2f * s)
                lineTo(18.5f * s, 18f * s)
                close()
            }
        }
    }
}
