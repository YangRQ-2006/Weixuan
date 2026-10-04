package cn.yangrq.weixuan.ui.design.morph

/*
 * 弹簧（MorphSpring.kt）
 *
 * 移植自 guillermolg00/morphicons（MIT）src/core/spring.ts；
 * 本地蓝本：docs/third-party/morphicons/src/core/spring.ts。
 *
 * 作用于进度 x: 0 → 1 的阻尼谐振子：ẍ = k·(1−x) − c·ẋ，
 * 用 1/240 s 子步的半隐式欧拉积分（ω·h ≈ 2 内稳定；k=420 时 ω≈20.5，裕量充足）。
 * 可打断：start() 把 x 重置为 0，同时保留速度（钳制到 ±14）。
 */

internal class MorphSpring {
    var x: Double = 1.0
    var v: Double = 0.0
    var k: Double = 250.0
    var c: Double = 24.0

    fun config(stiffness: Double, damping: Double) {
        k = stiffness
        c = damping
    }

    /** 开始（或飞行中重启）：保留速度。 */
    fun start() {
        x = 0.0
        if (v > 14.0) v = 14.0
        if (v < -14.0) v = -14.0
    }

    /** 推进 dt 秒。返回 true 表示已安定（|1−x| < 0.001 ∧ |v| < 0.02）。 */
    fun step(dt: Double): Boolean {
        val h = 1.0 / 240.0
        val steps = maxOf(1, minOf(16, kotlin.math.ceil(dt / h).toInt()))
        val s = dt / steps
        for (i in 0 until steps) {
            val a = k * (1.0 - x) - c * v
            v += a * s
            x += v * s
        }
        return kotlin.math.abs(1.0 - x) < 0.001 && kotlin.math.abs(v) < 0.02
    }
}
