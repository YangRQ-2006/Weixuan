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
 *
 * 图形语言：24 格画布 / 2.0dp 圆头细线 / 笔画占满 4-20 格（视觉重量对齐 Material 24dp 图标）。
 * 上游 Eta 全量使用 material-icons-extended（262 处），此为微玄自有图形语言。
 */
enum class XuanGlyphType {
    Gate, Settings, Model, Tools, Skills, Permission, Character, Terminal, Browser,
    Memory, Mcp, Plus, History, More, Send, Stop, Search, Check, Close,
    ChevronRight, ChevronDown, ChevronLeft, Refresh, Download, Delete, Play, Globe, Folder,
    Image, Device, Clipboard, Tap, Wifi, Swap, Bag, Monitor, Bell, Location,
    Music, Pulse, Mic, Command, Keyboard, Note, Contact, Move, Sync, Link,
}

@Composable
internal fun XuanGlyph(
    type: XuanGlyphType,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    strokeWidth: Dp = 2.dp,
) {
    val color = if (tint == Color.Unspecified) MiuixTheme.colorScheme.onBackground else tint
    Canvas(modifier = modifier) {
        val s = size.minDimension / 24f
        drawGlyph(type, s, strokeWidth.toPx(), color)
    }
}

@Composable
internal fun XuanGlyph(
    type: XuanGlyphType,
    size: Dp,
    tint: Color = Color.Unspecified,
    strokeWidth: Dp = 2.dp,
) = XuanGlyph(type, Modifier.size(size), tint, strokeWidth)

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun DrawScope.drawGlyph(type: XuanGlyphType, s: Float, w: Float, c: Color) {
    fun l(x1: Float, y1: Float, x2: Float, y2: Float) {
        drawLine(c, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), w, StrokeCap.Round)
    }

    fun d(x: Float, y: Float, r: Float) {
        drawCircle(c, r * s, Offset(x * s, y * s))
    }

    fun r(x: Float, y: Float, rad: Float, sw: Float = w) {
        drawCircle(c, rad * s, Offset(x * s, y * s), style = Stroke(sw))
    }

    fun a(x: Float, y: Float, rad: Float, st: Float, sw: Float = w, th: Float = w) {
        drawArc(
            color = c,
            startAngle = st,
            sweepAngle = sw,
            useCenter = false,
            topLeft = Offset((x - rad) * s, (y - rad) * s),
            size = Size(2f * rad * s, 2f * rad * s),
            style = Stroke(width = th, cap = StrokeCap.Round),
        )
    }

    fun p(block: Path.() -> Unit) {
        drawPath(Path().apply(block), c, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    when (type) {
        XuanGlyphType.Gate -> {
            p {
                moveTo(6f * s, 20.5f * s); lineTo(6f * s, 11.6f * s)
                cubicTo(6f * s, 5f * s, 18f * s, 5f * s, 18f * s, 11.6f * s)
                lineTo(18f * s, 20.5f * s)
            }
            r(12f, 13.4f, 3.9f); d(12f, 13.4f, 1.35f)
        }
        XuanGlyphType.Settings -> {
            l(4f, 5.5f, 20f, 5.5f); l(4f, 12f, 10.2f, 12f); l(13.8f, 12f, 20f, 12f); l(4f, 18.5f, 20f, 18.5f)
        }
        XuanGlyphType.Model -> {
            r(12f, 12f, 7.6f); d(12f, 12f, 1.9f); l(12f, 2.4f, 12f, 4.2f); l(12f, 19.8f, 12f, 21.6f)
        }
        XuanGlyphType.Tools -> {
            l(5.2f, 18.8f, 14.4f, 9.6f); r(16.9f, 7.1f, 3.7f)
        }
        XuanGlyphType.Skills -> {
            l(19.6f, 4.4f, 19.6f, 19.6f); l(9.6f, 7f, 19.6f, 7f); l(4.4f, 12f, 19.6f, 12f); l(11f, 17f, 19.6f, 17f)
        }
        XuanGlyphType.Permission -> {
            p {
                moveTo(7f * s, 20.8f * s); lineTo(7f * s, 10.2f * s)
                cubicTo(7f * s, 5.2f * s, 17f * s, 5.2f * s, 17f * s, 10.2f * s)
                lineTo(17f * s, 20.8f * s)
            }
            d(12f, 13.6f, 1.7f)
        }
        XuanGlyphType.Character -> { r(8.7f, 12f, 5.3f); r(15.3f, 12f, 5.3f) }
        XuanGlyphType.Terminal -> {
            l(5.4f, 7.4f, 10.6f, 12f); l(10.6f, 12f, 5.4f, 16.6f); l(12.8f, 17.6f, 18.6f, 17.6f)
        }
        XuanGlyphType.Browser -> { r(12f, 12f, 7.8f); l(4.2f, 12f, 19.8f, 12f) }
        XuanGlyphType.Memory -> {
            p {
                moveTo(5.4f * s, 5.4f * s); lineTo(18.6f * s, 5.4f * s)
                lineTo(18.6f * s, 18.6f * s); lineTo(5.4f * s, 18.6f * s); close()
            }
            l(9f, 10.4f, 15f, 10.4f); l(9f, 14f, 13.4f, 14f)
        }
        XuanGlyphType.Mcp -> {
            l(12f, 4.6f, 5.8f, 19f); l(12f, 4.6f, 18.2f, 19f); l(5.8f, 19f, 18.2f, 19f)
            d(12f, 4.6f, 1.8f); d(5.8f, 19f, 1.8f); d(18.2f, 19f, 1.8f)
        }
        XuanGlyphType.Plus -> { l(12f, 4.6f, 12f, 19.4f); l(4.6f, 12f, 19.4f, 12f) }
        XuanGlyphType.History -> {
            r(12f, 12f, 7.8f); l(12f, 12f, 12f, 6.8f); l(12f, 12f, 16.2f, 14f)
        }
        XuanGlyphType.More -> { d(12f, 4.8f, 1.9f); d(12f, 12f, 1.9f); d(12f, 19.2f, 1.9f) }
        XuanGlyphType.Send -> {
            l(12f, 20f, 12f, 5f); l(12f, 5f, 6.8f, 10.6f); l(12f, 5f, 17.2f, 10.6f)
        }
        XuanGlyphType.Stop -> {
            p {
                moveTo(7.2f * s, 7.2f * s); lineTo(16.8f * s, 7.2f * s)
                lineTo(16.8f * s, 16.8f * s); lineTo(7.2f * s, 16.8f * s); close()
            }
        }
        XuanGlyphType.Search -> { r(10.8f, 10.8f, 6.4f); l(15.6f, 15.6f, 20f, 20f) }
        XuanGlyphType.Check -> { l(4.6f, 12.8f, 9.6f, 17.6f); l(9.6f, 17.6f, 19.6f, 6.8f) }
        XuanGlyphType.Close -> { l(6f, 6f, 18f, 18f); l(18f, 6f, 6f, 18f) }
        XuanGlyphType.ChevronRight -> { l(9f, 5.2f, 15.8f, 12f); l(15.8f, 12f, 9f, 18.8f) }
        XuanGlyphType.ChevronDown -> { l(5.2f, 9f, 12f, 15.8f); l(12f, 15.8f, 18.8f, 9f) }
        XuanGlyphType.ChevronLeft -> { l(15.8f, 5.2f, 9f, 12f); l(9f, 12f, 15.8f, 18.8f) }
        XuanGlyphType.Refresh -> {
            a(12f, 12f, 7.6f, st = -60f, sw = 290f)
            l(15.4f, 3.6f, 18.6f, 8.4f); l(18.6f, 8.4f, 13.2f, 9f)
        }
        XuanGlyphType.Download -> {
            l(12f, 3.6f, 12f, 15.4f); l(12f, 15.4f, 7.2f, 10.6f); l(12f, 15.4f, 16.8f, 10.6f)
            l(6.4f, 19.8f, 17.6f, 19.8f)
        }
        XuanGlyphType.Delete -> {
            l(6.6f, 6.4f, 17.4f, 6.4f); l(9.8f, 6.4f, 10.5f, 19.6f)
            l(14.2f, 6.4f, 13.5f, 19.6f); l(9.2f, 19.6f, 14.8f, 19.6f)
        }
        XuanGlyphType.Play -> {
            p {
                moveTo(7f * s, 4.6f * s); lineTo(7f * s, 19.4f * s); lineTo(19f * s, 12f * s); close()
            }
        }
        XuanGlyphType.Globe -> { r(12f, 12f, 7.8f); l(4.2f, 12f, 19.8f, 12f); a(12f, 12f, 3.9f, 90f, 180f) }
        XuanGlyphType.Folder -> {
            p {
                moveTo(4.6f * s, 19.6f * s); lineTo(4.6f * s, 6f * s); lineTo(9.8f * s, 6f * s)
                lineTo(11.6f * s, 8.6f * s); lineTo(19.4f * s, 8.6f * s); lineTo(19.4f * s, 19.6f * s); close()
            }
        }

        XuanGlyphType.Image -> {
            p {
                moveTo(4.6f * s, 5.4f * s); lineTo(19.4f * s, 5.4f * s)
                lineTo(19.4f * s, 18.6f * s); lineTo(4.6f * s, 18.6f * s); close()
            }
            l(6.8f, 15.6f, 10.8f, 11f); l(10.8f, 11f, 14.4f, 15.6f)
            d(15.6f, 9.2f, 1.5f)
        }
        XuanGlyphType.Device -> {
            p {
                moveTo(7.6f * s, 4.4f * s); lineTo(16.4f * s, 4.4f * s)
                lineTo(16.4f * s, 19.6f * s); lineTo(7.6f * s, 19.6f * s); close()
            }
            l(10.8f, 17f, 13.2f, 17f)
        }
        XuanGlyphType.Clipboard -> {
            p {
                moveTo(7.4f * s, 5.6f * s); lineTo(16.6f * s, 5.6f * s)
                lineTo(16.6f * s, 19.6f * s); lineTo(7.4f * s, 19.6f * s); close()
            }
            l(10f, 5.6f, 10f, 3.6f); l(10f, 3.6f, 14f, 3.6f); l(14f, 3.6f, 14f, 5.6f)
        }
        XuanGlyphType.Tap -> {
            r(12f, 12f, 6.6f); d(12f, 12f, 1.9f)
            l(12f, 2.6f, 12f, 4.6f); l(12f, 19.4f, 12f, 21.4f); l(2.6f, 12f, 4.6f, 12f); l(19.4f, 12f, 21.4f, 12f)
        }
        XuanGlyphType.Wifi -> {
            a(12f, 18.6f, 12.6f, st = -145f, sw = 110f)
            a(12f, 18.6f, 8f, st = -145f, sw = 110f)
            a(12f, 18.6f, 3.4f, st = -145f, sw = 110f)
        }
        XuanGlyphType.Swap -> {
            l(7.6f, 4.4f, 7.6f, 17.6f); l(4.8f, 14.8f, 7.6f, 17.6f); l(10.4f, 14.8f, 7.6f, 17.6f)
            l(16.4f, 19.6f, 16.4f, 6.4f); l(13.6f, 9.2f, 16.4f, 6.4f); l(19.2f, 9.2f, 16.4f, 6.4f)
        }
        XuanGlyphType.Bag -> {
            p {
                moveTo(5.4f * s, 8.6f * s); lineTo(18.6f * s, 8.6f * s)
                lineTo(18.6f * s, 20f * s); lineTo(5.4f * s, 20f * s); close()
            }
            a(12f, 8.6f, 3.4f, st = 180f, sw = 180f)
        }
        XuanGlyphType.Monitor -> {
            p {
                moveTo(3.6f * s, 5.2f * s); lineTo(20.4f * s, 5.2f * s)
                lineTo(20.4f * s, 16.4f * s); lineTo(3.6f * s, 16.4f * s); close()
            }
            l(12f, 16.4f, 12f, 19.6f); l(8f, 19.6f, 16f, 19.6f)
        }
        XuanGlyphType.Bell -> {
            p {
                moveTo(8.2f * s, 17.4f * s); lineTo(8.2f * s, 11f * s)
                cubicTo(8.2f * s, 5.6f * s, 15.8f * s, 5.6f * s, 15.8f * s, 11f * s)
                lineTo(15.8f * s, 17.4f * s)
            }
            l(5.8f, 17.4f, 18.2f, 17.4f); l(10.6f, 20f, 13.4f, 20f)
        }
        XuanGlyphType.Location -> {
            p {
                moveTo(12f * s, 4f * s)
                cubicTo(17.2f * s, 4f * s, 19.6f * s, 8f * s, 19.6f * s, 11f * s)
                cubicTo(19.6f * s, 15.6f * s, 12f * s, 21f * s, 12f * s, 21f * s)
                cubicTo(12f * s, 21f * s, 4.4f * s, 15.6f * s, 4.4f * s, 11f * s)
                cubicTo(4.4f * s, 8f * s, 6.8f * s, 4f * s, 12f * s, 4f * s)
                close()
            }
            r(12f, 10.8f, 2.4f)
        }
        XuanGlyphType.Music -> {
            l(8.6f, 18.4f, 8.6f, 6.4f); l(8.6f, 6.4f, 17.4f, 4.6f); l(17.4f, 4.6f, 17.4f, 16.4f)
            r(6.4f, 18.4f, 2.3f); r(15.2f, 16.4f, 2.3f)
        }
        XuanGlyphType.Pulse -> {
            l(3.6f, 12f, 8f, 12f); l(8f, 12f, 10.2f, 6.6f); l(10.2f, 6.6f, 13.4f, 17.4f)
            l(13.4f, 17.4f, 15.6f, 12f); l(15.6f, 12f, 20.4f, 12f)
        }
        XuanGlyphType.Mic -> {
            p {
                moveTo(12f * s, 4.2f * s)
                cubicTo(14.4f * s, 4.2f * s, 15.4f * s, 6f * s, 15.4f * s, 8f * s)
                lineTo(15.4f * s, 12.6f * s)
                cubicTo(15.4f * s, 15f * s, 8.6f * s, 15f * s, 8.6f * s, 12.6f * s)
                lineTo(8.6f * s, 8f * s)
                cubicTo(8.6f * s, 6f * s, 9.6f * s, 4.2f * s, 12f * s, 4.2f * s)
                close()
            }
            a(12f, 12.4f, 6.2f, st = 20f, sw = 140f)
            l(12f, 18.6f, 12f, 21f)
        }
        XuanGlyphType.Command -> {
            l(8.6f, 8.6f, 15.4f, 15.4f); l(15.4f, 8.6f, 8.6f, 15.4f)
            r(6.6f, 6.6f, 2f); r(17.4f, 6.6f, 2f); r(6.6f, 17.4f, 2f); r(17.4f, 17.4f, 2f)
        }
        XuanGlyphType.Keyboard -> {
            p {
                moveTo(3.6f * s, 6.8f * s); lineTo(20.4f * s, 6.8f * s)
                lineTo(20.4f * s, 17.2f * s); lineTo(3.6f * s, 17.2f * s); close()
            }
            l(7f, 10.4f, 8.4f, 10.4f); l(11.2f, 10.4f, 12.6f, 10.4f); l(15.4f, 10.4f, 16.8f, 10.4f)
            l(8.4f, 14f, 15.6f, 14f)
        }
        XuanGlyphType.Note -> {
            p {
                moveTo(5.4f * s, 4.6f * s); lineTo(14f * s, 4.6f * s)
                lineTo(18.6f * s, 9.2f * s); lineTo(18.6f * s, 19.4f * s)
                lineTo(5.4f * s, 19.4f * s); close()
            }
            l(13.6f, 4.6f, 13.6f, 9.6f); l(13.6f, 9.6f, 18.6f, 9.6f)
            l(8.6f, 13.4f, 15.4f, 13.4f); l(8.6f, 16.4f, 13f, 16.4f)
        }
        XuanGlyphType.Contact -> {
            r(12f, 9f, 3.6f)
            a(12f, 20.4f, 7.6f, st = 200f, sw = 140f)
        }
        XuanGlyphType.Move -> {
            l(12f, 3.6f, 12f, 20.4f); l(3.6f, 12f, 20.4f, 12f)
            l(12f, 3.6f, 9.6f, 6.4f); l(12f, 3.6f, 14.4f, 6.4f)
            l(12f, 20.4f, 9.6f, 17.6f); l(12f, 20.4f, 14.4f, 17.6f)
            l(3.6f, 12f, 6.4f, 9.6f); l(3.6f, 12f, 6.4f, 14.4f)
            l(20.4f, 12f, 17.6f, 9.6f); l(20.4f, 12f, 17.6f, 14.4f)
        }
        XuanGlyphType.Sync -> {
            a(11f, 11f, 7.2f, st = -30f, sw = 200f)
            a(11f, 11f, 7.2f, st = 150f, sw = 200f)
            l(15.4f, 2.6f, 18.4f, 6.2f); l(18.4f, 6.2f, 13.6f, 6.8f)
            l(6.6f, 21.4f, 3.6f, 17.8f); l(3.6f, 17.8f, 8.4f, 17.2f)
        }
        XuanGlyphType.Link -> {
            a(9.4f, 12f, 5.4f, st = 40f, sw = 100f)
            a(14.6f, 12f, 5.4f, st = 220f, sw = 100f)
            l(9.4f, 12f, 14.6f, 12f)
        }
    }
}
