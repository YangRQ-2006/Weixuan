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

    /** KV 行对齐/布局 + 计算缓冲的安全系数。 */
    private const val KV_SAFETY = 1.35

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
