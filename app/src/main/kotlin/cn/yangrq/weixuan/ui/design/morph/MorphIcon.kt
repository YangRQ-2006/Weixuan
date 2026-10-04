package cn.yangrq.weixuan.ui.design.morph

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 变形图标渲染层（MorphIcon.kt）
 *
 * 算法来源：guillermolg00/morphicons（MIT），本地蓝本 docs/third-party/morphicons/。
 * 对应上游 src/dom/index.ts 的驱动语义（lazy driver / 打断快照 / 安定吸附），
 * 渲染目标从 DOM `<path>` 换成 Compose Canvas。
 *
 * 生命周期契约：
 *  - 目标 d 变化 → 重定目标（飞行中则把当前中间形状当新计划源，速度保留）→ 弹簧重新起跑；
 *  - 飞行中每帧画「多段线」（N 点/子路径）；安定后改画目标图形的 canonical cubic
 *    曲线（静止时是真曲线，不是折线），与静态图标逐像素一致。
 *  - 连点/连切不打断感知：速度被保留，画面永不跳变。
 */

/** 重采样结果的进程级 LRU 缓存（打断时频繁重建计划，避免重复解析/重采样）。 */
private val MORPH_SAMPLE_CACHE = object : LinkedHashMap<String, List<Sampled>>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Sampled>>): Boolean =
        size > 128
}

private fun cachedSamples(d: String, n: Int): List<Sampled> {
    if (n != MORPH_DEFAULT_POINTS) return resampleIcon(d, n)
    return MORPH_SAMPLE_CACHE.getOrPut(d) { resampleIcon(d, n) }
}

/**
 * 单枚图标的变形状态机（对应上游 createMorph 的 retarget/snapshot/settle）。
 * 仅在主线程访问。
 */
internal class MorphState(private val n: Int) {
    var plan: MorphPlan? = null
        private set
    var out: Array<DoubleArray>? = null
        private set
    var closed: BooleanArray? = null
        private set
    var targetD: String = ""
        private set
    var canonical: List<CubicPath> = emptyList()
        private set
    var flying: Boolean = false
        private set
    val spring = MorphSpring()

    /** 直接安定在目标图形上（挂载时不做动画）。 */
    fun restAt(d: String) {
        targetD = d
        canonical = iconToCubics(d)
        plan = null
        out = null
        closed = null
        spring.x = 1.0
        spring.v = 0.0
        flying = false
    }

    /** 打断快照：当前帧的中间形状（已是 N 点/子路径）直接充当新计划的源。 */
    private fun snapshot(): List<Sampled> {
        val p = plan
        val o = out
        val c = closed
        if (p == null || o == null || c == null) return cachedSamples(targetD, n)
        return List(p.items.size) { k -> Sampled(o[k].copyOf(), c[k]) }
    }

    /**
     * 重定目标。飞行中从中间形状续飞（速度保留）；静止时从旧目标起飞。
     * 目标相同且已安定 → 无操作（上游 createMorph 同语义）。
     */
    fun retarget(d: String) {
        if (d == targetD && !flying) return
        val src = if (flying) snapshot() else cachedSamples(targetD, n)
        val dst = cachedSamples(d, n)
        val newPlan = buildMorphPlan(src, dst)
        plan = newPlan
        out = allocOutputs(newPlan)
        closed = BooleanArray(newPlan.items.size) { newPlan.items[it].closed }
        targetD = d
        canonical = iconToCubics(d)
        spring.start()
        flying = true
    }

    /** 推进 dt 秒。安定后清空计划并吸附到 canonical；返回 true 表示安定。 */
    fun step(dt: Double): Boolean {
        val p = plan
        val o = out
        if (p == null || o == null) return true
        val settled = spring.step(dt)
        interpPolar(p, spring.x, o)
        if (settled) {
            flying = false
            plan = null
            out = null
            closed = null
            spring.x = 1.0
            spring.v = 0.0
        }
        return settled
    }
}

/**
 * 变形图标：给一枚 24 格描边矢量（`d` 字符串），目标变化时沿弹簧物理
 * 变形过去。任意两个描边图标之间都可以互相变形（满射对应 + Procrustes
 * 对齐 + 极坐标插值），与 [cn.yangrq.weixuan.ui.design.XuanGlyph] 同一
 * 视觉语言（24 格、圆头描边、默认 2dp）。
 *
 * @param d      目标图标的路径数据（24 格坐标系）
 * @param tint   颜色；[Color.Unspecified] 时跟随 Miuix 主题前景色
 * @param grid   图标坐标系边长（默认 24，对应爻线系统）
 * @param preset 弹簧预设（Snappy ≈ 上游默认手感）
 */
@Composable
internal fun MorphIcon(
    d: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    strokeWidth: Dp = 2.dp,
    grid: Float = 24f,
    preset: MorphSpringPreset = MorphSpringPreset.Snappy,
) {
    val state = remember { MorphState(MORPH_DEFAULT_POINTS) }
    // 首次组合：直接安定在目标上（挂载不动画），避免首帧空绘制
    if (state.targetD.isEmpty()) state.restAt(d)
    // 画布失效信号：只在 draw 阶段读取，弹簧每帧只触发重绘，不触发重组
    val progress = remember { mutableFloatStateOf(1f) }
    val path = remember { Path() }

    LaunchedEffect(d, preset) {
        state.spring.config(preset.stiffness.toDouble(), preset.damping.toDouble())
        state.retarget(d)
        var last = withFrameNanos { it }
        while (state.flying) {
            val now = withFrameNanos { it }
            val dt = ((now - last).coerceAtLeast(0L)) / 1e9
            last = now
            val settled = state.step(dt)
            progress.floatValue = state.spring.x.toFloat()
            if (settled) {
                progress.floatValue = 1f
                break
            }
        }
    }

    val color = if (tint == Color.Unspecified) MiuixTheme.colorScheme.onBackground else tint

    Canvas(modifier = modifier) {
        progress.floatValue // 订阅：弹簧推进时仅重绘本 Canvas
        val s = size.minDimension / grid
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        path.rewind()
        val p = state.plan
        val o = state.out
        val c = state.closed
        if (state.flying && p != null && o != null && c != null) {
            // 飞行中：极坐标插值结果按多段线绘制
            for (k in p.items.indices) {
                val pts = o[k]
                path.moveTo((pts[0] * s).toFloat(), (pts[1] * s).toFloat())
                for (i in 1 until p.n) {
                    path.lineTo((pts[2 * i] * s).toFloat(), (pts[2 * i + 1] * s).toFloat())
                }
                if (c[k]) path.close()
            }
        } else {
            // 安定：画目标图形的真实 cubic 曲线（与静态图标一致）
            for (cp in state.canonical) {
                val pts = cp.pts
                path.moveTo((pts[0] * s).toFloat(), (pts[1] * s).toFloat())
                var i = 2
                while (i < pts.size) {
                    path.cubicTo(
                        (pts[i] * s).toFloat(),
                        (pts[i + 1] * s).toFloat(),
                        (pts[i + 2] * s).toFloat(),
                        (pts[i + 3] * s).toFloat(),
                        (pts[i + 4] * s).toFloat(),
                        (pts[i + 5] * s).toFloat(),
                    )
                    i += 6
                }
                if (cp.closed) path.close()
            }
        }
        drawPath(path, color, style = stroke)
    }
}
