package cn.yangrq.weixuan.local

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * BigMoeOnEdge 流式 MoE 引擎（bmoe-cli）子进程管理器。
 *
 * 解决的问题：MoE 大模型（如 Qwen3-30B-A3B，13–18GB）体积远超手机可用内存——
 * 传统 mmap 会让 page cache 膨胀到模型全量，触发 lowmemorykiller 连锁杀进程
 * （2026-09-27 实测：MemAvailable 掉到 1.5GB，设置/相机/壁纸/桌面被杀）。
 *
 * BigMoeOnEdge 的做法（无损）：hook llama.cpp 的 expert-ready 回调，每 token 只把
 * 「该 token 实际激活的专家」从存储读入（30B-A3B 约 89 MiB/token），热专家进 LRU 缓存
 * （实测 78% 命中），I/O 与计算重叠。输出与全量常驻逐字节一致。
 *
 * 集成方式：与 LlamaServerProcess 同构——二进制以 libbmoe-cli.so 打包在
 * nativeLibraryDir（Android 只允许执行该目录下的 lib*.so），通过 session 模式常驻：
 * 模型只加载一次，之后每个请求走 stdin 的一行 JSON、响应走 stdout 的 `BMOE_*` 行。
 */
object BmoeCliProcess {
    private const val TAG = "BmoeCli"

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdin: BufferedWriter? = null

    @Volatile
    private var pumpThread: Thread? = null

    @Volatile
    private var ready = false

    @Volatile
    private var currentId = 0

    @Volatile
    private var onDelta: ((String) -> Unit)? = null

    @Volatile
    private var onDone: ((String) -> Unit)? = null

    fun isRunning(): Boolean = process?.isAlive == true

    fun isReady(): Boolean = ready && isRunning()

    /**
     * 启动常驻 session。
     *
     * @param cacheMb 专家缓存预算：`auto`（按当前可用内存自适应，自动给系统留余量，推荐）
     *   或显式 MiB。
     * @param ubatch 计算缓冲上限：越小则把内存还给专家缓存（prefill 略慢，decode 不受影响）。
     */
    fun start(
        context: Context,
        modelPath: String,
        ctxSize: Int = 4096,
        cacheMb: String = "auto",
        ioThreads: Int = 4,
        ubatch: Int = 512,
        threads: Int = 6,
    ): Boolean {
        if (isReady()) return true
        stop()
        val libDir = context.applicationInfo.nativeLibraryDir
        val bin = File(libDir, "libbmoe-cli.so")
        if (!bin.exists()) {
            Log.e(TAG, "bmoe-cli 不存在：${bin.absolutePath}")
            return false
        }
        val model = File(modelPath)
        if (!model.exists()) {
            Log.e(TAG, "模型不存在：$modelPath")
            return false
        }

        // 内存预检：MoE 流式虽按需读取，但 mmap 页缓存仍会随访问增长；可用内存过低时
        // 直接拒绝，避免 2026-09-27 那类 lowmemorykiller 连锁杀进程（设置/相机/桌面被杀）。
        val availMb = runCatching {
            File("/proc/meminfo").readLines()
                .firstOrNull { it.startsWith("MemAvailable") }
                ?.filter { it.isDigit() }?.toInt()?.div(1024)
                ?: 0
        }.getOrDefault(0)
        if (availMb in 1..1500) {
            Log.e(TAG, "内存不足拒绝启动 MoE：可用 ${availMb}MB < 1500MB")
            return false
        }
        return try {
            val pb = ProcessBuilder(
                bin.absolutePath,
                "-m", modelPath,
                "--session",
                "--moe-stream",
                "--cache-mb", cacheMb,
                "-c", ctxSize.toString(),
                "-t", threads.toString(),
                "--ubatch", ubatch.toString(),
                "--io-threads", ioThreads.toString(),
                "--progress",
            )
            // 库同目录（nativeLibraryDir），libomp 等运行库随包分发
            pb.environment()["LD_LIBRARY_PATH"] = libDir
            pb.redirectErrorStream(true)
            val p = pb.start()
            process = p
            stdin = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
            ready = false
            pumpThread = Thread({ pump(p) }, "bmoe-pump").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "bmoe-cli 已启动（session 模式，缓存=$cacheMb，ctx=$ctxSize）")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "bmoe-cli 启动失败：${t.message}", t)
            stop()
            false
        }
    }

    /** 等待就绪（模型加载完成）。 */
    fun awaitReady(timeoutMs: Long = 120_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ready) return true
            if (!isRunning()) return false
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return ready
    }

    /**
     * 发一次生成请求（session 常驻，复用 expert cache 与 KV 前缀）。
     *
     * @param clearKv true=清空 KV（独立提问）；false=延续会话（复用前缀，多轮更快）。
     */
    fun generate(
        prompt: String,
        nPredict: Int = 256,
        think: Boolean = false,
        clearKv: Boolean = true,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
    ): Boolean {
        if (!isReady()) {
            Log.w(TAG, "generate 被拒：session 未就绪")
            return false
        }
        return try {
            val id = ++currentId
            this.onDelta = onDelta
            this.onDone = onDone
            val req = JSONObject()
                .put("cmd", "generate")
                .put("prompt", prompt)
                .put("id", id)
                .put("n_predict", nPredict)
                .put("think", think)
                .put("clear_kv", clearKv)
            synchronized(this) {
                stdin?.apply {
                    write(req.toString())
                    newLine()
                    flush()
                } ?: return false
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "generate 写入失败：${t.message}", t)
            false
        }
    }

    fun stop() {
        runCatching {
            stdin?.apply {
                write("{\"cmd\":\"close\"}")
                newLine()
                flush()
                close()
            }
        }
        stdin = null
        runCatching { process?.destroy() }
        process = null
        ready = false
        onDelta = null
        onDone = null
        Log.i(TAG, "bmoe-cli 已停止")
    }

    /** 逐行消费 stdout 的 BMOE_* 协议。 */
    private fun pump(p: Process) {
        try {
            BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    handle(line)
                    line = reader.readLine()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pump 结束：${t.message}")
        }
    }

    private fun handle(line: String) {
        // 非协议行（llama.cpp 自身日志）只在调试时透传
        if (line.startsWith("BMOE_")) {
            Log.d(TAG, line.take(200))
        } else {
            return
        }
        try {
            when {
                line.startsWith("BMOE_READY") -> {
                    ready = true
                    Log.i(TAG, "session 就绪：${line.take(180)}")
                }
                line.startsWith("BMOE_PROGRESS") -> {
                    val json = JSONObject(line.substringAfter(' '))
                    json.optString("delta_text").takeIf { it.isNotEmpty() }?.let {
                        onDelta?.invoke(it)
                    }
                }
                line.startsWith("BMOE_DONE") -> {
                    val json = JSONObject(line.substringAfter(' '))
                    val text = json.optString("text")
                    onDone?.invoke(text)
                }
                line.startsWith("BMOE_ERROR") -> Log.e(TAG, line.take(200))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "协议解析失败：${t.message} | ${line.take(120)}")
        }
    }
}
