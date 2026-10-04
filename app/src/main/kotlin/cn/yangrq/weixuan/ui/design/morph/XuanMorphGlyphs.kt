package cn.yangrq.weixuan.ui.design.morph

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 「爻形」字形集（XuanMorphGlyphs.kt）
 *
 * 这里每一枚字形都是 24 格坐标系的纯描边 `d` 数据，几何与
 * cn.yangrq.weixuan.ui.design.XuanGlyphs 里同名爻线图形**逐点一致**
 * （Send/Stop/Chevron/Check/Plus/Close/Play/Search/Refresh/Mic 等），
 * 因此变形图标可以无缝混入既有爻线图标体系。
 *
 * 用法：XuanMorphIcon(if (streaming) XuanMorphGlyph.Stop else XuanMorphGlyph.Send)
 *  → 目标切换时自动沿弹簧物理变形过去（打断友好）。
 */

internal enum class XuanMorphGlyph(internal val d: String) {
    /** 发送（向上箭头）。 */
    Send("M12 20L12 5M12 5L6.8 10.6M12 5L17.2 10.6"),

    /**
     * 停止（实心方框描边）。
     * 方框内缩到 9..15：变形图标固定在 16dp 画布上绘制（旧实现是 10dp 画布），
     * 内缩后物理尺寸与原 XuanGlyph.Stop@10dp 一致（约 4dp 方块）。
     */
    Stop("M9 9L15 9L15 15L9 15Z"),

    /** 右箭头。 */
    ChevronRight("M9 5.2L15.8 12L9 18.8"),

    /** 下箭头。 */
    ChevronDown("M5.2 9L12 15.8L18.8 9"),

    /** 左箭头。 */
    ChevronLeft("M15.8 5.2L9 12L15.8 18.8"),

    /** 新增。 */
    Plus("M12 4.6L12 19.4M4.6 12L19.4 12"),

    /** 关闭。 */
    Close("M6 6L18 18M18 6L6 18"),

    /** 完成。 */
    Check("M4.6 12.8L9.6 17.6L19.6 6.8"),

    /** 播放（闭合三角）。 */
    Play("M7 4.6L7 19.4L19 12Z"),

    /** 搜索（圆 + 手柄）。 */
    Search("M17.2 10.8A6.4 6.4 0 1 1 4.4 10.8A6.4 6.4 0 1 1 17.2 10.8M15.6 15.6L20 20"),

    /** 刷新（290° 弧 + 箭头）。 */
    Refresh("M15.8 5.42A7.6 7.6 0 1 1 7.11 6.18M15.4 3.6L18.6 8.4L13.2 9"),

    /** 麦克风。 */
    Mic(
        "M12 4.2C14.4 4.2 15.4 6 15.4 8L15.4 12.6C15.4 15 8.6 15 8.6 12.6L8.6 8" +
            "C8.6 6 9.6 4.2 12 4.2ZM17.83 14.52A6.2 6.2 0 0 1 6.17 14.52M12 18.6L12 21",
    ),
    ;
}

/**
 * 爻形变形图标：目标字形变化时自动变形（弹簧 + Procrustes 对应）。
 * 视觉约定与 XuanGlyph 完全一致（24 格、圆头描边、默认 2dp）。
 */
@Composable
internal fun XuanMorphIcon(
    glyph: XuanMorphGlyph,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    strokeWidth: Dp = 2.dp,
) {
    MorphIcon(
        d = glyph.d,
        modifier = modifier,
        tint = tint,
        strokeWidth = strokeWidth,
        grid = 24f,
        preset = MorphSpringPreset.Snappy,
    )
}
