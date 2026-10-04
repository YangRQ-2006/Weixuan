package cn.yangrq.weixuan.local

import android.os.Build
import java.io.File
import java.util.Locale

/**
 * 后端 × 模型 × 设备 兼容性判定（2026-10-02，移植自 PocketOrca-LLM 的兼容性体系）。
 *
 * ## 为什么要抄这套
 *
 * PocketOrca 的 `profiles.json` 给每个引擎挂了一份 `requires` 量化白名单，
 * 代码里用 `ModelFileHelper.quantOf()` 把模型文件判成 `npuOk` 再决定是否放行，
 * README 里再配一张 **SoC × 引擎** 的实测矩阵。三件事合起来解决的是同一个问题：
 * **"在跑之前就知道跑不跑得起来、会不会白慢"**。
 *
 * 微玄原来缺这一层：用户/Agent 选了模型 + 后端，要等 `llama-server` 起来、
 * 甚至等 decode 慢十倍才发现不对。本文件把判定前移到 UI 与启动之前。
 *
 * ## 判定依据（都取自本机源码或本机实测，不是抄来的说法）
 *
 * ### HTP 原生 repack 白名单
 * `ggml-hexagon.cpp:266-271` 的 `ggml_hexagon_is_repack_type()` —— 命中这些格式，
 * 权重会被重排成 DSP 侧的 tiled 布局、**没有逐行反量化**；再叠加
 * `ggml_hexagon_is_hmx_weight_type()` 里的 F16/F32。**不在这个集合里的格式会走慢路径。**
 *
 * ### 与 PocketOrca 的差异（这就是微玄的优势所在）
 * PocketOrca 的上游构建白名单 = {Q4_0, Q4_1, Q8_0, IQ4_NL, MXFP4, F16, F32}；
 * 微玄所用的 GenieX 后端**多出 Q6_K 与 Q4_K**（源码里另有 Q4_K 专用 tile 尺寸）。
 * 本机实测印证：4B-Q4_K_M 在 NPU 上 decode 15.6 t/s、纯 CPU 仅 4.2 t/s（3.7×）。
 *
 * ### 设备侧实测结论
 * - `Vulkan0`（Adreno 840 / SM8850）能起来但**输出乱码**，prefill 慢 40 倍 → 判不可用；
 * - OpenCL 在 exec 子进程下 `platform IDs not available`（无 sphal 命名空间）→ 判不可用。
 */
object ModelCompat {
    private const val TAG = "ModelCompat"

    /** HTP 原生 tiled 布局白名单（= 不会被逐行反量化）。 */
    val HTP_NATIVE = setOf("Q4_0", "Q4_1", "Q8_0", "IQ4_NL", "MXFP4", "Q6_K", "Q4_K", "F16", "F32")

    /** PocketOrca（上游 llama.cpp）的白名单，少 Q4_K/Q6_K。仅用于文档与对照。 */
    val HTP_NATIVE_UPSTREAM = setOf("Q4_0", "Q4_1", "Q8_0", "IQ4_NL", "MXFP4", "F16", "F32")

    /** Q4_K 家族的别名：GGUF 张量类型只记到 Q4_K，`_S/_M/_L` 是文件级又叫法。 */
    private val FILENAME_QUANT = Regex(
        "(Q[2-8]_K_[SML]|Q[2-8]_K|Q[2-8]_[01]|IQ[1-4]_[A-Z]+|MXFP4|BF16|F16|F32)",
        RegexOption.IGNORE_CASE,
    )

    enum class Level { OK, DEGRADED, UNSUPPORTED }

    data class Verdict(
        val level: Level,
        /** 一句话结论，直接进 UI。 */
        val headline: String,
        /** UI 列表用的极简徽章文案（单行，避免 summary 被截断）。 */
        val short: String,
        /** 具体原因与依据。 */
        val detail: String,
    ) {
        /** 能不能尝试启动（DEGRADED 允许，但要在 UI 上说清楚）。 */
        val canRun: Boolean get() = level != Level.UNSUPPORTED
    }

    /** SoC 型号，用于设备矩阵匹配（Android 12+ 有 Build.SOC_MODEL）。 */
    fun socModel(): String {
        val soc = runCatching { Build.SOC_MODEL }.getOrNull()
        if (!soc.isNullOrBlank() && soc != Build.UNKNOWN) return soc
        return runCatching { Build.HARDWARE }.getOrNull() ?: "unknown"
    }

    /**
     * 读取模型量化画像；GGUF 解析失败时退回文件名正则（PocketOrca 的做法）。
     * @return null 表示完全判不出来（此时兼容性判定一律放行，不误伤）
     */
    fun quantOf(modelFile: File?): LocalMemoryModel.QuantInfo? {
        if (modelFile == null || !modelFile.isFile) return null
        LocalMemoryModel.probeQuant(modelFile)?.let { return it }
        val m = FILENAME_QUANT.find(modelFile.name) ?: return null
        val name = m.groupValues[1].uppercase(Locale.US)
        // 文件名里的 Q4_K_M / Q6_K 等归一到 GGUF 张量类型名
        val normalized = when {
            name.startsWith("Q2_K") || name.startsWith("Q3_K") ||
                name.startsWith("Q4_K") || name.startsWith("Q5_K") || name.startsWith("Q6_K") -> name.substringBefore("_K") + "_K"
            else -> name
        }
        return LocalMemoryModel.QuantInfo(
            fileType = null,
            dominant = normalized,
            share = 1.0,
            histogram = listOf(normalized to 1.0),
            src = "filename",
        )
    }

    /**
     * 综合判定：后端 × 量化 × 设备。
     *
     * @param quant 为 null 时只做设备侧判定（模型相关的降级不再拦截）
     */
    fun check(backend: LocalBackend, quant: LocalMemoryModel.QuantInfo?, soc: String = socModel()): Verdict {
        val device = deviceVerdict(backend, soc)
        if (device != null) return device
        return when (backend) {
            LocalBackend.CPU -> Verdict(Level.OK, "CPU 全格式可用", "✅ CPU 可用（全格式）", "CPU 后端对所有 GGML 类型都有实现，无格式风险")
            LocalBackend.NPU_HTP -> htpVerdict(quant)
            LocalBackend.GPU_OPENCL -> openclVerdict(quant)
            LocalBackend.GPU_VULKAN -> Verdict(
                Level.DEGRADED,
                "Vulkan 未在本机验证过输出正确性",
                "⚠️ Vulkan（输出待核对）",
                "本机（$soc）尚未测出可用的 Vulkan 结果；启用后请先确认输出不是乱码",
            )
        }
    }

    /** 设备/架构级的硬性结论（与模型无关），null = 该后端在本机没有已知硬伤。 */
    private fun deviceVerdict(backend: LocalBackend, soc: String): Verdict? = when (backend) {
        LocalBackend.GPU_OPENCL -> Verdict(
            Level.UNSUPPORTED,
            "Adreno OpenCL 在当前进程模型下不可用",
            "⛔ OpenCL 不可用（无 sphal）",
            "子进程拿不到 Android 的 sphal 命名空间，厂商驱动 libOpenCL_adreno.so 无法加载；" +
                "实测日志为 `ggml_opencl: platform IDs not available.`（2026-10-02）。" +
                "要让 OpenCL 复活必须改走 App 进程内 JNI + manifest uses-native-library。",
        )
        LocalBackend.GPU_VULKAN -> if (soc.contains("8850", ignoreCase = true)) Verdict(
            Level.UNSUPPORTED,
            "Adreno 840（$soc）Vulkan 输出乱码",
            "⛔ Vulkan 输出乱码（本机实测）",
            "本机实测：Vulkan0 可枚举（19.1GB 可用）但输出为 `@@@@@@@@`，且 prefill 4.3 t/s " +
                "对 HTP0 的 184 t/s 慢 40 倍（2026-10-02）。在修复精度问题前不要用它跑正式推理。",
        ) else null
        else -> null
    }

    private fun htpVerdict(quant: LocalMemoryModel.QuantInfo?): Verdict {
        if (quant == null) {
            return Verdict(Level.OK, "无法判定量化格式，按可用处理", "❔ 量化未知，按可用处理", "GGUF 头部解析失败（可能是分片或非标准文件）")
        }
        val d = quant.dominant
        return if (d in HTP_NATIVE) {
            Verdict(
                Level.OK,
                "格式匹配：$d 在 HTP 原生白名单内",
                "✅ $d 匹配 HTP 原生格式",
                "${quant.describe()}；该格式会被重排成 DSP tiled 布局，无逐行反量化开销" +
                    "（依据 ggml-hexagon.cpp:266-271 的 ggml_hexagon_is_repack_type）",
            )
        } else {
            Verdict(
                Level.DEGRADED,
                "格式不匹配：$d 不在 HTP 原生白名单，会明显变慢",
                "⚠️ $d 非原生格式，会明显变慢",
                "${quant.describe()}；HTP 原生只覆盖 ${HTP_NATIVE.joinToString("/")}，" +
                    "其余格式的权重矩阵乘会退化为反量化 + 通用路径。建议换 Q4_0 / IQ4_NL / Q4_K / Q6_K / Q8_0 的 GGUF。",
            )
        }
    }

    private fun openclVerdict(quant: LocalMemoryModel.QuantInfo?): Verdict = Verdict(
        Level.UNSUPPORTED,
        "OpenCL 不可用",
        "⛔ OpenCL 不可用（无 sphal）",
        "见设备判定：exec 子进程没有 sphal 命名空间，取不到 Adreno 驱动。",
    )

    /** UI 用的单行摘要（列表里用极简 short，避免 summary 被截断）。 */
    fun badge(verdict: Verdict): String = verdict.short

    /** 完整徽章（带结论句），用于日志与详情。 */
    fun badgeFull(verdict: Verdict): String = when (verdict.level) {
        Level.OK -> "✅ ${verdict.headline}"
        Level.DEGRADED -> "⚠️ ${verdict.headline}"
        Level.UNSUPPORTED -> "⛔ ${verdict.headline}"
    }
}
