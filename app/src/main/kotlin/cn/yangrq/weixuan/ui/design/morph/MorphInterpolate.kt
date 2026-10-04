package cn.yangrq.weixuan.ui.design.morph

/*
 * 极坐标插值（MorphInterpolate.kt）
 *
 * 移植自 guillermolg00/morphicons（MIT）src/core/interpolate.ts；
 * 本地蓝本：docs/third-party/morphicons/src/core/interpolate.ts。
 *
 * 相似变换在其自然空间里插值（角度线性、尺度对数线性、质心 lerp），
 * 再施加到对齐坐标系里的残差混合上：
 *   P(t) = c(t) + σᵗ·R(t·θ)·[(1−t)·aC + t·bT]
 * 全局混合下质心不做 lerp —— 沿共享相似变换绕全局质心运转（块输送，见 plan）：
 *   c(t) = ca + t·drift + (σᵗ·R(t·θ) − I)·off
 * t=0 与 t=1 处精确；弹簧过冲（t>1）时自然外推。线性模式保留作对照。
 */

/** 为计划预分配输出缓冲（每帧零分配）。 */
internal fun allocOutputs(plan: MorphPlan): Array<DoubleArray> =
    Array(plan.items.size) { DoubleArray(2 * plan.n) }

/** 极坐标插值（默认模式）。 */
internal fun interpPolar(plan: MorphPlan, t: Double, out: Array<DoubleArray>) {
    for (k in plan.items.indices) {
        val it = plan.items[k]
        val o = out[k]
        val n = plan.n
        val s = kotlin.math.exp(it.lnSigma * t)
        val ang = it.theta * t
        val cos = kotlin.math.cos(ang) * s
        val sin = kotlin.math.sin(ang) * s
        val cx: Double
        val cy: Double
        val block = it.block
        if (block != null) {
            val ox = block.offX
            val oy = block.offY
            val dx = block.driftX
            val dy = block.driftY
            cx = it.caX + dx * t + (ox * cos - oy * sin - ox)
            cy = it.caY + dy * t + (ox * sin + oy * cos - oy)
        } else {
            cx = it.caX + (it.cbX - it.caX) * t
            cy = it.caY + (it.cbY - it.caY) * t
        }
        for (i in 0 until n) {
            val px = it.aC[2 * i] + (it.bT[2 * i] - it.aC[2 * i]) * t
            val py = it.aC[2 * i + 1] + (it.bT[2 * i + 1] - it.aC[2 * i + 1]) * t
            o[2 * i] = cx + px * cos - py * sin
            o[2 * i + 1] = cy + px * sin + py * cos
        }
    }
}

/** 原始坐标 lerp（同一对应关系，不做分解），仅作对照/调试用。 */
internal fun interpLinear(plan: MorphPlan, t: Double, out: Array<DoubleArray>) {
    for (k in plan.items.indices) {
        val it = plan.items[k]
        val o = out[k]
        val n = plan.n
        for (i in 0 until n) {
            o[2 * i] = it.a[2 * i] + (it.bO[2 * i] - it.a[2 * i]) * t
            o[2 * i + 1] = it.a[2 * i + 1] + (it.bO[2 * i + 1] - it.a[2 * i + 1]) * t
        }
    }
}
