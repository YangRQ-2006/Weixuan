package cn.yangrq.weixuan.ui.design.morph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「爻形」核心算法单测（解析 / 重采样 / 规划 / 插值 / 重排）。
 * 被测实现：app/src/main/kotlin/cn/yangrq/weixuan/ui/design/morph/
 */
class MorphCoreTest {

    private val sendD = "M12 20L12 5M12 5L6.8 10.6M12 5L17.2 10.6"
    private val stopD = "M7.2 7.2L16.8 7.2L16.8 16.8L7.2 16.8Z"

    // 1. 解析：相对指令 / 隐式重复 / arc 打包 flag / H-V 简写
    @Test
    fun parse_relativeAndImplicitRepetition() {
        val subs = parsePathD("M10 10l5 5 5-5")
        assertEquals(1, subs.size)
        assertEquals(2, subs[0].segs.size)
        val l0 = subs[0].segs[0] as RawLine
        val l1 = subs[0].segs[1] as RawLine
        assertEquals(15.0, l0.x, 1e-9)
        assertEquals(15.0, l0.y, 1e-9)
        assertEquals(20.0, l1.x, 1e-9)
        assertEquals(10.0, l1.y, 1e-9)
    }

    @Test
    fun parse_horizontalVerticalShorthand() {
        val subs = parsePathD("M0 0h10v10")
        assertEquals(1, subs.size)
        val l0 = subs[0].segs[0] as RawLine
        val l1 = subs[0].segs[1] as RawLine
        assertEquals(10.0 to 0.0, l0.x to l0.y)
        assertEquals(10.0 to 10.0, l1.x to l1.y)
    }

    @Test
    fun parse_packedArcFlags() {
        val subs = parsePathD("M0 0A5 5 0 0110 0")
        assertEquals(1, subs.size)
        val arc = subs[0].segs[0] as RawArc
        assertEquals(0, arc.large)
        assertEquals(1, arc.sweep)
        assertEquals(10.0, arc.x, 1e-9)
        assertEquals(0.0, arc.y, 1e-9)
    }

    @Test
    fun parse_scientificNotation() {
        val subs = parsePathD("M1e1 1E-1L2e1 2.5e0")
        assertEquals(1, subs.size)
        val l = subs[0].segs[0] as RawLine
        assertEquals(20.0, l.x, 1e-9)
        assertEquals(2.5, l.y, 1e-9)
    }

    // 2. 重采样：每条子路径恰好 n 个点；开放路径首尾点等于端点
    @Test
    fun resample_fixedPointsPerSubpath() {
        val subs = resampleIcon(sendD)
        assertEquals(3, subs.size)
        for (s in subs) {
            assertEquals(MORPH_DEFAULT_POINTS * 2, s.pts.size)
        }
        val first = subs[0]
        assertEquals(12.0, first.pts[0], 1e-9)
        assertEquals(20.0, first.pts[1], 1e-9)
        assertEquals(12.0, first.pts[first.pts.size - 2], 1e-6)
        assertEquals(5.0, first.pts[first.pts.size - 1], 1e-6)
    }

    @Test
    fun resample_closedLoopKeepsPointCount() {
        val subs = resampleIcon(stopD)
        assertEquals(1, subs.size)
        assertEquals(MORPH_DEFAULT_POINTS * 2, subs[0].pts.size)
        assertFalse(subs[0].closed.not())
    }

    // 3. 端点精确：t=0 完全等于源、t=1 完全等于目标（含闭合环重排的等价性放宽）
    @Test
    fun plan_endpointsExact() {
        val a = resampleIcon(sendD)
        val b = resampleIcon(stopD)
        val plan = buildMorphPlan(a, b)
        assertEquals(3, plan.items.size)
        val out = allocOutputs(plan)
        interpPolar(plan, 0.0, out)
        for (k in plan.items.indices) {
            // 闭合环的对应可能选中环形偏移/反向 —— 计划里的 a/bO 就是重排后的端点
            for (i in 0 until plan.n * 2) {
                assertEquals("t=0 item$k[$i]", plan.items[k].a[i], out[k][i], 1e-9)
            }
        }
        interpPolar(plan, 1.0, out)
        for (k in plan.items.indices) {
            for (i in 0 until plan.n * 2) {
                assertEquals("t=1 item$k[$i]", plan.items[k].bO[i], out[k][i], 1e-9)
            }
        }
    }

    // 4. 全等不变性：旋转 90° 的同一图形 Procrustes 残差 ≈ 0
    @Test
    fun plan_congruentRotationHasZeroResidual() {
        // 直角三角形 (5,5)(15,5)(15,15) 及其绕 (10,10) 的 90° 旋转
        val a = resampleIcon("M5 5L15 5L15 15Z")
        val b = resampleIcon("M15 5L15 15L5 15Z")
        val plan = buildMorphPlan(a, b)
        assertTrue("res=${plan.items[0].res}", plan.items[0].res < 1e-6)
    }

    // 5. 子路径数不等：满射匹配，items.size == 较多一侧的数量
    @Test
    fun plan_surjectiveWhenCountsDiffer() {
        val plan = buildMorphPlan(resampleIcon(sendD), resampleIcon(stopD))
        assertEquals(3, plan.items.size)
        val plan2 = buildMorphPlan(resampleIcon(stopD), resampleIcon(sendD))
        assertEquals(3, plan2.items.size)
    }

    // 6. fitIcon：20 格 → 24 格（缩放 1.2，居中偏移 0）
    @Test
    fun fitIcon_regridsTo24() {
        val fitted = fitIcon("M0 0L20 20", "0 0 20 20", 24.0)
        val paths = iconToCubics(fitted)
        assertEquals(1, paths.size)
        val pts = paths[0].pts
        assertEquals(0.0, pts[0], 1e-6)
        assertEquals(0.0, pts[1], 1e-6)
        assertEquals(24.0, pts[pts.size - 2], 1e-6)
        assertEquals(24.0, pts[pts.size - 1], 1e-6)
    }

    // 7. 中间帧有限且确实在变形
    @Test
    fun interpolate_midpointIsFiniteAndMoving() {
        val plan = buildMorphPlan(resampleIcon(sendD), resampleIcon(stopD))
        val out = allocOutputs(plan)
        interpPolar(plan, 0.5, out)
        for (o in out) {
            for (i in o.indices) {
                assertTrue("mid[$i]=${o[i]}", o[i].isFinite())
            }
        }
        // 与两端都不完全相同（确实在飞）
        interpPolar(plan, 1.0, out)
        val endFirst = out[0].copyOf()
        interpPolar(plan, 0.5, out)
        assertFalse(out[0].contentEquals(endFirst))
    }

    // 8. 弹簧：start 保留速度并钳制；step 会收敛
    @Test
    fun spring_settlesAndClampsVelocity() {
        val s = MorphSpring()
        s.config(420.0, 30.0)
        s.v = 100.0
        s.start()
        assertEquals(14.0, s.v, 1e-9)
        var dt = 1.0 / 60.0
        var settled = false
        var guard = 0
        while (!settled && guard < 600) {
            settled = s.step(dt)
            guard++
        }
        assertTrue("did not settle in $guard frames", settled)
        assertEquals(1.0, s.x, 1e-3)
    }
}
