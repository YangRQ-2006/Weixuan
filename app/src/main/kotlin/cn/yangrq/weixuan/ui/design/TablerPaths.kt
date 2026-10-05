package cn.yangrq.weixuan.ui.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import cn.yangrq.weixuan.ui.design.tabler.TablerGlyph

/**
 * Tabler 图标的渲染层（微玄图标底座 · 2026-10-04）。
 *
 * **为什么不用 `com.composables:icons-tabler-outline-android` 资源 AAR**
 *
 * 实测：该 AAR 的 `classes.jar` 为空，是纯资源包 —— 内含 4,985 个 `res/drawable/tabler_ic_*.xml`
 * （2.2 MB），且描边 `strokeWidth=2.0` 烧死在 XML 里，渲染到 16dp 时只剩 1.33dp，无法补偿。
 * 因此改为内联 [TablerGlyph]（42 枚 / 125 条路径 / 约 9 KB），描边宽度可按渲染尺寸参数化。
 *
 * **描边光学补偿**
 *
 * 等比缩放会让小尺寸图标的线条变弱（24dp 的 2.0 描边到 16dp 只剩 1.33dp），
 * 而自绘爻线是恒定 2dp 线宽，两者并排会明显一强一弱。
 * 这里把「可见线宽」尽量维持恒定：24 空间线宽按 `基准 × 24 / 渲染尺寸` 放大，
 * 并封顶 [MaxOpticalBoost] 倍，避免小尺寸糊成一团。
 * （Material Symbols 的 optical size 轴是同一思路。）
 */
internal object TablerPaths {

    /** 上游规格：24 单位画布上的 2.0 描边。 */
    const val BaseStroke24: Float = 2.0f

    /** 描边补偿上限系数（2.0 最多放大到 2.5）。 */
    const val MaxOpticalBoost: Float = 1.25f

    /** 上游画布边长（单位）。 */
    private const val Viewport = 24f

    private val cache = HashMap<TablerGlyph, List<Path>>()

    /** 解析并缓存 24 单位空间下的路径（每枚图标只解析一次）。 */
    fun paths(glyph: TablerGlyph): List<Path> = cache.getOrPut(glyph) {
        glyph.paths.map { PathParser().parsePathString(it).toPath() }
    }

    /**
     * 计算 24 单位空间下应使用的描边宽度。
     *
     * @param renderDp 图标在屏幕上的实际边长（dp）
     * @param baseStroke24 基准描边（24 单位空间）
     * @param boost 补偿上限系数
     */
    fun strokeFor(
        renderDp: Float,
        baseStroke24: Float = BaseStroke24,
        boost: Float = MaxOpticalBoost,
    ): Float {
        if (renderDp <= 0f) return baseStroke24
        val constant = baseStroke24 * Viewport / renderDp
        return constant.coerceIn(baseStroke24, baseStroke24 * boost)
    }
}

/**
 * 把 [glyph] 画进当前 DrawScope。
 *
 * 画布按 `size.minDimension / 24` 等比缩放，几何与描边一起缩放，
 * 因此这里可以直接使用上游 0..24 的坐标，描边用 [TablerPaths.strokeFor] 换算。
 */
internal fun DrawScope.drawTablerGlyph(
    glyph: TablerGlyph,
    color: Color,
    strokeWidth24: Float,
) {
    val k = size.minDimension / 24f
    if (k <= 0f) return
    val stroke = Stroke(width = strokeWidth24, cap = StrokeCap.Round, join = StrokeJoin.Round)
    scale(scaleX = k, scaleY = k, pivot = Offset.Zero) {
        TablerPaths.paths(glyph).forEach { path -> drawPath(path, color, style = stroke) }
    }
}
