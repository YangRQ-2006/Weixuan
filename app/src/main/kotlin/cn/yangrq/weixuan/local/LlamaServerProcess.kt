package cn.yangrq.weixuan.local

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自建 llama.cpp runtime（llama-server 子进程）管理器。
 *
 * 为什么自建：GenieX SDK 的模型加载是大块预分配（实测 14B 加载 RSS 冲到 4.3GB+ 并拖垮
 * 整个系统，Launcher 被杀、桌面壁纸丢失）；而官方 llama.cpp 的 mmap 是按需分页
 * （9GB 模型仅需几百 MB 物理内存）+ 支持 KV 量化（内存减半）、flash-attn、prompt cache。
 *
 * 二进制以 libllama-server.so 形式打包在 nativeLibraryDir（Android 可执行文件规范），
 * 监听与微玄原服务相同的端口，Agent 侧零改动。
 */
object LlamaServerProcess {
    private const val TAG = "LlamaServer"

    @Volatile
    private var process: Process? = null

    fun isRunning(): Boolean = process?.isAlive == true

    /**
     * 启动 llama-server。参数（全部来自官方 llama.cpp，2026-09-25 源码确认）：
     * - `-ctk/-ctv q8_0`：KV cache 量化（内存减半，窗口可加倍）
     * - `-fa on`：flash attention（更快 prefill）
     * - `--mmap`（默认）：权重按需分页（14B 只驻留几百 MB —— 解决崩溃的核心）
     * - `-c`：上下文窗口
     */
    fun start(context: Context, modelPath: String, port: Int, contextSize: Int = 6144): Boolean {
        if (isRunning()) return true
        // 动态窗口：按当前可用内存自适应——内存充足时给"完整工具 schema"留空间（Agent 多步
        // 规划依赖完整描述），内存紧张时自动降级以避免请求超窗（HTTP 400）。
        val preAvailMb = runCatching {
            File("/proc/meminfo").readLines()
                .firstOrNull { it.startsWith("MemAvailable") }
                ?.filter { it.isDigit() }?.toInt()?.div(1024)
                ?: 0
        }.getOrDefault(0)
        val ctx = when {
            preAvailMb >= 4600 -> 6144
            preAvailMb >= 3800 -> 4096
            else -> 2816
        }
        Log.i(TAG, "动态窗口：可用 ${preAvailMb}MB → n_ctx=$ctx")
        val bin = File(context.applicationInfo.nativeLibraryDir, "libllama-server.so")
        if (!bin.exists()) {
            Log.e(TAG, "llama-server 二进制不存在：${bin.absolutePath}")
            return false
        }
        // 内存预检（2026-09-25 事故教训 + MoE 支持）：
        // - dense 模型：mmap 不减少推理总内存需求——推理必然读入全部权重页 → 按文件全量估算；
        // - MoE 模型（文件名含 "-A#B"，如 Qwen3-30B-A3B）：每 token 仅激活少数专家，
        //   推理只读激活专家的权重页 → 按激活参数量估算（而非 17GB 文件全量）。
        val modelFile = File(modelPath)
        val fileMb = (modelFile.length() / 1024 / 1024).toInt()
        val moeActivatedB = Regex("""-A(\d+)B""").find(modelFile.name)?.groupValues?.get(1)?.toIntOrNull()
        val residentMb = if (moeActivatedB != null) moeActivatedB * 600 else fileMb
        // f16 KV：每 token 每层 2*head_dim*n_kv_heads*2byte（q8_0 时的系数是 0.3）
        val kvMb = (ctx * 0.6).toInt()
        // 投机解码的 draft 模型额外内存（0.6B Q4_K_M ≈ 380MB；仅启用时计入）
        val hasDraft = File(modelPath).parentFile
            ?.let { File(it, "Qwen3-0.6B-Q4_K_M.gguf").exists() } == true &&
            !modelPath.contains("0.6B")
        val needMb = residentMb + kvMb + (if (hasDraft) 380 else 0) + 32
        val availMb = runCatching {
            File("/proc/meminfo").readLines()
                .firstOrNull { it.startsWith("MemAvailable") }
                ?.filter { it.isDigit() }?.toInt()?.div(1024)
                ?: 0
        }.getOrDefault(0)
        // 先尽力释放自家资源（System.gc；不触碰用户后台应用）
        val kind = if (moeActivatedB != null) "MoE(激活 ${moeActivatedB}B->${residentMb}MB)" else "dense(全量 ${fileMb}MB)"
        System.gc()
        if (availMb in 1 until needMb) {
            Log.e(TAG, "内存不足拒绝加载：需要 ${needMb}MB [$kind + KV ${kvMb} + 余量 128]，当前可用 ${availMb}MB")
            return false
        }
        Log.i(TAG, "内存预检通过：需要 ${needMb}MB [$kind/可用 ${availMb}MB]")
        // 投机解码（draft 0.6B，2026-09-27）：同词表小模型草拟 → 4B 并行验证，
        // decode 吞吐可提升 2-3x，且输出与目标模型一致（无损）。draft 与 target 同为
        // Qwen3 系列，词表相同（都是 151936）。
        val draftFile = File(modelPath).parentFile?.let { File(it, "Qwen3-0.6B-Q4_K_M.gguf") }
        // 实测（2026-09-27）：在 Hexagon NPU 后端上投机解码**无收益**——
        // decode 11.0 vs 12.0 tok/s（持平），prefill 107.8 vs 254 tok/s（腰斩，draft 抢占
        // NPU 算力），且多占 380MB 内存。故默认禁用；代码保留以备 CPU 后端场景启用。
        val speculativeArgs = emptyList<String>()

        val cmd = listOf(
            bin.absolutePath,
            "-m", modelPath,
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "-c", ctx.toString(),
            "-t", "4",
            // KV 用 f16（2026-09-27 实测突破）：HTP 的 flash-attn 对 q8_0 KV 需要逐行
            // 反量化（每 token 数百万行），长上下文下成为 decode 主瓶颈。改 f16 后实测：
            // 长 prompt（3.1k tok）decode 4.4 → 11.2 tok/s（+155%），短 prompt 12 → 18.7。
            // 代价仅为 KV 内存 ×2（不削模型、不缩上下文、不减工具）。
            "-ctk", "f16",
            "-ctv", "f16",
            "-fa", "on",
            "-ngl", "99",
            "--no-warmup",
            // KV 前缀复用（2026-09-27，关键优化）：Agent 每轮请求的 system + 31 个工具
            // schema（约 5941 tokens）完全不变，只有历史增量。默认实测 cache_n=1 即全量重算
            // （NPU 254 tok/s 下约 23 秒/轮）。开启后复用公共前缀 KV，让 prefill 只算增量。
            "--cache-reuse", "256",
            "-np", "1",
        ) + speculativeArgs
        Log.i(TAG, "启动 llama-server：${cmd.joinToString(" ")}")
        return runCatching {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            // 交叉编译产物无 RPATH：显式指定依赖库搜索路径（nativeLibraryDir 内含全部依赖 .so）
            pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            // HTP(NPU) 的 DSP 端库搜索路径（Qualcomm 标准变量——缺它 HTP session 打不开：
            // "HTP0 failed to open session ... libggml-htp-v81.so not found"）
            pb.environment()["ADSP_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            pb.environment()["DSP_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            // ggml 后端插件必须显式指定（本环境下 llama.cpp 的"可执行文件目录扫描"不生效，
            // 否则会 no backends are loaded → 模型加载失败）
            pb.environment()["GGML_BACKEND_PATH"] =
                File(context.applicationInfo.nativeLibraryDir, "libggml-cpu-arm64.so").absolutePath
            // 加速后端：优先 NPU（Hexagon adapter 桥接 GenieX 预编译后端）；Vulkan(GPU) 因 Adreno 840
            // 精度问题暂不启用
            val extraBackend = listOf("libggml-hexagon-adapter.so")
                .map { File(context.applicationInfo.nativeLibraryDir, it) }
                .firstOrNull { it.exists() }
            if (extraBackend != null) {
                pb.environment()["WEIXUAN_HEXAGON_BACKEND"] = extraBackend.absolutePath
                // Hexagon 忙轮询（2026-09-27 实测）：阻塞等待 DSP 响应存在唤醒延迟，
                // 改用忙轮询（timeo=0）可去掉这部分固定开销；与 f16 KV 配合实测
                // 长上下文 decode 4.4 → 11.2 tok/s。
                pb.environment()["GGML_HEXAGON_OPPOLL"] = "1"
            }
            val p = pb.start()
            process = p
            Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { Log.i(TAG, it) } }
            }.apply { isDaemon = true }.start()
            // 等待端口就绪（14B 的 mmap 加载需要时间，最多 90 秒）
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline) {
                if (probe(port)) {
                    Log.i(TAG, "llama-server 就绪：http://127.0.0.1:$port/v1")
                    return@runCatching true
                }
                if (!p.isAlive) {
                    Log.e(TAG, "llama-server 进程提前退出")
                    return@runCatching false
                }
                Thread.sleep(500)
            }
            Log.e(TAG, "llama-server 就绪超时（90 秒）")
            false
        }.getOrElse {
            Log.e(TAG, "llama-server 启动失败：${it.message}")
            false
        }
    }

    fun stop() {
        runCatching { process?.destroy() }
        process = null
    }

    private fun probe(port: Int): Boolean = runCatching {
        val conn = (URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection)
        conn.connectTimeout = 800
        conn.readTimeout = 800
        val code = conn.responseCode
        conn.disconnect()
        code == 200
    }.getOrDefault(false)
}
