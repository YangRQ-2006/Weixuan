package cn.yangrq.weixuan.ui.design.morph

/*
 * 归一化（MorphNormalize.kt）：任意 SVG 图元 → cubic 贝塞尔段
 *
 * 移植自 guillermolg00/morphicons（MIT）src/core/normalize.ts 与 serialize.ts；
 * 本地蓝本：docs/third-party/morphicons/src/core/。
 *
 * 规则：直线 → 共线控制点（⅓ 与 ⅔）；二次曲线 → 精确升阶；
 * 椭圆弧 → 中心参数化（SVG 规范附录 F.6），切片 ≤ 90°，α = 4/3·tan(Δθ/4)；
 * circle/ellipse/rect/polyline/polygon → 直线与四分之一椭圆。
 */

/** 四分之一圆的控制点偏移：(4/3)·tan(π/8) ≈ 0.55228475（非 const：含三角函数求值）。 */
internal val MORPH_KAPPA: Double = (4.0 / 3.0) * kotlin.math.tan(Math.PI / 8)

private const val TAU = 2 * Math.PI

/** cubic 累加器：从起点 (x0,y0) 开始，逐段追加 cubic 控制点。 */
private class CubicBuilder(private val x0: Double, private val y0: Double) {
    private val pts = ArrayList<Double>(32).apply { add(x0); add(y0) }
    private var cx = x0
    private var cy = y0

    fun cubic(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) {
        pts.add(x1); pts.add(y1); pts.add(x2); pts.add(y2); pts.add(x); pts.add(y)
        cx = x
        cy = y
    }

    fun line(x: Double, y: Double) {
        if (kotlin.math.abs(x - cx) < 1e-12 && kotlin.math.abs(y - cy) < 1e-12) return // 退化段
        cubic(
            cx + (x - cx) / 3.0,
            cy + (y - cy) / 3.0,
            cx + (2.0 * (x - cx)) / 3.0,
            cy + (2.0 * (y - cy)) / 3.0,
            x,
            y,
        )
    }

    fun quad(x1: Double, y1: Double, x: Double, y: Double) {
        cubic(
            cx + (2.0 / 3.0) * (x1 - cx),
            cy + (2.0 / 3.0) * (y1 - cy),
            x + (2.0 / 3.0) * (x1 - x),
            y + (2.0 / 3.0) * (y1 - y),
            x,
            y,
        )
    }

    /** 椭圆弧 → cubic。端点 → 中心参数化，按 SVG 规范附录 F.6。 */
    fun arc(
        rx0: Double,
        ry0: Double,
        rotDeg: Double,
        large: Int,
        sweep: Int,
        x: Double,
        y: Double,
    ) {
        val x1 = cx
        val y1 = cy
        if (kotlin.math.abs(x - x1) < 1e-12 && kotlin.math.abs(y - y1) < 1e-12) return // F.6.2
        var rx = kotlin.math.abs(rx0)
        var ry = kotlin.math.abs(ry0)
        if (rx < 1e-12 || ry < 1e-12) {
            line(x, y) // F.6.6：零半径 → 直线
            return
        }
        val phi = rotDeg * Math.PI / 180.0
        val cosP = kotlin.math.cos(phi)
        val sinP = kotlin.math.sin(phi)
        val hx = (x1 - x) / 2.0
        val hy = (y1 - y) / 2.0
        val x1p = cosP * hx + sinP * hy
        val y1p = -sinP * hx + cosP * hy
        // F.6.6：半径不足时等比放大
        val lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lam > 1.0) {
            val s = kotlin.math.sqrt(lam)
            rx *= s
            ry *= s
        }
        // F.6.5：圆心
        val rx2 = rx * rx
        val ry2 = ry * ry
        val xp2 = x1p * x1p
        val yp2 = y1p * y1p
        var rad = (rx2 * ry2 - rx2 * yp2 - ry2 * xp2) / (rx2 * yp2 + ry2 * xp2)
        if (rad < 0.0) rad = 0.0
        val co = (if (large == sweep) -1.0 else 1.0) * kotlin.math.sqrt(rad)
        val cxp = (co * rx * y1p) / ry
        val cyp = (-co * ry * x1p) / rx
        val ccx = cosP * cxp - sinP * cyp + (x1 + x) / 2.0
        val ccy = sinP * cxp + cosP * cyp + (y1 + y) / 2.0
        val th1 = kotlin.math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
        var dth = kotlin.math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx) - th1
        if (sweep == 0 && dth > 0) dth -= TAU
        else if (sweep == 1 && dth < 0) dth += TAU
        // 切成 ≤ 90° 的弧片，每片转 cubic：α = 4/3·tan(δ/4)
        val slices = maxOf(1, kotlin.math.ceil(kotlin.math.abs(dth) / (Math.PI / 2.0) - 1e-9).toInt())
        val delta = dth / slices
        val alpha = (4.0 / 3.0) * kotlin.math.tan(delta / 4.0)
        val ex = { t: Double -> ccx + rx * kotlin.math.cos(t) * cosP - ry * kotlin.math.sin(t) * sinP }
        val ey = { t: Double -> ccy + rx * kotlin.math.cos(t) * sinP + ry * kotlin.math.sin(t) * cosP }
        val dx = { t: Double -> -rx * kotlin.math.sin(t) * cosP - ry * kotlin.math.cos(t) * sinP }
        val dy = { t: Double -> -rx * kotlin.math.sin(t) * sinP + ry * kotlin.math.cos(t) * cosP }
        var t0 = th1
        var p0x = x1
        var p0y = y1
        for (s in 1..slices) {
            val t1 = th1 + delta * s
            // 最后一片直接用给定终点，避免浮点漂移
            val p1x = if (s == slices) x else ex(t1)
            val p1y = if (s == slices) y else ey(t1)
            cubic(
                p0x + alpha * dx(t0),
                p0y + alpha * dy(t0),
                p1x - alpha * dx(t1),
                p1y - alpha * dy(t1),
                p1x,
                p1y,
            )
            t0 = t1
            p0x = p1x
            p0y = p1y
        }
    }

    /** 结束子路径。闭合时补一条显式收尾段；不足一段（< 8 个数）返回 null。 */
    fun finish(closed: Boolean): CubicPath? {
        if (closed) line(x0, y0)
        if (pts.size < 8) return null
        val arr = DoubleArray(pts.size)
        for (i in pts.indices) arr[i] = pts[i]
        return CubicPath(arr, closed)
    }
}

private fun lowerSubpath(raw: RawSubpath): CubicPath? {
    val b = CubicBuilder(raw.x0, raw.y0)
    for (s in raw.segs) {
        when (s) {
            is RawLine -> b.line(s.x, s.y)
            is RawCubic -> b.cubic(s.x1, s.y1, s.x2, s.y2, s.x, s.y)
            is RawQuad -> b.quad(s.x1, s.y1, s.x, s.y)
            is RawArc -> b.arc(s.rx, s.ry, s.rotDeg, s.large, s.sweep, s.x, s.y)
        }
    }
    return b.finish(raw.closed)
}

private fun attrNum(attrs: Map<String, Any>, key: String, fallback: Double = 0.0): Double {
    val v = attrs[key] ?: return fallback
    val x = (v as? Number)?.toDouble() ?: v.toString().toDoubleOrNull() ?: return fallback
    return if (x.isFinite()) x else fallback
}

private fun parsePoints(v: Any?): List<Double> {
    val s = v?.toString()?.trim() ?: ""
    if (s.isEmpty()) return emptyList()
    val nums = s.split(Regex("[\\s,]+")).mapNotNull { it.toDoubleOrNull() }
    if (nums.size % 2 != 0) return emptyList()
    return nums
}

private fun polyPath(nums: List<Double>, closed: Boolean): CubicPath? {
    if (nums.size < 4) return null
    val b = CubicBuilder(nums[0], nums[1])
    var i = 2
    while (i + 1 < nums.size) {
        b.line(nums[i], nums[i + 1])
        i += 2
    }
    return b.finish(closed)
}

private fun ellipsePath(cx: Double, cy: Double, rx: Double, ry: Double): CubicPath? {
    if (rx < 1e-12 || ry < 1e-12) return null
    val kx = MORPH_KAPPA * rx
    val ky = MORPH_KAPPA * ry
    val e = cx + rx // 东
    val w = cx - rx // 西
    val s = cy + ry // 南
    val n = cy - ry // 北
    val b = CubicBuilder(e, cy)
    b.cubic(e, cy + ky, cx + kx, s, cx, s)
    b.cubic(cx - kx, s, w, cy + ky, w, cy)
    b.cubic(w, cy - ky, cx - kx, n, cx, n)
    b.cubic(cx + kx, n, e, cy - ky, e, cy)
    return b.finish(true)
}

private fun rectPath(attrs: Map<String, Any>): CubicPath? {
    val x = attrNum(attrs, "x")
    val y = attrNum(attrs, "y")
    val w = attrNum(attrs, "width")
    val h = attrNum(attrs, "height")
    if (w < 1e-12 || h < 1e-12) return null
    // SVG 规则：rx/ry 单给时互拷；并钳制到边长一半
    var rx = attrNum(attrs, "rx", Double.NaN)
    var ry = attrNum(attrs, "ry", Double.NaN)
    if (rx.isNaN()) rx = if (ry.isNaN()) 0.0 else ry
    if (ry.isNaN()) ry = rx
    rx = rx.coerceIn(0.0, w / 2.0)
    ry = ry.coerceIn(0.0, h / 2.0)
    if (rx < 1e-12 || ry < 1e-12) {
        return polyPath(listOf(x, y, x + w, y, x + w, y + h, x, y + h), true)
    }
    // 每个圆角「直线↔弧」的衔接点
    val xa = x + rx
    val xb = x + w - rx
    val xr = x + w
    val ya = y + ry
    val yb = y + h - ry
    val yd = y + h
    val kx = MORPH_KAPPA * rx
    val ky = MORPH_KAPPA * ry
    val b = CubicBuilder(xa, y)
    b.line(xb, y)
    b.cubic(xb + kx, y, xr, ya - ky, xr, ya)
    b.line(xr, yb)
    b.cubic(xr, yb + ky, xb + kx, yd, xb, yd)
    b.line(xa, yd)
    b.cubic(xa - kx, yd, x, yb + ky, x, yb)
    b.line(x, ya)
    b.cubic(x, ya - ky, xa - kx, y, xa, y)
    return b.finish(true)
}

/** 图标输入 → cubic 子路径列表（仅保留非空子路径）。 */
internal fun iconToCubics(input: MorphIconInput): List<CubicPath> {
    val out = ArrayList<CubicPath>()
    when (input) {
        is MorphIconD -> for (s in parsePathD(input.d)) lowerSubpath(s)?.let { out.add(it) }
        is MorphIconNodes -> {
            for (node in input.nodes) {
                val tag = node.tag
                val attrs = node.attrs
                when (tag) {
                    "path" -> for (s in parsePathD(attrs["d"]?.toString() ?: "")) {
                        lowerSubpath(s)?.let { out.add(it) }
                    }
                    "line" -> {
                        val b = CubicBuilder(attrNum(attrs, "x1"), attrNum(attrs, "y1"))
                        b.line(attrNum(attrs, "x2"), attrNum(attrs, "y2"))
                        b.finish(false)?.let { out.add(it) }
                    }
                    "circle" -> {
                        val r = attrNum(attrs, "r")
                        ellipsePath(attrNum(attrs, "cx"), attrNum(attrs, "cy"), r, r)?.let { out.add(it) }
                    }
                    "ellipse" -> ellipsePath(
                        attrNum(attrs, "cx"),
                        attrNum(attrs, "cy"),
                        attrNum(attrs, "rx"),
                        attrNum(attrs, "ry"),
                    )?.let { out.add(it) }
                    "rect" -> rectPath(attrs)?.let { out.add(it) }
                    "polyline" -> polyPath(parsePoints(attrs["points"]), false)?.let { out.add(it) }
                    "polygon" -> polyPath(parsePoints(attrs["points"]), true)?.let { out.add(it) }
                    // 其他标签忽略（上游会抛错；这里更宽容，避免整枚图标失效）
                }
            }
        }
    }
    return out
}

/** 便捷重载：直接给 `d` 字符串。 */
internal fun iconToCubics(d: String): List<CubicPath> = iconToCubics(MorphIconD(d))

private fun parseViewBox(vb: String): DoubleArray {
    val v = vb.trim().split(Regex("[\\s,]+")).mapNotNull { it.toDoubleOrNull() }
    val minX = v.getOrNull(0) ?: Double.NaN
    val minY = v.getOrNull(1) ?: Double.NaN
    val w = v.getOrNull(2) ?: Double.NaN
    val h = v.getOrNull(3) ?: Double.NaN
    if (w <= 0.0 || h <= 0.0 || !minX.isFinite() || !minY.isFinite()) {
        throw IllegalArgumentException("morphicons: invalid viewBox \"$vb\"")
    }
    return doubleArrayOf(minX, minY, w, h)
}

/**
 * 把画在 [viewBox] 坐标系上的图标重排到 [grid]（默认 24）格上：居中、保持长宽比 ——
 * 即 SVG 的 `xMidYMid meet` 规则。变形的两端必须处在同一坐标系，否则 Procrustes
 * 会把尺度/偏移差误读成旋转。只需在模块级调用一次（不要每帧调用）。
 */
internal fun fitIcon(input: MorphIconInput, viewBox: String, grid: Double = 24.0): String {
    val v = parseViewBox(viewBox)
    val minX = v[0]
    val minY = v[1]
    val w = v[2]
    val h = v[3]
    val s = minOf(grid / w, grid / h)
    val tx = (grid - w * s) / 2.0 - minX * s
    val ty = (grid - h * s) / 2.0 - minY * s
    val paths = iconToCubics(input)
    for (p in paths) {
        val pts = p.pts
        var i = 0
        while (i < pts.size) {
            pts[i] = pts[i] * s + tx
            pts[i + 1] = pts[i + 1] * s + ty
            i += 2
        }
    }
    return cubicsToPathD(paths)
}

/** 便捷重载：直接给 `d` 字符串。 */
internal fun fitIcon(d: String, viewBox: String, grid: Double = 24.0): String =
    fitIcon(MorphIconD(d), viewBox, grid)

private fun fmtCanon(v: Double): String {
    // 4 位小数量化：吸附浮点尾差，保证同输入同字节
    val r = Math.round(v * 1e4) / 1e4
    return if (r == Math.floor(r) && kotlin.math.abs(r) < 1e15) {
        r.toLong().toString()
    } else {
        r.toString()
    }
}

/** cubic 子路径 → 规范 `d` 字符串（4 位小数量化，fitIcon 使用）。 */
internal fun cubicsToPathD(paths: List<CubicPath>): String {
    val sb = StringBuilder()
    for (p in paths) {
        val pts = p.pts
        sb.append('M').append(fmtCanon(pts[0])).append(' ').append(fmtCanon(pts[1]))
        var i = 2
        while (i < pts.size) {
            sb.append('C')
                .append(fmtCanon(pts[i])).append(' ')
                .append(fmtCanon(pts[i + 1])).append(' ')
                .append(fmtCanon(pts[i + 2])).append(' ')
                .append(fmtCanon(pts[i + 3])).append(' ')
                .append(fmtCanon(pts[i + 4])).append(' ')
                .append(fmtCanon(pts[i + 5]))
            i += 6
        }
        if (p.closed) sb.append('Z')
    }
    return sb.toString()
}
