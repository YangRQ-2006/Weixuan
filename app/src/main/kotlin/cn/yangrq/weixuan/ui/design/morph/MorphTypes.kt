package cn.yangrq.weixuan.ui.design.morph

/*
 * 微玄「爻形」动态图标 · 核心类型定义
 *
 * 算法源头：morphicons —— https://github.com/guillermolg00/morphicons （MIT）
 * 本地蓝本：docs/third-party/morphicons/src/core/（TypeScript 原文，只读参考，不参与编译）
 *
 * 本文件只放共享类型与常量；算法实现分散在 MorphParse / MorphNormalize /
 * MorphResample / MorphPlan / MorphInterpolate 中，Compose 渲染层见 MorphIcon.kt。
 */

/** 图标默认采样点数：每条子路径都被重采样成同样多的点，实现「任意图形 → 任意图形」。 */
internal const val MORPH_DEFAULT_POINTS: Int = 64

/**
 * 拐角判定阈值（弧度）：相邻两段切向方向差超过该值即视为拐角，
 * 重采样时拐角必须落在采样点上，否则直角会被弧长均匀采样「抹圆」。
 */
internal const val MORPH_CORNER_THRESHOLD: Double = Math.PI / 8

/**
 * 一条三次贝塞尔子路径。
 *
 * [pts] 为扁平坐标数组：`[x0,y0, c1x,c1y, c2x,c2y, x1,y1, c1x,c1y, c2x,c2y, x2,y2, …]`，
 * 首点之后每 6 个数为一段 cubic。[closed] 表示原路径带 `Z` 闭合指令。
 */
internal class CubicPath(val pts: DoubleArray, val closed: Boolean)

/**
 * 重采样后的形状：每条子路径固定 [MORPH_DEFAULT_POINTS] 个等弧长点。
 *
 * [pts] 为扁平坐标数组 `[x0,y0, x1,y1, …]`，长度 = 点数 × 2。
 */
internal class Sampled(val pts: DoubleArray, val closed: Boolean)

// ---------------------------------------------------------------------------
// 路径解析产物（parsePathD）：保留原始指令类型，交给 normalize 转 cubic
// ---------------------------------------------------------------------------

internal sealed interface RawSeg

internal class RawLine(val x: Double, val y: Double) : RawSeg

internal class RawCubic(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val x: Double,
    val y: Double,
) : RawSeg

internal class RawQuad(
    val x1: Double,
    val y1: Double,
    val x: Double,
    val y: Double,
) : RawSeg

internal class RawArc(
    val rx: Double,
    val ry: Double,
    val rotDeg: Double,
    val large: Int,
    val sweep: Int,
    val x: Double,
    val y: Double,
) : RawSeg

/**
 * 一条原始子路径（尚未转 cubic）：起点 [x0],[y0] + 若干段 + 是否闭合。
 * 上游为 24 格画布（lucide 风格）设计，坐标系与 Android Canvas 一致（y 向下）。
 */
internal class RawSubpath(
    val x0: Double,
    val y0: Double,
    val segs: List<RawSeg>,
    val closed: Boolean,
)

// ---------------------------------------------------------------------------
// 非 d 字符串形态的图标输入（<circle>/<rect>/<line>/<polyline> 等），用于导入其他图标库
// ---------------------------------------------------------------------------

/** 一个 SVG 风格图形节点：tag 如 "circle"/"rect"/"path"，attrs 值可为 String / Double / Int。 */
internal class IconNode(val tag: String, val attrs: Map<String, Any>)

internal sealed interface MorphIconInput

/** 直接给 `d` 字符串。 */
internal class MorphIconD(val d: String) : MorphIconInput

/** 给节点列表（等价于一段 SVG 片段），由 normalize 展开成 cubic。 */
internal class MorphIconNodes(val nodes: List<IconNode>) : MorphIconInput

// ---------------------------------------------------------------------------
// 弹簧预设
// ---------------------------------------------------------------------------

/**
 * 变形动画的弹簧预设，参数与上游 `src/core/spring.ts` 一致（mass = 1 的半隐式欧拉弹簧）。
 *
 * Compose 的 `spring(stiffness, dampingRatio)` 使用同一套物理量（mass = 1），
 * 所以 [stiffness] 可直接透传，[dampingRatio] 由 ζ = c / (2√k) 换算，
 * 从而在 Compose 侧复现上游手感（含过冲与速度保持打断）。
 */
internal enum class MorphSpringPreset(val stiffness: Float, val damping: Float) {
    /** k = 170, c = 26 —— ζ ≈ 0.997，几乎临界阻尼，最稳。 */
    Smooth(170f, 26f),

    /** k = 420, c = 30 —— ζ ≈ 0.73，干脆利落且有轻微回弹，图标默认。 */
    Snappy(420f, 30f),

    /** k = 300, c = 14 —— ζ ≈ 0.40，明显回弹，适合强调性交互。 */
    Bouncy(300f, 14f),
    ;

    /** ζ = c / (2√k)，可直接喂给 Compose 的 `spring(dampingRatio = …)`。 */
    val dampingRatio: Float
        get() = (damping / (2f * kotlin.math.sqrt(stiffness)))
}
