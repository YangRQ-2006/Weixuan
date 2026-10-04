package cn.yangrq.weixuan.ui.design.morph

/*
 * 对应与对齐（MorphPlan.kt）
 *
 * 移植自 guillermolg00/morphicons（MIT）src/core/plan.ts；
 * 本地蓝本：docs/third-party/morphicons/src/core/plan.ts。
 *
 * 内容：闭式 2D Procrustes（免 SVD：atan2）；子路径配对用「质心+长度」代价
 * （两侧数量不等时做满射匹配 —— 多余者复制，即「细胞分裂」）；闭合环做环形
 * 对应；λ 做最小旋转的并列打破；全局混合 —— 若整个图标在同一个相似变换下
 * 全等，则所有子路径共享 (θ, σ)（连贯的整块旋转）。
 */

/** 子路径配对代价里 |ΔL| 的权重。 */
private const val LEN_WEIGHT = 0.35

/**
 * 最小旋转并列打破的 λ：score = res + λ·|θ|/π。
 * 它存在的原因：对反转对称的形状（直线）两种遍历方向残差并列，却给出
 * 不同的旋转。
 */
private const val LAMBDA = 0.05

/** 全局残差低于该值时整个图标视为全等，所有子路径共享 (θ, σ)。 */
private const val GLOBAL_EPS = 5e-3

/** 穷举匹配的上限；超过则回退到贪婪 + 修补。8! = 40320 / 1e5 —— 均为亚毫秒级。 */
private const val PERM_MAX = 8
private const val SURJ_MAX = 100000.0

/** 一对子路径的对齐结果。 */
internal class MorphAlignment(
    val caX: Double,
    val caY: Double,
    val cbX: Double,
    val cbY: Double,
    /** 选用对应关系后的 A（若 A 是闭合环，可能被环形重排：同样的点，不同的切口）。 */
    val a: DoubleArray,
    /** 选用对应关系后的 B（含方向与环形偏移）。 */
    val b: DoubleArray,
    val theta: Double,
    val sigma: Double,
    val res: Double,
)

/** 子路径质心的「块输送」参数（仅全局混合时非空）。 */
internal class MorphBlock(
    val offX: Double,
    val offY: Double,
    val driftX: Double,
    val driftY: Double,
)

/** 计划里的一对子路径（A → B）。 */
internal class PlanItem(
    /** 选用对应关系后的 A 的采样点（闭合环可能被环形重排）。 */
    val a: DoubleArray,
    /** 以自身质心为原点的 A。 */
    val aC: DoubleArray,
    /** 拉到 A 坐标系里的 B：R(−θ)·(b − c_B)/σ。 */
    val bT: DoubleArray,
    /** 方向修正后的原始 B（线性模式与 t=1 精确端点用）。 */
    val bO: DoubleArray,
    val caX: Double,
    val caY: Double,
    val cbX: Double,
    val cbY: Double,
    var theta: Double,
    var lnSigma: Double,
    var res: Double,
    /** 两端都是闭合环时该子路径带 Z 飞行；闭合→开放则在选定切口处张开。 */
    val closed: Boolean,
    /** 块输送（全局混合时设置）：飞行中质心沿共享相似变换绕全局质心运转。 */
    var block: MorphBlock?,
)

/** 变形计划：可缓存、可复用；接受任意子路径列表 —— 包括中间形状（打断）。 */
internal class MorphPlan(val items: List<PlanItem>, val n: Int)

/** 点云质心 → [x, y]。 */
internal fun centroidOf(p: DoubleArray): DoubleArray {
    val n = p.size / 2
    var cx = 0.0
    var cy = 0.0
    for (i in 0 until n) {
        cx += p[2 * i]
        cy += p[2 * i + 1]
    }
    return doubleArrayOf(cx / n, cy / n)
}

/** 折线周长。 */
internal fun polyLenOf(p: DoubleArray): Double {
    val n = p.size / 2
    var l = 0.0
    for (i in 1 until n) {
        l += kotlin.math.hypot(p[2 * i] - p[2 * i - 2], p[2 * i + 1] - p[2 * i - 1])
    }
    return l
}

/** 点序反转。 */
internal fun reversePts(p: DoubleArray): DoubleArray {
    val n = p.size / 2
    val out = DoubleArray(2 * n)
    for (i in 0 until n) {
        out[2 * i] = p[2 * (n - 1 - i)]
        out[2 * i + 1] = p[2 * (n - 1 - i) + 1]
    }
    return out
}

/** 环形重排：out[i] = p[(i+off) mod n]。同一组点、不同切口 —— 闭合路径的环形自由度。 */
internal fun rotatePts(p: DoubleArray, off: Int): DoubleArray {
    val n = p.size / 2
    val out = DoubleArray(2 * n)
    for (i in 0 until n) {
        val j = (i + off) % n
        out[2 * i] = p[2 * j]
        out[2 * i + 1] = p[2 * j + 1]
    }
    return out
}

/**
 * 最优相似变换 (θ, σ)：最小化 Σ|σ·R(θ)·(a−c_A) − (b−c_B)|²。
 * θ* = atan2(S_xy − S_yx, S_xx + S_yy)；σ* 由导数为零求得；
 * res = 以 b 的能量归一的 RMS 残差（0 → 同形）。
 * 返回 [theta, sigma, res]。
 */
internal fun procrustes(
    a: DoubleArray,
    b: DoubleArray,
    caX: Double,
    caY: Double,
    cbX: Double,
    cbY: Double,
): DoubleArray {
    val n = a.size / 2
    var sxx = 0.0
    var sxy = 0.0
    var syx = 0.0
    var syy = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in 0 until n) {
        val ax = a[2 * i] - caX
        val ay = a[2 * i + 1] - caY
        val bx = b[2 * i] - cbX
        val by = b[2 * i + 1] - cbY
        sxx += ax * bx
        syy += ay * by
        sxy += ax * by
        syx += ay * bx
        na += ax * ax + ay * ay
        nb += bx * bx + by * by
    }
    val theta = kotlin.math.atan2(sxy - syx, sxx + syy)
    val num = kotlin.math.cos(theta) * (sxx + syy) + kotlin.math.sin(theta) * (sxy - syx)
    var sigma = if (na > 1e-12) num / na else 1.0
    if (!(sigma > 1e-6)) sigma = 1e-6
    val res2 = maxOf(0.0, sigma * sigma * na - 2 * sigma * num + nb)
    val res = if (nb > 1e-12) kotlin.math.sqrt(res2 / nb) else 0.0
    return doubleArrayOf(theta, sigma, res)
}

/**
 * a↔b 的最优逐点对应：尝试两种遍历方向；若有闭合环，再试它的 N 个环形偏移；
 * 打分 score = res + λ·|θ|/π。自由度只施加于一个点云 —— 闭合的那个
 * （两端都闭合时施加于 b）；两边同时变化是冗余的。
 */
private fun alignPair(
    aPts: DoubleArray,
    bPts: DoubleArray,
    aClosed: Boolean,
    bClosed: Boolean,
): MorphAlignment {
    val ca = centroidOf(aPts)
    val cb = centroidOf(bPts)
    val varyA = aClosed && !bClosed
    val base = if (varyA) aPts else bPts
    val offs = if (aClosed || bClosed) base.size / 2 else 1
    var bestScore = Double.POSITIVE_INFINITY
    var best = base
    var sim = doubleArrayOf(0.0, 1.0, 0.0)
    for (dir in 0..1) {
        val walk = if (dir == 1) reversePts(base) else base
        for (off in 0 until offs) {
            val cand = if (off != 0) rotatePts(walk, off) else walk
            val s = if (varyA) procrustes(cand, bPts, ca[0], ca[1], cb[0], cb[1])
            else procrustes(aPts, cand, ca[0], ca[1], cb[0], cb[1])
            val score = s[2] + LAMBDA * kotlin.math.abs(s[0]) / Math.PI
            if (score < bestScore) {
                bestScore = score
                best = cand
                sim = s
            }
        }
    }
    return if (varyA) {
        MorphAlignment(ca[0], ca[1], cb[0], cb[1], best, bPts, sim[0], sim[1], sim[2])
    } else {
        MorphAlignment(ca[0], ca[1], cb[0], cb[1], aPts, best, sim[0], sim[1], sim[2])
    }
}

/** 代价矩阵：dist(质心) + LEN_WEIGHT·|ΔL|。 */
private fun costMatrix(A: List<DoubleArray>, B: List<DoubleArray>): Array<DoubleArray> {
    val cbs = B.map { centroidOf(it) }
    val lbs = B.map { polyLenOf(it) }
    return Array(A.size) { i ->
        val ca = centroidOf(A[i])
        val la = polyLenOf(A[i])
        DoubleArray(B.size) { j ->
            kotlin.math.hypot(ca[0] - cbs[j][0], ca[1] - cbs[j][1]) + LEN_WEIGHT * kotlin.math.abs(la - lbs[j])
        }
    }
}

/** 数量相等：最小代价置换。≤ PERM_MAX 时带剪枝穷举；超过则按代价排序贪婪。 */
private fun bestPermutation(c: Array<DoubleArray>): IntArray {
    val n = c.size
    if (n > PERM_MAX) {
        val pairs = ArrayList<Triple<Double, Int, Int>>(n * n)
        for (i in 0 until n) for (j in 0 until n) pairs.add(Triple(c[i][j], i, j))
        pairs.sortBy { it.first }
        val out = IntArray(n) { -1 }
        val used = BooleanArray(n)
        for ((_, i, j) in pairs) {
            if (out[i] < 0 && !used[j]) {
                out[i] = j
                used[j] = true
            }
        }
        for (i in 0 until n) if (out[i] < 0) {
            for (j in 0 until n) if (!used[j]) {
                out[i] = j
                used[j] = true
                break
            }
        }
        return out
    }
    val idx = IntArray(n) { it }
    var best = idx.copyOf()
    var bc = Double.POSITIVE_INFINITY
    fun perm(arr: IntArray, k: Int, acc: Double) {
        if (acc >= bc) return
        if (k == n) {
            bc = acc
            best = arr.copyOf()
            return
        }
        for (i in k until n) {
            val t = arr[k]; arr[k] = arr[i]; arr[i] = t
            perm(arr, k + 1, acc + c[k][arr[k]])
            val t2 = arr[k]; arr[k] = arr[i]; arr[i] = t2
        }
    }
    perm(idx, 0, 0.0)
    return best
}

/**
 * 数量不等：从多的一侧向少的一侧做最小代价满射分配。S^B 较小时穷举（带剪枝），
 * 否则贪婪 + 覆盖修补。满射保证不会凭空出现或消失子路径。
 */
private fun bestSurjection(c: Array<DoubleArray>): IntArray {
    val big = c.size
    val small = c[0].size
    require(big >= small) { "morphicons: no valid surjection (big < small)" }
    if (Math.pow(small.toDouble(), big.toDouble()) > SURJ_MAX) {
        // 贪婪 + 覆盖修补
        val f = IntArray(big) { i ->
            var m = 0
            for (j in 1 until small) if (c[i][j] < c[i][m]) m = j
            m
        }
        val mult = IntArray(small)
        for (s in f) mult[s]++
        for (s in 0 until small) {
            if (mult[s] > 0) continue
            var bi = -1
            var bc = Double.POSITIVE_INFINITY
            for (i in 0 until big) {
                if (mult[f[i]] < 2) continue // 只有重复占用者能让位
                val extra = c[i][s] - c[i][f[i]]
                if (extra < bc) {
                    bc = extra
                    bi = i
                }
            }
            if (bi < 0) continue
            mult[f[bi]]--
            f[bi] = s
            mult[s]++
        }
        return f
    }
    var best: IntArray? = null
    var bc = Double.POSITIVE_INFINITY
    val f = IntArray(big)
    val mult = IntArray(small)
    fun rec(i: Int, acc: Double, covered: Int) {
        if (acc >= bc || small - covered > big - i) return
        if (i == big) {
            bc = acc
            best = f.copyOf()
            return
        }
        for (s in 0 until small) {
            f[i] = s
            mult[s]++
            rec(i + 1, acc + c[i][s], covered + if (mult[s] == 1) 1 else 0)
            mult[s]--
        }
    }
    rec(0, 0.0, 0)
    return best ?: throw IllegalStateException("morphicons: no valid surjection (big < small)")
}

/**
 * 全局混合：把已选定对应的两个点云拼起来做一次 Procrustes。
 * 若全局残差 ≈ 0，整个图标全等 —— 所有子路径共享 (θ, σ)：
 * 连贯的整块旋转（避免对称子路径选出相反的自旋）。
 */
private fun applyGlobal(items: List<PlanItem>, n: Int) {
    val t = items.size * n
    val ga = DoubleArray(2 * t)
    val gb = DoubleArray(2 * t)
    for ((k, it) in items.withIndex()) {
        System.arraycopy(it.a, 0, ga, 2 * n * k, 2 * n)
        System.arraycopy(it.bO, 0, gb, 2 * n * k, 2 * n)
    }
    val gca = centroidOf(ga)
    val g = procrustes(ga, gb, gca[0], gca[1], centroidOf(gb)[0], centroidOf(gb)[1])
    if (g[2] >= GLOBAL_EPS) return
    val gTheta = g[0]
    val gSigma = g[1]
    val cos = kotlin.math.cos(-gTheta)
    val sin = kotlin.math.sin(-gTheta)
    val rc = kotlin.math.cos(gTheta)
    val rs = kotlin.math.sin(gTheta)
    for (it in items) {
        var e2 = 0.0
        var nb = 0.0
        for (i in 0 until n) {
            val bx = it.bO[2 * i] - it.cbX
            val by = it.bO[2 * i + 1] - it.cbY
            it.bT[2 * i] = (bx * cos - by * sin) / gSigma
            it.bT[2 * i + 1] = (bx * sin + by * cos) / gSigma
            val ex = gSigma * (rc * it.aC[2 * i] - rs * it.aC[2 * i + 1]) - bx
            val ey = gSigma * (rs * it.aC[2 * i] + rc * it.aC[2 * i + 1]) - by
            e2 += ex * ex + ey * ey
            nb += bx * bx + by * by
        }
        it.theta = gTheta
        it.lnSigma = kotlin.math.ln(gSigma)
        it.res = if (nb > 1e-12) kotlin.math.sqrt(e2 / nb) else 0.0
        // 块输送：每一部分都随共享的 θ 自旋，但对质心做普通插值会让偏离
        // 中心的部分沿弦飞行 —— 落在弧内 —— 整块中途变形（箭头的头部向
        // 杆部下坠）。质心改为沿共享相似变换绕全局质心运转；drift 吸收
        // （微小的）全局残差，保证 t = 1 仍然精确。与插值器相同的运算：
        // 旋转增量在两个端点位相同，逐位抵消。
        val s1 = kotlin.math.exp(it.lnSigma)
        val c1 = kotlin.math.cos(it.theta) * s1
        val n1 = kotlin.math.sin(it.theta) * s1
        val ox = it.caX - gca[0]
        val oy = it.caY - gca[1]
        val rx = ox * c1 - oy * n1 - ox
        val ry = ox * n1 + oy * c1 - oy
        it.block = MorphBlock(
            ox,
            oy,
            it.cbX - it.caX - rx,
            it.cbY - it.caY - ry,
        )
    }
}

/** 在两组重采样子路径之间构建变形计划。 */
internal fun buildMorphPlan(srcSubs: List<Sampled>, dstSubs: List<Sampled>): MorphPlan {
    val p = srcSubs.size
    val q = dstSubs.size
    require(p > 0 && q > 0) { "morphicons: icon has no subpaths" }
    val aPts = srcSubs.map { it.pts }
    val bPts = dstSubs.map { it.pts }
    val pairs = ArrayList<Pair<Int, Int>>()
    if (p == q) {
        val perm = bestPermutation(costMatrix(aPts, bPts))
        for (i in 0 until p) pairs.add(i to perm[i])
    } else if (p < q) {
        val f = bestSurjection(costMatrix(bPts, aPts))
        for (j in 0 until q) pairs.add(f[j] to j)
    } else {
        val f = bestSurjection(costMatrix(aPts, bPts))
        for (i in 0 until p) pairs.add(i to f[i])
    }
    val n = aPts[0].size / 2
    val items = pairs.map { (si, di) ->
        val al = alignPair(aPts[si], bPts[di], srcSubs[si].closed, dstSubs[di].closed)
        val a = al.a
        val aC = DoubleArray(2 * n)
        val bT = DoubleArray(2 * n)
        val bO = DoubleArray(2 * n)
        val cos = kotlin.math.cos(-al.theta)
        val sin = kotlin.math.sin(-al.theta)
        for (i in 0 until n) {
            aC[2 * i] = a[2 * i] - al.caX
            aC[2 * i + 1] = a[2 * i + 1] - al.caY
            val bx = al.b[2 * i] - al.cbX
            val by = al.b[2 * i + 1] - al.cbY
            bT[2 * i] = (bx * cos - by * sin) / al.sigma
            bT[2 * i + 1] = (bx * sin + by * cos) / al.sigma
            bO[2 * i] = al.b[2 * i]
            bO[2 * i + 1] = al.b[2 * i + 1]
        }
        PlanItem(
            a = a,
            aC = aC,
            bT = bT,
            bO = bO,
            caX = al.caX,
            caY = al.caY,
            cbX = al.cbX,
            cbY = al.cbY,
            theta = al.theta,
            lnSigma = kotlin.math.ln(al.sigma),
            res = al.res,
            closed = srcSubs[si].closed && dstSubs[di].closed,
            block = null,
        )
    }
    if (items.size > 1) applyGlobal(items, n)
    return MorphPlan(items, n)
}
