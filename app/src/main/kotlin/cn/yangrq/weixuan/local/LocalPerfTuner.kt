package cn.yangrq.weixuan.local

import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig

/**
 * 本地推理调优器（移植自微玄 InferenceOptimizer，对接 GenieX SDK）：
 *
 * 1. SWA 滑动窗口固定 KV Cache   -> GenerationConfig.slidingWindow + slidingWindowNKeep
 * 2. 推测解码（0.6B draft + 主模型 verify） -> ModelConfig.spec_type/spec_draft_model/spec_n_max...
 * 3. 自适应上下文/KV 预算        -> ModelConfig.nCtx（由 [LocalResourceGuard] 内存预算决定）
 * 4. 自适应批大小/线程           -> nBatch/nUBatch/nThreads（温控降级）
 * 5. 混合 GPU+NPU                -> compute_unit "hybrid"，失败回退 "npu"
 *
 * 说明：mmap/mlock 与 KV Cache 量化在 GenieX SDK v0.7.0 未暴露，由 native（llama.cpp）自行管理；
 * prefix cache 亦为 native 自动前缀复用，应用层保持 prompt 前缀稳定（不 reset 会话）即可触发。
 */
object LocalPerfTuner {

    const val DRAFT_MODEL_NAME = "Qwen/Qwen3-0.6B"
    const val DRAFT_MODEL_PRECISION = "Q8_0"
    /** 推测解码类型：2026-09-25 实测 ngram-cache 在多轮请求后出现服务线程挂死
     *  （TCP 可连但无响应、连接排队），已回滚禁用；eagle/draft 类需专用模型（普通 GGUF 崩溃）。
     *  待 SDK 后续版本修复后再评估。 */
    const val SPEC_TYPE = ""
    const val SPEC_N_MAX = 8
    const val SWA_N_KEEP = 512
    const val BASE_MAX_TOKENS = 1024
    const val COMPUTE_UNIT_HYBRID = "hybrid"
    const val COMPUTE_UNIT_NPU = "npu"
    /** 纯 CPU 后端：hybrid 在部分设备推理挂死（2026-09-25），CPU 保底可通链路。 */
    const val COMPUTE_UNIT_CPU = "cpu"
    const val RUNTIME_LLAMA_CPP = "llama_cpp"

    /** 主模型规模估算（MB），用于加载前内存预检（Qwen3-8B Q4_0 ≈ 4.7GB）。 */
    const val MODEL_ESTIMATE_MB_8B = 4800

    /** 27B Q4_0 约 15GB，用于预检时给出明确拒绝理由。 */
    const val MODEL_ESTIMATE_MB_27B = 15_000

    fun buildModelConfig(guard: LocalResourceGuard, draftModelPath: String?, modelMb: Int = 0): ModelConfig {
        // 大模型低内存模式（2026-09-25）：小窗口 + 小 batch + 纯 CPU（免 NPU ION 额外分配）
        val lowMem = guard.lowMemoryMode(modelMb)
        val batch = if (lowMem) 64 else guard.optimalBatchSize()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        // ngram 类 spec 免 draft 模型（官方 spec_draft_model 传空即可）；eagle/draft 类才需要。
        val ngramSpec = SPEC_TYPE.startsWith("ngram")
        val specEnabled = ngramSpec || !draftModelPath.isNullOrEmpty()
        return ModelConfig(
            nCtx = if (lowMem) 1024 else guard.optimalContextWindow(modelMb),
            nThreads = threads,
            nThreadsBatch = threads,
            nBatch = batch,
            nUBatch = if (lowMem) 64 else minOf(batch, 512),
            nSeqMax = 1,
            nGpuLayers = if (lowMem) 0 else 99,
            spec_type = if (specEnabled) SPEC_TYPE else "",
            spec_draft_model = draftModelPath ?: "",
            spec_n_max = if (specEnabled && !ngramSpec) ((SPEC_N_MAX * guard.speedFactor()).toInt().coerceAtLeast(1)) else 0,
            spec_n_min = if (specEnabled && !ngramSpec) 1 else 0,
            spec_p_min = if (specEnabled && !ngramSpec) 0.5f else 0f,
            // HTP 电源模式（2026-09-25）：官方文档明确"未显式设置则零初始化 = LOW_POWER_SAVER(0)
            // = HTP 最低频运行"，必须显式设为 burst（爆发模式=满频）才能发挥 NPU 全部性能。
            power_mode = "burst",
        )
    }

    /**
     * 构建自适应 GenerationConfig：
     * - SWA 滑动窗口固定 KV Cache
     * - maxTokens 优先使用请求侧值（Agent 单步通常 200~800），否则按温控系数缩放
     */
    fun buildGenerationConfig(
        guard: LocalResourceGuard,
        maxTokensOverride: Int? = null,
        temperature: Float = 0.7f,
    ): GenerationConfig {
        val cfg = guard.preInferenceCheck()
        val maxTokens = (maxTokensOverride?.takeIf { it > 0 } ?: cfg.maxTokens).coerceIn(64, 4096)
        return GenerationConfig(
            maxTokens = maxTokens,
            samplerConfig = SamplerConfig(
                temperature = temperature,
                topP = 0.9f,
                topK = 40,
                repetitionPenalty = 1.1f,
            ),
            slidingWindow = true,
            slidingWindowNKeep = SWA_N_KEEP,
        )
    }
}
