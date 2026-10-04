package cn.yangrq.weixuan.ui.design.morph

/*
 * 弧长重采样（MorphResample.kt）——带拐角锚定
 *
 * 移植自 guillermolg00/morphicons（MIT）src/core/resample.ts；
 * 本地蓝本：docs/third-party/morphicons/src/core/resample.ts。
 *
 * cubic 的弧长没有闭式解：|B′(t)| 用 8 点 Gauss-Legendre 积分。
 * 拐角（切向不连续超过角度阈值）必须作为精确采样点锚定；其余点按弧长
 * 在拐角之间分配 —— 最大余数法整数分摊，每段至少 1 个区间，
 * 总数精确等于 N−1（闭合时为 N）。
 */

// Gauss-Legendre，[−1,1] 上 8 点 —— 节点对称，只存一半
private val GX = doubleArrayOf(
    0.18343464249564978,
    0.525532409916329,
    0.7966664774136267,
    0.9602898564975363,
)
private val GW = doubleArrayOf(
    0.362683783378362,
    0.31370664587788727,
    0.22238103445337448,
    0.10122853629037626,
)

/** 第 k 段的速度 |B′(t)|。B′(t) = 3(1−t)²(P₁−P₀) + 6(1−t)t(P₂−P₁) + 3t²(P₃−P₂)。 */
private fun speed(p: DoubleArray, k: Int, t: Double): Double {
    val i = 6 * k
    val u = 1.0 - t
    val c0 = 3 * u * u
    val c1 = 6 * u * t
    val c2 = 3 * t * t
    val dx = c0 * (p[i + 2] - p[i]) + c1 * (p[i + 4] - p[i + 2]) + c2 * (p[i + 6] - p[i + 4])
    val dy = c0 * (p[i + 3] - p[i + 1]) + c1 * (p[i + 5] - p[i + 3]) + c2 * (p[i + 7] - p[i + 5])
    return kotlin.math.hypot(dx, dy)
}

/** 第 k 段上 ∫₀^t1 |B′|，Gauss-Legendre 积分。 */
private fun segLen(p: DoubleArray, k: Int, t1: Double = 1.0): Double {
    val half = t1 / 2.0
    var s = 0.0
    for (j in 0..3) {
        s += GW[j] * (speed(p, k, half + half * GX[j]) + speed(p, k, half - half * GX[j]))
    }
    return s * half
}

/** 第 k 段在 t 处的 Bernstein 求值 → 写入 out[o]、out[o+1]。 */
private fun bernsteinAt(p: DoubleArray, k: Int, t: Double, out: DoubleArray, o: Int) {
    val i = 6 * k
    val u = 1.0 - t
    val b0 = u * u * u
    val b1 = 3 * u * u * t
    val b2 = 3 * u * t * t
    val b3 = t * t * t
    out[o] = b0 * p[i] + b1 * p[i + 2] + b2 * p[i + 4] + b3 * p[i + 6]
    out[o + 1] = b0 * p[i + 1] + b1 * p[i + 3] + b2 * p[i + 5] + b3 * p[i + 7]
}

/** 第 k 段端点的切向。[atEnd]=true 取 P₃ 处的出切（P₃−P₂），否则取 P₀ 处的入切（P₁−P₀）。
 *  退化时回退到下一个控制点；全退化返回 null。 */
private fun tangent(p: DoubleArray, k: Int, atEnd: Boolean): Pair<Double, Double>? {
    val i = 6 * k
    val b = if (atEnd) i + 6 else i // 基点（端点）
    val s = if (atEnd) -1.0 else 1.0
    val js = if (atEnd) intArrayOf(4, 2, 0) else intArrayOf(2, 4, 6)
    for (j in js) {
        val dx = s * (p[i + j] - p[b])
        val dy = s * (p[i + j + 1] - p[b + 1])
        if (dx * dx + dy * dy > 1e-18) return dx to dy
    }
    return null
}

/**
 * 拐角检测：返回「切向不连续超过阈值」的段边界（以该段索引计）。
 * 闭合路径会包含收尾接缝（边界 = 第一条活动段）。
 */
internal fun detectCorners(path: CubicPath, threshold: Double = MORPH_CORNER_THRESHOLD): List<Int> {
    val p = path.pts
    val m = (p.size / 2 - 1) / 3
    val active = ArrayList<Int>()
    for (k in 0 until m) if (segLen(p, k) > 1e-9) active.add(k)
    if (active.isEmpty()) return emptyList()
    val corners = HashSet<Int>()
    fun test(a: Int, b: Int) {
        val u = tangent(p, a, true) ?: return
        val v = tangent(p, b, false) ?: return
        val ang = kotlin.math.abs(
            kotlin.math.atan2(u.first * v.second - u.second * v.first, u.first * v.first + u.second * v.second),
        )
        if (ang > threshold) corners.add(b)
    }
    for (j in 0 until active.size - 1) test(active[j], active[j + 1])
    if (path.closed && active.size > 1) test(active[active.size - 1], active[0])
    return corners.sorted()
}

/** 子路径总弧长（逐段 Gauss-Legendre）。 */
internal fun pathArcLength(path: CubicPath): Double {
    val p = path.pts
    val m = (p.size / 2 - 1) / 3
    var l = 0.0
    for (k in 0 until m) l += segLen(p, k)
    return l
}

/** 弧长反演：求 t 使 ∫₀^t |B′| = s。带二分保护的 Newton 迭代（|B′| 即目标函数的精确导数）。 */
private fun invert(p: DoubleArray, k: Int, s: Double, ls: Double): Double {
    if (s <= 0.0) return 0.0
    if (s >= ls) return 1.0
    var lo = 0.0
    var hi = 1.0
    var t = s / ls
    for (it in 0 until 12) {
        val f = segLen(p, k, t) - s
        if (kotlin.math.abs(f) < 1e-10 * ls + 1e-14) break
        if (f > 0) hi = t else lo = t
        val sp = speed(p, k, t)
        var nt = if (sp > 1e-12) t - f / sp else (lo + hi) / 2.0
        if (!(nt > lo && nt < hi)) nt = (lo + hi) / 2.0
        t = nt
    }
    return t
}

/**
 * 把一条 cubic 子路径按弧长等距采样成 N 个点，拐角与端点作为精确采样点锚定。
 * 返回 DoubleArray(2N)。闭合路径把 N 个区间绕环分布（不重复首点）；
 * 起点的环形自由度由 plan 的环形对应消解。
 */
internal fun resamplePath(
    path: CubicPath,
    n: Int = MORPH_DEFAULT_POINTS,
    cornerThreshold: Double = MORPH_CORNER_THRESHOLD,
): DoubleArray {
    val p = path.pts
    val m = (p.size / 2 - 1) / 3
    val out = DoubleArray(2 * n)
    if (m < 1) {
        for (i in 0 until n) {
            out[2 * i] = p[0]
            out[2 * i + 1] = p[1]
        }
        return out
    }
    val lens = DoubleArray(m)
    var totalLen = 0.0
    for (k in 0 until m) {
        lens[k] = segLen(p, k)
        totalLen += lens[k]
    }
    if (totalLen < 1e-12) {
        for (i in 0 until n) {
            out[2 * i] = p[0]
            out[2 * i + 1] = p[1]
        }
        return out
    }

    // 锚点：段边界。开放路径 = 端点 + 拐角；闭合路径只取拐角 —— 采样必须
    // 只依赖形状而不依赖任意的 M 点：两个起点不同的全等环产生的采样集
    // （在索引旋转意义下）相同，由 plan 的环形对应消解。无拐角（圆）时
    // 路径起点是唯一可用的参照。
    val cs = detectCorners(path, cornerThreshold)
    val anchors: List<Int> = if (path.closed) {
        if (cs.isNotEmpty()) cs else listOf(0)
    } else {
        (mutableListOf(0) + cs + m).distinct().sorted()
    }
    // 拐角之间的「区间段（run）」；闭合时最后一段回绕到 anchors[0] + m
    val runs = ArrayList<Pair<Int, Int>>()
    if (path.closed) {
        for (j in anchors.indices) {
            val a = anchors[j]
            val b = if (j + 1 < anchors.size) anchors[j + 1] else anchors[0] + m
            runs.add(a to b)
        }
    } else {
        for (j in 0 until anchors.size - 1) runs.add(anchors[j] to anchors[j + 1])
    }
    val rl = runs.map { (a, b) ->
        var s = 0.0
        for (k in a until b) s += lens[k % m]
        s
    }
    val intervals = if (path.closed) n else n - 1
    check(runs.size <= intervals) { "morphicons: N=$n too small (${runs.size} runs)" }

    // 最大余数法：按长度比例分配，每段至少 1，总和精确
    val total = rl.sum().takeIf { it > 0.0 } ?: 1.0
    val ideal = rl.map { intervals * it / total }
    val counts = ideal.map { maxOf(1.0, kotlin.math.floor(it)).toInt() }.toIntArray()
    var remainder = intervals - counts.sum()
    if (remainder > 0) {
        // 量化后的分数部分：数值噪声（~1e-15）不能决定并列时的分配 ——
        // 旋转全等的 run 在两个图标里必须分得同样的点数，否则 Procrustes
        // 会丢失精确全等性
        val order = ideal
            .mapIndexed { idx, q -> Triple(Math.round((q - kotlin.math.floor(q)) * 1e9), idx, q) }
            .sortedWith(compareByDescending<Triple<Long, Int, Double>> { it.first }.thenBy { it.second })
        for (j in 0 until remainder) counts[order[j % counts.size].second]++
    }
    while (remainder < 0) {
        var bi = 0
        for (idx in counts.indices) if (counts[idx] > counts[bi]) bi = idx
        if (counts[bi] <= 1) break
        counts[bi]--
        remainder++
    }

    // 采样：每个 run 的起点精确落在锚点上，内部点由弧长反演得到
    var w = 0
    for (r in runs.indices) {
        val (k0, k1) = runs[r]
        val cnt = counts[r]
        val lr = rl[r]
        val vi = 6 * (k0 % m)
        out[2 * w] = p[vi]
        out[2 * w + 1] = p[vi + 1]
        w++
        var seg = k0
        var acc = 0.0
        for (j in 1 until cnt) {
            val target = lr * j / cnt
            while (seg < k1 - 1 && acc + lens[seg % m] < target) {
                acc += lens[seg % m]
                seg++
            }
            val k = seg % m
            val ls = lens[k]
            val t = if (ls > 1e-12) invert(p, k, target - acc, ls) else 0.0
            bernsteinAt(p, k, t, out, 2 * w)
            w++
        }
    }
    if (!path.closed) {
        val vi = 6 * m
        out[2 * w] = p[vi]
        out[2 * w + 1] = p[vi + 1]
    }
    return out
}

/** 完整管线：图标 `d` → cubic → 按弧长重采样的子路径（含拓扑 closed 标记）。 */
internal fun resampleIcon(d: String, n: Int = MORPH_DEFAULT_POINTS): List<Sampled> =
    iconToCubics(d).map { path -> Sampled(resamplePath(path, n), path.closed) }
