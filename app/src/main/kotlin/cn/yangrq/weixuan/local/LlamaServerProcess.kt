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
        val kvMb = (contextSize * 0.3).toInt()
        val needMb = residentMb + kvMb + 768
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
            Log.e(TAG, "内存不足拒绝加载：需要 ${needMb}MB [$kind + KV ${kvMb} + 余量 1024]，当前可用 ${availMb}MB")
            return false
        }
        Log.i(TAG, "内存预检通过：需要 ${needMb}MB [$kind/可用 ${availMb}MB]")
        val cmd = listOf(
            bin.absolutePath,
            "-m", modelPath,
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "-c", contextSize.toString(),
            "-t", "4",
            "-ctk", "q8_0",
            "-ctv", "q8_0",
            "-fa", "on",
            "-ngl", "99",
            "--no-warmup",
            "-np", "1",
        )
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
