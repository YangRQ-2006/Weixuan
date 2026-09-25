package cn.yangrq.weixuan.local

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * 本地模型（GenieX NPU/GPU）契约层。
 *
 * 架构：**Eta 的 Agent 框架与 Provider 层保持不变**，本地模型以「回环 OpenAI 兼容服务」接入：
 *
 * ```
 * AgentLoop / 工具执行 / UI  ← 全部复用 Eta 原实现
 *        ↑
 * OpenAiChatCompletionsProvider（Eta 原生，未改动）
 *        ↑  http://127.0.0.1:<port>/v1/chat/completions  (SSE)
 * LocalOpenAiServer（本地回环服务）
 *        ↑
 * GenieXLocalEngine（llama.cpp 模板 + 流式生成 + 工具调用解析）
 *        ↑
 * GenieX SDK（高通 NPU/GPU：libQnnHtp* + libgeniex-proc.so）
 * ```
 *
 * 本文件只放共享类型，实现见 [GenieXLocalEngine] / [LocalOpenAiServer]。
 */

/** 本地推理引擎生命周期状态。 */
enum class LocalEngineStatus {
    /** 尚未初始化 GenieX SDK。 */
    UNINITIALIZED,

    /** SDK 就绪，但没有加载模型（内存未占用）。 */
    READY_TO_LOAD,

    /** 正在加载模型（可能耗时数十秒）。 */
    LOADING,

    /** 模型已就绪，可接受请求。 */
    READY,

    /** 初始化或加载失败，[LocalEngineState.message] 为原因。 */
    ERROR,
}

/** 一台设备上可用的（或可下载的）GenieX 模型。 */
data class LocalModelEntry(
    val name: String,
    val precision: String,
    val displayName: String,
    val installed: Boolean,
    val modelPath: String? = null,
    val tokenizerPath: String? = null,
    val modelType: String = "LLM",
    val sizeBytes: Long = 0L,
) {
    val key: String get() = "$name@$precision"
}

/** 引擎对外可见的运行态快照。 */
data class LocalEngineState(
    val status: LocalEngineStatus = LocalEngineStatus.UNINITIALIZED,
    val modelName: String = "",
    val precision: String = "",
    val message: String = "",
    val nCtx: Int = 0,
    val computeUnit: String = "",
    val speculativeDecoding: Boolean = false,
    val lastSpeedTokensPerSecond: Double = 0.0,
    val lastGeneratedTokens: Long = 0,
) {
    val isReady: Boolean get() = status == LocalEngineStatus.READY
}

/** 模型给出的单次工具调用。 */
data class LocalToolCall(
    val index: Int,
    val id: String,
    val name: String,
    /** 参数 JSON 字符串（对象字面量），可能经过容错修复。 */
    val argumentsJson: String,
)

/** 一轮生成的用量统计（来自 GenieX ProfilingData）。 */
data class LocalUsage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val tokensPerSecond: Double = 0.0,
    val ttftMs: Double = 0.0,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

/**
 * 引擎产出的事件流。服务端把它序列化成 OpenAI SSE 分片，
 * 因此这里只表达「内容增量 / 工具调用 / 用量 / 结束」四类语义。
 */
sealed interface LocalStreamEvent {
    /** 思考内容增量（模型带  thinking 时）。 */
    data class ReasoningDelta(val text: String) : LocalStreamEvent

    /** 可见正文增量。 */
    data class TextDelta(val text: String) : LocalStreamEvent

    /** 一个完整的工具调用（解析完成后一次给出）。 */
    data class ToolCall(val call: LocalToolCall) : LocalStreamEvent

    /** 用量统计，通常在流末尾给出。 */
    data class Usage(val usage: LocalUsage) : LocalStreamEvent

    /** 正常结束，reason ∈ {stop, tool_calls, length}。 */
    data class Finished(val reason: String) : LocalStreamEvent

    /** 失败终止，服务端应转成 OpenAI 错误响应。 */
    data class Failed(val message: String, val httpCode: Int = 500) : LocalStreamEvent
}

/**
 * 本地聊天引擎统一接口。
 *
 * 约定：
 * 1. [streamChat] 的入参是 **OpenAI wire 格式**（与 Eta Provider 层一致），实现方负责
 *    转成 GenieX ChatMessage + chat template，并把输出反向解析成 [LocalStreamEvent]；
 * 2. collector 取消（客户端断开）时必须停止底层生成（stopStream）；
 * 3. 引擎同一时刻只服务一个生成任务（nSeqMax = 1），并发请求返回 [LocalStreamEvent.Failed]。
 */
interface LocalChatEngine {

    val state: StateFlow<LocalEngineState>

    val isReady: Boolean

    /** 初始化 GenieX SDK（幂等）。 */
    suspend fun initialize(context: Context): Result<Unit>

    /** 列出本机已安装的 GenieX 模型。 */
    suspend fun refreshInstalledModels(): List<LocalModelEntry>

    /** 从模型中心下载模型；[onProgress] 取值 0.0~1.0。 */
    suspend fun downloadModel(
        name: String,
        precision: String,
        onProgress: (Double) -> Unit = {},
    ): Result<Unit>

    /** 加载模型（[name] 为 null 时使用当前选中的模型），幂等：已加载同一模型直接返回成功。 */
    suspend fun loadModel(name: String? = null): Result<Unit>

    /** 卸载模型并释放内存。 */
    suspend fun unload()

    /** 中止当前生成（用户停止 / 客户端断开 / 资源告急）。 */
    fun cancelGeneration()

    /** 当前已加载模型名；未加载返回空串。 */
    fun currentModelName(): String

    /**
     * 执行一轮 OpenAI 兼容对话，产出流式事件。
     *
     * @param messages OpenAI `messages` 数组（role/content/tool_calls/tool_call_id）。
     * @param tools    OpenAI `tools` 数组（type=function + function.{name,description,parameters}）。
     * @param maxTokens 请求侧 max_tokens，可为 null（用引擎自适应值）。
     */
    fun streamChat(
        messages: JSONArray,
        tools: JSONArray,
        maxTokens: Int? = null,
    ): Flow<LocalStreamEvent>
}
