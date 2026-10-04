package cn.yangrq.weixuan.local

import android.util.Log
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale

/**
 * 端侧模型内存账本（2026-09-30 重构）。
 *
 * 旧实现用 `kvMb = ctx × 0.6(f16) / 0.3(q8_0)` 这个"拍出来的"常量估算 KV cache，
 * 实测偏高 4~19 倍，连锁造成两个后果：
 * 1. KV 被误判为"昂贵"，可用内存 < 5GB 时永远选 q8_0 —— 而 HTP 上 q8_0 KV 的逐行
 *    反量化正是长上下文 decode 的主要瓶颈（实测 3.1k 上下文：4.4 vs 11.2 tok/s），
 *    等于在能跑 f16 的机器上白付 2.5 倍速度。
 * 2. 需求被抬高后，任何 >2.4GB 的模型都会被拒绝加载（catalog 里的 8B/9B 永远进不来）。
 *
 * 现改为按 GGUF 元数据【按架构计算】：
 *   每 token KV 字节 = Σ(非递归层) (key_length + value_length) × n_head_kv[il] × elemSize
 *   递归层（线性注意力 / GDN）不占 KV，而是固定大小的 F32 递归状态（与上下文长度无关）。
 * 例：Qwen3-4B（36 层全注意力, 8 KV头 × 128）= 147,456 B/token；
 *     MiMo-V2.6-9B（32 层中 8 层 full attention, 4 KV头 × 256）= 32,768 B/token，
 *     比 4B 还省 4.5 倍 —— 这也解释了为什么它反而更容易塞进手机。
 *
 * 所有常量都取自本机 llama.cpp 源码（b11096）：
 *   src/llama-arch.cpp（GGUF 键名）、src/models/qwen35.cpp（递归层判定）、
 *   src/llama-kv-cache.cpp（KV 尺寸公式）、src/llama-hparams.cpp（递归状态尺寸）。
 */
object LocalMemoryModel {
    private const val TAG = "LocalMemModel"

    /**
     * KV 行对齐/布局 + 计算缓冲的安全系数。
     *
     * 2026-09-30 按实测校准 1.35 → 1.10：设备实测显示 KV 实际落在 HTP/DSP 侧内存，
     * 不占 host RSS（ctx=8192 f16 时加载后 MemAvailable 仅下降 2202MB，而我的保守模型
     * 预测 3936MB；另一次 ctx=4096 的裸跑进程 RSS 只有 335MB）。保留 1.10 是只覆盖
     * 行对齐/布局开销，不再重复计入已经不占 host 内存的部分——这样 9B 的 IQ3/Q3 档
     * 才不会被误拒。**注意：这不等于"KV 免费"**，KV 仍占 HTP 侧物理内存，所以
     * `residentMb = 文件全量` 这条底线保持不变。
     */
    private const val KV_SAFETY = 1.10

    /** 固定开销：llama-server 进程自身 + 计算图缓冲 + 采样器。 */
    const val FIXED_OVERHEAD_MB = 128

    /** 加载后内存审计底线：低于此值判定不可持续，自动卸载防卡死。 */
    const val POST_LOAD_MIN_AVAIL_MB = 800

    /**
     * 上下文窗口下限。必须装得下 Agent prompt（实测 3.2k~6k tokens）——低于此值会让
     * llama.cpp 陷入 context-shifting 死循环、零输出（2026-09-25 事故根因），
     * 所以宁可拒绝加载，也不给一个装不下 prompt 的窗口。
     */
    const val MIN_CTX = 4096

    /**
     * 默认上下文窗口上限（速度优先，2026-09-30 从 8192 回调）。
     *
     * 依据本机实测：同一 prompt（3674 tokens）在 ctx=8192 下 decode = 8.32 tok/s
     * （120.16 ms/token），而历史记录里 ctx≈3.1k 时有 11.2 tok/s —— KV 每 token 的读取量
     * 随窗口线性增长（f16 @8192 约 1152MB/token，@6144 约 864MB，@4096 约 576MB），
     * 在权重带宽已经吃紧的 HTP 路径上，这笔流量会直接反映到 tok/s。
     * Agent prompt 实测 3.2k~6k，6144 仍留 2k+ 输出余量；需要长会话可调大（实际窗口由
     * 可用内存决定，阶梯为 8192/6144/4096，不会超过本上限）。
     */
    const val DEFAULT_CTX_CAP = 6144

    /** 候选窗口，从大到小挑第一个装得下的。 */
    private val LADDER = intArrayOf(8192, 6144, MIN_CTX)

    /** q8_0：每 32 元素 34 字节。 */
    private const val Q8_0_BYTES_PER_ELEM = 34.0 / 32.0

    // ──────────────────────────────────────────── 模型内存画像

    /**
     * 模型的内存画像。
     * @param kvBytesPerToken 每 token 的 K+V 字节数（f16 基准），只统计真正带 KV 的层
     * @param recurrentBytes 递归层（线性注意力）的固定状态字节数，与 ctx 无关
     */
    data class Footprint(
        val arch: String,
        val nLayer: Int,
        val nKvLayer: Int,
        val nRecrLayer: Int,
        val kvBytesPerToken: Long,
        val recurrentBytes: Long,
        val src: String,
    ) {
        /** 给定上下文与 KV 类型下的 KV + 递归状态占用（MB，含安全系数）。 */
        fun kvMb(ctx: Int, kvType: String): Int {
            val perElem = if (kvType == "f16") 2.0 else Q8_0_BYTES_PER_ELEM
            val elemsPerToken = kvBytesPerToken / 2.0   // kvBytesPerToken 以 f16 计
            val bytes = elemsPerToken * perElem * ctx + recurrentBytes
            return (bytes / 1048576.0 * KV_SAFETY).toInt()
        }

        /** f16 下每 token 的 KV（MiB，含安全系数），便于对照实测校准。 */
        fun kvMiBPerTokenF16(): Double = kvBytesPerToken / 1048576.0 * KV_SAFETY
    }

    /**
     * GGUF 解析失败时的保守回退：等价于旧的 0.6 MiB/token(f16) / 0.3(q8_0) 行为，
     * 即"退回到改动前的安全性"，不会因为解析失败而放行不该放行的模型。
     */
    fun fallback(): Footprint = Footprint(
        arch = "unknown", nLayer = 0, nKvLayer = 0, nRecrLayer = 0,
        kvBytesPerToken = (0.6 * 1048576 / KV_SAFETY).toLong(),
        recurrentBytes = 0L,
        src = "fallback(保守 0.6MiB/token)",
    )

    fun describe(fp: Footprint): String =
        "arch=${fp.arch}, 层=${fp.nLayer}(KV层 ${fp.nKvLayer} / 递归层 ${fp.nRecrLayer}), " +
            "KV=${String.format(Locale.US, "%.3f", fp.kvMiBPerTokenF16())}MiB/token(f16), " +
            "递归状态=${fp.recurrentBytes / 1048576}MiB, 来源=${fp.src}"

    // ──────────────────────────────────────────── 决策

    data class Plan(
        val ctx: Int,
        val kvType: String,
        val kvMb: Int,
        val needMb: Int,
        val residentMb: Int,
        val availMb: Int,
        val fp: Footprint,
    ) {
        val summary: String
            get() = "n_ctx=$ctx, kv=$kvType(KV ${kvMb}MB), 权重常驻 ${residentMb}MB, " +
                "总需 ${needMb}MB, 可用 ${availMb}MB"
    }

    /**
     * 核心决策：在「权重常驻 + KV + 固定开销 ≤ 可用内存」前提下**取最大的上下文**，
     * 且**优先 f16 KV**（HTP 上 f16 比 q8_0 快 2.5 倍，q8_0 只作兜底）。
     *
     * @param residentMb 权重需常驻的内存量（dense=文件全量；MoE=激活参数量）
     * @param maxCtx 上下文上限（调用方指定）
     * @return null 表示当前可用内存下不可承载 —— 调用方应先做内存整理再重试
     */
    fun plan(
        residentMb: Int,
        fp: Footprint,
        availMb: Int,
        maxCtx: Int = 8192,
    ): Plan? {
        val cap = maxCtx.coerceAtLeast(MIN_CTX)
        // 第一轮一律 f16（速度优先）；只有 f16 全试不通才降 q8_0
        for (kvType in arrayOf("f16", "q8_0")) {
            for (ctx in LADDER) {
                if (ctx > cap) continue
                val kv = fp.kvMb(ctx, kvType)
                val need = residentMb + kv + FIXED_OVERHEAD_MB
                if (availMb >= need) return Plan(ctx, kvType, kv, need, residentMb, availMb, fp)
            }
        }
        return null
    }

    /** 拒绝加载时上报的最小需求（按下限窗口 + q8_0 兜底算）。 */
    fun minNeedMb(residentMb: Int, fp: Footprint): Int =
        residentMb + fp.kvMb(MIN_CTX, "q8_0") + FIXED_OVERHEAD_MB

    // ──────────────────────────────────────────── GGUF 头部解析
    //
    // 只读文件头部的 KV 元数据段（不读张量体），遇到 tokenizer.* 即提前结束，
    // 对 2~6GB 的模型文件耗时仅毫秒级。

    private val WANTED = setOf(
        "block_count",
        "embedding_length",
        "attention.head_count",
        "attention.head_count_kv",
        "attention.key_length",
        "attention.value_length",
        "attention.recurrent_layers",
        "full_attention_interval",
        "ssm.state_size",
        "ssm.inner_size",
        "ssm.time_step_rank",
        "ssm.group_count",
        "ssm.conv_kernel",
    )

    /** 读取模型内存画像；失败返回 null（调用方用 [fallback]）。 */
    fun probe(modelFile: File): Footprint? {
        if (!modelFile.isFile || modelFile.length() < (1L shl 20)) return null
        var ins: InputStream? = null
        return try {
            ins = BufferedInputStream(FileInputStream(modelFile), 1 shl 16)
            parseGgufHeader(ins)
        } catch (t: Throwable) {
            Log.w(TAG, "GGUF 元数据解析失败，回退保守估算：${t.message}")
            null
        } finally {
            runCatching { ins?.close() }
        }
    }

    // ──────────────────────────────────────────── 量化画像
    //
    // 兼容性矩阵要点：HTP 只对少数量化格式有「原生 tiled 布局」（没有逐行反量化），
    // 其余格式会退化成慢路径。所以必须知道模型**真正的**权重格式，而不是文件名里写的那个。
    // 做法：完整走一遍 GGUF 的 KV 段，再读张量信息表，按**元素数加权**统计类型分布。

    /** GGML 张量类型 id → 名称（取自 ggml/include/ggml.h:390-429）。 */
    private val GGML_TYPE_NAMES = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 6 to "Q5_0", 7 to "Q5_1",
        8 to "Q8_0", 9 to "Q8_1", 10 to "Q2_K", 11 to "Q3_K", 12 to "Q4_K", 13 to "Q5_K",
        14 to "Q6_K", 15 to "Q8_K", 16 to "IQ2_XXS", 17 to "IQ2_XS", 18 to "IQ3_XXS",
        19 to "IQ1_S", 20 to "IQ4_NL", 21 to "IQ3_S", 22 to "IQ2_S", 23 to "IQ4_XS",
        24 to "I8", 25 to "I16", 26 to "I32", 27 to "I64", 28 to "F64", 29 to "IQ1_M",
        30 to "BF16", 34 to "TQ1_0", 35 to "TQ2_0", 39 to "MXFP4",
    )

    /**
     * 模型的量化画像。
     * @param dominant 按**元素数加权**占比最高的 ggml 类型名（= 真正的权重格式）
     * @param share 该类型占全部权重元素的比例
     * @param histogram 类型 → 元素数占比，降序，最多 4 项
     */
    data class QuantInfo(
        val fileType: Int?,
        val dominant: String,
        val share: Double,
        val histogram: List<Pair<String, Double>>,
        val src: String,
    ) {
        fun describe(): String =
            "$dominant（${String.format(Locale.US, "%.0f%%", share * 100)} 权重元素" +
                histogram.drop(1).joinToString("") { "，${it.first} ${String.format(Locale.US, "%.0f%%", it.second * 100)}" } +
                "）"
    }

    private val quantCache = java.util.concurrent.ConcurrentHashMap<String, QuantInfo>()

    /** 读取模型的量化画像（按 path+length+mtime 缓存）；失败返回 null。 */
    fun probeQuant(modelFile: File): QuantInfo? {
        if (!modelFile.isFile || modelFile.length() < (1L shl 20)) return null
        val key = "${modelFile.absolutePath}|${modelFile.length()}|${modelFile.lastModified()}"
        quantCache[key]?.let { return it }
        var ins: InputStream? = null
        return try {
            ins = BufferedInputStream(FileInputStream(modelFile), 1 shl 16)
            parseGgufQuant(ins)?.also { quantCache[key] = it }
        } catch (t: Throwable) {
            Log.w(TAG, "量化解析失败，回退文件名判定：${t.message}")
            null
        } finally {
            runCatching { ins?.close() }
        }
    }

    private fun parseGgufQuant(ins: InputStream): QuantInfo? {
        val r = R(ins)
        if (String(r.buf(4), Charsets.US_ASCII) != "GGUF") return null
        r.u32()                                  // version
        val tensorCount = r.u64()
        val kvCount = r.u64()
        if (kvCount <= 0L || kvCount > 100_000L) return null
        if (tensorCount <= 0L || tensorCount > 200_000L) return null

        // 1) 走完元数据段（只关心 general.file_type，其余按类型跳过，不做分配）
        var fileType: Int? = null
        var i = 0L
        while (i < kvCount) {
            val key = r.str()
            val t = r.u32().toInt()
            if (key == "general.file_type") fileType = readInt(r, t).toInt() else skipValue(r, t)
            i++
        }

        // 2) 张量信息表：name / n_dims / dims[] / type / offset
        val elemsByType = HashMap<Int, Long>()
        i = 0L
        while (i < tensorCount) {
            r.str()                              // 张量名
            val nDims = r.u32().toInt()
            if (nDims < 0 || nDims > 8) throw IllegalArgumentException("非法维度数 $nDims")
            var elems = 1L
            var d = 0
            while (d < nDims) {
                val dim = r.u64()
                if (dim > 0) elems *= dim
                d++
            }
            val ty = r.u32().toInt()
            r.u64()                              // offset
            elemsByType[ty] = (elemsByType[ty] ?: 0L) + elems
            i++
        }
        val total = elemsByType.values.sum()
        if (total <= 0L) return null
        val hist = elemsByType.entries
            .sortedByDescending { it.value }
            .take(4)
            .map { (ty, n) -> (GGML_TYPE_NAMES[ty] ?: "type$ty") to n.toDouble() / total }
        return QuantInfo(
            fileType = fileType,
            dominant = hist.first().first,
            share = hist.first().second,
            histogram = hist,
            src = "gguf",
        )
    }

    /** 按 GGUF 类型定义跳过一个 value（不分配内存）。 */
    private fun skipValue(r: R, t: Int) {
        when (t) {
            0, 1, 7 -> r.u8()
            2, 3 -> r.skip(2)
            4, 5, 6 -> r.skip(4)
            10, 11, 12 -> r.skip(8)
            8 -> r.skip(r.u64())
            9 -> {
                val et = r.u32().toInt()
                val cnt = r.u64()
                if (cnt < 0 || cnt > 100_000_000L) throw IllegalArgumentException("非法数组长度 $cnt")
                if (et == 8) {
                    var k = 0L
                    while (k < cnt) { r.skip(r.u64()); k++ }
                } else {
                    val sz = intSize(et)
                    if (sz == 0) throw IllegalArgumentException("非法数组元素类型 $et")
                    r.skip(cnt * sz)
                }
            }
            else -> throw IllegalArgumentException("非法 GGUF value 类型 $t")
        }
    }

    private fun parseGgufHeader(ins: InputStream): Footprint? {
        val r = R(ins)
        if (String(r.buf(4), Charsets.US_ASCII) != "GGUF") return null
        r.u32()                       // version
        r.u64()                       // tensor_count
        val kvCount = r.u64()
        if (kvCount <= 0L || kvCount > 100_000L) return null

        var arch = ""
        val got = HashMap<String, Any>()
        var i = 0L
        while (i < kvCount) {
            val key = r.str()
            val type = r.u32().toInt()
            if (key == "general.architecture") {
                arch = readValue(r, type) as? String ?: return null
            } else {
                val v = readValue(r, type)
                if (arch.isNotEmpty() && key.startsWith("$arch.")) {
                    val suffix = key.substring(arch.length + 1)
                    if (suffix in WANTED && v != null) got[suffix] = v
                }
            }
            // tokenizer 段体积巨大且与内存账本无关，拿到关键字段就收工
            if (arch.isNotEmpty() && got.containsKey("block_count") && key.startsWith("tokenizer.")) break
            i++
        }
        if (arch.isEmpty()) return null

        val nLayer = (got["block_count"] as? Long)?.toInt() ?: return null
        if (nLayer !in 1..1024) return null

        fun intAt(v: Any?, il: Int, fallback: Int = 0): Int = when (v) {
            is Long -> v.toInt()
            is LongArray -> if (il in v.indices) v[il].toInt() else 0
            else -> fallback
        }

        val nEmbd = (got["embedding_length"] as? Long)?.toInt() ?: 0
        val headAny = got["attention.head_count"]
        val headKvAny = got["attention.head_count_kv"]
        var headLenK = (got["attention.key_length"] as? Long)?.toInt() ?: 0
        var headLenV = (got["attention.value_length"] as? Long)?.toInt() ?: 0
        if (headLenK <= 0) {
            val nh = intAt(headAny, 0)
            if (nh <= 0 || nEmbd <= 0) return null
            headLenK = nEmbd / nh
        }
        if (headLenV <= 0) headLenV = headLenK

        // 递归层（线性注意力）判定，与 llama.cpp 完全一致：
        // src/models/qwen35.cpp:17 优先读 *_attention.recurrent_layers（数组或模式整数），
        // 缺失则用 *_full_attention_interval 回退：(i+1) % interval != 0 即递归层。
        val recrAny = got["attention.recurrent_layers"]
        val interval = (got["full_attention_interval"] as? Long)?.toInt() ?: 0
        val isRecr = BooleanArray(nLayer)
        when {
            recrAny is LongArray && recrAny.size >= nLayer ->
                for (il in 0 until nLayer) isRecr[il] = recrAny[il] != 0L
            interval > 1 ->
                for (il in 0 until nLayer) isRecr[il] = (il + 1) % interval != 0
            recrAny is Long && recrAny.toInt() > 1 -> {
                // src/llama-hparams.cpp:24 set_recr_pattern(dense_first=false)
                val p = recrAny.toInt()
                for (il in 0 until nLayer) isRecr[il] = (il % p) < (p - 1)
            }
            else -> Unit   // 普通 dense 模型：全部层带 KV
        }

        var kvPerToken = 0L
        for (il in 0 until nLayer) {
            if (isRecr[il]) continue
            var nkv = intAt(headKvAny, il)
            if (nkv <= 0) nkv = intAt(headAny, il)
            if (nkv <= 0) continue
            kvPerToken += (headLenK.toLong() + headLenV.toLong()) * nkv * 2L
        }
        if (kvPerToken <= 0L) return null

        // 递归状态（F32，与 ctx 无关）：src/llama-hparams.cpp:208/236
        val dState = (got["ssm.state_size"] as? Long)?.toInt() ?: 0
        val dtRank = (got["ssm.time_step_rank"] as? Long)?.toInt() ?: 0
        val inner = (got["ssm.inner_size"] as? Long)?.toInt() ?: 0
        val group = (got["ssm.group_count"] as? Long)?.toInt() ?: 0
        val dConv = (got["ssm.conv_kernel"] as? Long)?.toInt() ?: 0
        val nRecr = isRecr.count { it }
        var recrBytes = 0L
        if (nRecr > 0) {
            val perLayer = if (dState > 0 && dtRank > 0 && inner > 0) {
                val stateElems = dState.toLong() * dState.toLong() * dtRank.toLong()
                val convElems = (dConv - 1).coerceAtLeast(0).toLong() * (inner + 2L * group * dState)
                (stateElems + convElems) * 4L
            } else {
                2L * 1024 * 1024   // 未知递归实现：保守按 2MiB/层
            }
            recrBytes = perLayer * nRecr
        }

        return Footprint(
            arch = arch,
            nLayer = nLayer,
            nKvLayer = nLayer - nRecr,
            nRecrLayer = nRecr,
            kvBytesPerToken = kvPerToken,
            recurrentBytes = recrBytes,
            src = "gguf",
        )
    }

    // ──────────────────────────────────────────── GGUF 底层读取

    private class R(private val ins: InputStream) {
        fun u8(): Int {
            val b = ins.read()
            if (b < 0) throw EOFException()
            return b
        }

        fun buf(n: Int): ByteArray {
            val a = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = ins.read(a, off, n - off)
                if (r < 0) throw EOFException()
                off += r
            }
            return a
        }

        fun skip(n: Long) {
            var left = n
            if (left <= 0) return
            val tmp = ByteArray(1 shl 16)
            while (left > 0) {
                val step = minOf(left, tmp.size.toLong()).toInt()
                val r = ins.read(tmp, 0, step)
                if (r < 0) throw EOFException()
                left -= r
            }
        }

        fun u16(): Long = u8().toLong() or (u8().toLong() shl 8)

        fun u32(): Long = u16() or (u16() shl 16)

        fun u64(): Long {
            var v = 0L
            for (i in 0 until 8) v = v or (u8().toLong() shl (8 * i))
            return v
        }

        fun f32(): Double = java.lang.Float.intBitsToFloat(u32().toInt()).toDouble()

        fun f64(): Double = java.lang.Double.longBitsToDouble(u64())

        fun str(): String {
            val n = u64()
            if (n < 0 || n > 1_000_000L) throw IllegalArgumentException("非法字符串长度 $n")
            return String(buf(n.toInt()), Charsets.UTF_8)
        }
    }

    private fun intSize(t: Int): Int = when (t) {
        0, 1, 7 -> 1
        2, 3 -> 2
        4, 5 -> 4
        10, 11 -> 8
        else -> 0
    }

    private fun readInt(r: R, t: Int): Long = when (t) {
        0, 1, 7 -> r.u8().toLong()
        2, 3 -> r.u16()
        4, 5 -> r.u32()
        10, 11 -> r.u64()
        else -> throw IllegalArgumentException("非整数类型 $t")
    }

    /** 读取一个 GGUF value；整数→Long，浮点→Double，字符串→String，整数数组→LongArray。 */
    private fun readValue(r: R, t: Int): Any? = when (t) {
        0, 1, 7, 2, 3, 4, 5, 10, 11 -> readInt(r, t)
        6 -> r.f32()
        12 -> r.f64()
        8 -> r.str()
        9 -> {
            val et = r.u32().toInt()
            val cnt = r.u64()
            if (cnt < 0 || cnt > 8_000_000L) throw IllegalArgumentException("非法数组长度 $cnt")
            if (et == 8) {
                // 字符串数组（如 tokenizer.ggml.tokens）：长度可变，只能逐个跳过
                var k = 0L
                while (k < cnt) {
                    r.skip(r.u64())
                    k++
                }
                null
            } else if (et == 6 || et == 12) {
                val sz = if (et == 6) 4 else 8
                r.skip(cnt * sz)
                null
            } else {
                val sz = intSize(et)
                if (sz == 0) throw IllegalArgumentException("非法数组元素类型 $et")
                val out = LongArray(cnt.toInt())
                var k = 0
                while (k < cnt.toInt()) {
                    out[k] = readInt(r, et)
                    k++
                }
                out
            }
        }
        else -> throw IllegalArgumentException("非法 GGUF value 类型 $t")
    }
}
