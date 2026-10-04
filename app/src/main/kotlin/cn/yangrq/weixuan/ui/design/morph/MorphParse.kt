package cn.yangrq.weixuan.ui.design.morph

/*
 * SVG `d` 属性解析器（MorphParse.kt）
 *
 * 移植自 guillermolg00/morphicons（MIT）src/core/parse.ts；
 * 本地蓝本：docs/third-party/morphicons/src/core/parse.ts（只读参考，不参与编译）。
 *
 * 职责：把 `d` 字符串解析为「绝对坐标」的原始子路径：
 *  - 相对指令（小写）→ 绝对；H/V → L；S/T → C/Q（控制点反射）；
 *  - 支持隐式重复（同一指令多组参数）、M 之后的多组坐标（隐式 lineto）、
 *    arc 的紧凑 flag 写法（如 `a1 1 0 011 1`）与科学计数法。
 * 转 cubic 的工作在 MorphNormalize.kt。
 */

/** 跳过分隔符（空格 / 逗号 / 换行 / 制表符）。 */
private fun skipSep(d: String, i: Int): Int {
    var k = i
    while (k < d.length && (d[k] == ' ' || d[k] == ',' || d[k] == '\n' || d[k] == '\t' || d[k] == '\r')) k++
    return k
}

private fun isDigit(c: Char): Boolean = c in '0'..'9'

/**
 * 扫描一个数字（支持 `.5`、`-.5.5` 这类紧凑写法与 `1e-3` 科学计数法）。
 * 返回 null 表示当前位置不是数字起点。
 */
private fun scanNumber(d: String, i: Int): Pair<Double, Int>? {
    var k = skipSep(d, i)
    if (k >= d.length) return null
    val start = k
    if (d[k] == '+' || d[k] == '-') k++
    var digits = false
    while (k < d.length && isDigit(d[k])) {
        k++
        digits = true
    }
    var seenDot = false
    if (k < d.length && d[k] == '.') {
        k++
        seenDot = true
        while (k < d.length && isDigit(d[k])) {
            k++
            digits = true
        }
    }
    if (!digits) return null
    if (k < d.length && (d[k] == 'e' || d[k] == 'E')) {
        var e = k + 1
        if (e < d.length && (d[e] == '+' || d[e] == '-')) e++
        var expDigits = false
        while (e < d.length && isDigit(d[e])) {
            e++
            expDigits = true
        }
        if (expDigits) k = e
    }
    val text = d.substring(start, k)
    return text.toDouble() to k
}

/** arc 的 large/sweep flag：0 或 1，允许与下一个数字连写（packed flags）。 */
private fun scanFlag(d: String, i: Int): Pair<Int, Int>? {
    val k = skipSep(d, i)
    if (k >= d.length) return null
    val c = d[k]
    if (c == '0') return 0 to (k + 1)
    if (c == '1') return 1 to (k + 1)
    return null
}

/** 主循环里判断当前位置是否是数字起点（用于隐式重复）。 */
private fun looksLikeNumber(d: String, i: Int): Boolean {
    var k = skipSep(d, i)
    if (k >= d.length) return false
    val c = d[k]
    if (isDigit(c) || c == '.' || c == '+' || c == '-') return true
    return false
}

/**
 * 解析 `d` 字符串 → 绝对坐标的原始子路径列表。
 * 没有段的子路径会被过滤掉；首次 M 之前的指令按 SVG 惯例忽略（仍消耗其参数）。
 */
internal fun parsePathD(d: String): List<RawSubpath> {
    val subs = ArrayList<RawSubpath>()
    val n = d.length
    var i = 0

    var cmd: Char = ' '
    var rel = false
    var started = false

    // 当前子路径
    var cur: ArrayList<RawSeg> = ArrayList()
    var sx = 0.0 // 当前子路径起点
    var sy = 0.0
    var cx = 0.0 // 当前点
    var cy = 0.0
    var px = 0.0 // 上一条 C/Q 的最后控制点（供 S/T 反射）
    var py = 0.0
    var prev: Char = ' '

    fun pushSub(closed: Boolean) {
        if (cur.isNotEmpty()) subs.add(RawSubpath(sx, sy, ArrayList(cur), closed))
        cur = ArrayList()
    }

    fun newSub(x: Double, y: Double) {
        cur = ArrayList()
        sx = x
        sy = y
        cx = x
        cy = y
    }

    while (i < n) {
        i = skipSep(d, i)
        if (i >= n) break
        val c = d[i]
        if (c.isLetter()) {
            cmd = c
            rel = c.isLowerCase()
            i++
        } else if (!looksLikeNumber(d, i)) {
            i++ // 无效字符，跳过
            continue
        } else if (cmd == ' ') {
            i++ // 首个 M 之前的裸数字
            continue
        }
        val u = cmd.uppercaseChar()
        var implicit = false

        when (u) {
            'M' -> {
                // M 之后的多组坐标按 L 处理
                started = true
                var first = true
                while (true) {
                    val nx = scanNumber(d, i) ?: break
                    val ny = scanNumber(d, nx.second) ?: break
                    i = ny.second
                    var x = nx.first
                    var y = ny.first
                    if (rel && !first) {
                        // 隐式 L 的相对量基于刚更新后的当前点
                    }
                    if (rel) {
                        if (first) {
                            x += cx
                            y += cy
                        } else {
                            x += cx
                            y += cy
                        }
                    }
                    if (first) {
                        pushSub(false)
                        newSub(x, y)
                        first = false
                        prev = ' '
                    } else {
                        cur.add(RawLine(x, y))
                        cx = x
                        cy = y
                        prev = ' '
                    }
                    if (!looksLikeNumber(d, i)) break
                    implicit = true
                }
                if (implicit) cmd = 'L'
            }

            'L' -> {
                while (true) {
                    val nx = scanNumber(d, i) ?: break
                    val ny = scanNumber(d, nx.second) ?: break
                    i = ny.second
                    var x = nx.first
                    var y = ny.first
                    if (rel) {
                        x += cx
                        y += cy
                    }
                    if (started) cur.add(RawLine(x, y))
                    cx = x
                    cy = y
                    prev = ' '
                    if (!looksLikeNumber(d, i)) break
                }
            }

            'H' -> {
                while (true) {
                    val nx = scanNumber(d, i) ?: break
                    i = nx.second
                    var x = nx.first
                    if (rel) x += cx
                    if (started) cur.add(RawLine(x, cy))
                    cx = x
                    prev = ' '
                    if (!looksLikeNumber(d, i)) break
                }
            }

            'V' -> {
                while (true) {
                    val ny = scanNumber(d, i) ?: break
                    i = ny.second
                    var y = ny.first
                    if (rel) y += cy
                    if (started) cur.add(RawLine(cx, y))
                    cy = y
                    prev = ' '
                    if (!looksLikeNumber(d, i)) break
                }
            }

            'C' -> {
                while (true) {
                    val a = scanNumber(d, i) ?: break
                    val b = scanNumber(d, a.second) ?: break
                    val c2 = scanNumber(d, b.second) ?: break
                    val e = scanNumber(d, c2.second) ?: break
                    val f = scanNumber(d, e.second) ?: break
                    val g = scanNumber(d, f.second) ?: break
                    i = g.second
                    var x1 = a.first
                    var y1 = b.first
                    var x2 = c2.first
                    var y2 = e.first
                    var x = f.first
                    var y = g.first
                    if (rel) {
                        x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy
                    }
                    if (started) cur.add(RawCubic(x1, y1, x2, y2, x, y))
                    px = x2
                    py = y2
                    cx = x
                    cy = y
                    prev = 'C'
                    if (!looksLikeNumber(d, i)) break
                }
            }

            'S' -> {
                while (true) {
                    val a = scanNumber(d, i) ?: break
                    val b = scanNumber(d, a.second) ?: break
                    val c2 = scanNumber(d, b.second) ?: break
                    val e = scanNumber(d, c2.second) ?: break
                    i = e.second
                    var x2 = a.first
                    var y2 = b.first
                    var x = c2.first
                    var y = e.first
                    val refl = prev == 'C' || prev == 'S'
                    val x1 = if (refl) 2 * cx - px else cx
                    val y1 = if (refl) 2 * cy - py else cy
                    if (rel) {
                        x2 += cx; y2 += cy; x += cx; y += cy
                    }
                    if (started) cur.add(RawCubic(x1, y1, x2, y2, x, y))
                    px = x2
                    py = y2
                    cx = x
                    cy = y
                    prev = 'S'
                    if (!looksLikeNumber(d, i)) break
                }
            }

            'Q' -> {
                while (true) {
                    val a = scanNumber(d, i) ?: break
                    val b = scanNumber(d, a.second) ?: break
                    val c2 = scanNumber(d, b.second) ?: break
                    val e = scanNumber(d, c2.second) ?: break
                    i = e.second
                    var x1 = a.first
                    var y1 = b.first
                    var x = c2.first
                    var y = e.first
                    if (rel) {
                        x1 += cx; y1 += cy; x += cx; y += cy
                    }
                    if (started) cur.add(RawQuad(x1, y1, x, y))
                    px = x1
                    py = y1
                    cx = x
                    cy = y
                    prev = 'Q'
                    if (!looksLikeNumber(d, i)) break
                }
            }

            'T' -> {
                while (true) {
                    val a = scanNumber(d, i) ?: break
                    val b = scanNumber(d, a.second) ?: break
                    i = b.second
                    var x = a.first
                    var y = b.first
                    val x1 = if (prev == 'Q' || prev == 'T') 2 * cx - px else cx
                    val y1 = if (prev == 'Q' || prev == 'T') 2 * cy - py else cy
                    if (rel) {
                        x += cx; y += cy
                    }
                    if (started) cur.add(RawQuad(x1, y1, x, y))
                    px = x1
                    py = y1
                    cx = x
                    cy = y
                    prev = 'Q'
                    if (!looksLikeNumber(d, i)) break
                }
            }

            'A' -> {
                while (true) {
                    val rxv = scanNumber(d, i) ?: break
                    val ryv = scanNumber(d, rxv.second) ?: break
                    val rot = scanNumber(d, ryv.second) ?: break
                    val la = scanFlag(d, rot.second) ?: break
                    val sw = scanFlag(d, la.second) ?: break
                    val x = scanNumber(d, sw.second) ?: break
                    val y = scanNumber(d, x.second) ?: break
                    i = y.second
                    var ex = x.first
                    var ey = y.first
                    if (rel) {
                        ex += cx
                        ey += cy
                    }
                    if (started) {
                        cur.add(RawArc(kotlin.math.abs(rxv.first), kotlin.math.abs(ryv.first), rot.first, la.first, sw.first, ex, ey))
                    }
                    cx = ex
                    cy = ey
                    prev = 'A'
                    if (!looksLikeNumber(d, i)) break
                }
            }

            'Z' -> {
                if (started) {
                    pushSub(true)
                    // Z 之后当前点回到子路径起点；后续指令从那里继续
                    cx = sx
                    cy = sy
                    cur = ArrayList()
                    prev = ' '
                }
            }

            else -> {
                // 未知指令：无法可靠跳参，直接终止以避免错位解析
                i = n
            }
        }
    }
    pushSub(false)
    return subs.filter { it.segs.isNotEmpty() }
}
