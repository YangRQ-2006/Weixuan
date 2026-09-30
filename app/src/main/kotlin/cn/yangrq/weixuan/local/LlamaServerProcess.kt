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
     * - `-ctk/-ctv`：KV cache 类型。**默认 f16**（2026-09-30 修正：HTP 上 q8_0 KV 的
     *   逐行反量化让 decode 慢 2.5 倍，实测 3.1k 上下文 4.4 vs 11.2 tok/s），
     *   仅当 f16 装不下时降 q8_0。
     * - `-fa on`：flash attention（更快 prefill）
     * - `--mmap`（默认）：权重按需分页（14B 只驻留几百 MB —— 解决崩溃的核心）
     * - `-c`：上下文窗口，由 [LocalMemoryModel] 按 GGUF 架构账本与可用内存决策
     *
     * @param contextSize 上下文窗口上限（不是固定值；实际窗口取"装得下的最大档"）
     */
    /**
     * 加载前的分层温和内存整理（2026-09-27）。
     *
     * L1 —— 附属进程（零影响）：小程序运行时（:appbrand/:miniapp）、WebView/浏览器内核
     *       （:xweb/:sandbox）、可重建的后台服务进程（:service/:push/:remote/:daemon）。
     *       这些进程承载的都是"随时可重建"的缓存式负载。
     * L2 —— 无关 App 的后台进程（`am kill <pkg>`，Android 官方语义：只结束后台、绝不碰
     *       前台/可见进程；被结束的 App 下次使用时正常冷启动）。
     * L3 —— 清 page cache（兜底，无副作用）。
     *
     * **白名单保护**（绝不触碰）：微玄自身、系统关键进程（system_server/systemui/桌面/
     * 输入法/电话/蓝牙/安全中心）。实测：微信 :appbrand0/1 + :xweb 组合释放 ~768MB。
     */
    private fun releaseExpendableProcesses(): Int {
        val before = readAvailMb()
        val protected = buildString {
            append("android|com.android.systemui|com.miui.home|com.android.settings|")
            append("cn.yangrq.weixuan|com.android.phone|com.android.server.telecom|")
            append("com.android.bluetooth|com.miui.securitycenter|com.miui.powerkeeper|")
            append("com.android.inputmethod|com.baidu.input|com.sohu.inputmethod|")
            append("com.miui.wallpaper|com.android.providers|com.miui.contentcatcher")
        }
        runCatching {
            // L1：精确结束附属进程（不碰任何主体进程）
            val l1 = "for p in /proc/[0-9]*; do " +
                "n=\$(tr '\\0' '\\n' < \$p/cmdline 2>/dev/null | head -1); " +
                "case \"\$n\" in " +
                "*:appbrand*|*:miniapp*|*:xweb*|*:sandbox*|*:service*|*:push*|*:remote*|*:daemon*" +
                ") kill \${p#/proc/} 2>/dev/null;; esac; " +
                "done"
            ProcessBuilder("/system/bin/su", "-c", l1)
                .redirectErrorStream(true).start().waitFor()

            // L2：结束无关 App 的后台（am kill 只杀后台；白名单跳过）
            val l2 = "for pkg in \$(pm list packages -3 2>/dev/null | sed 's/package://'); do " +
                "case \"\$pkg\" in $protected) continue;; esac; " +
                "am kill \"\$pkg\" >/dev/null 2>&1; " +
                "done"
            ProcessBuilder("/system/bin/su", "-c", l2)
                .redirectErrorStream(true).start().waitFor()

            // L3：丢弃页缓存兜底
            ProcessBuilder("/system/bin/su", "-c", "sync; echo 1 > /proc/sys/vm/drop_caches")
                .redirectErrorStream(true).start().waitFor()
        }
        Thread.sleep(1000)
        val after = readAvailMb()
        Log.i(TAG, "分层内存整理：${before}MB → ${after}MB（+${after - before}MB）")
        return after - before
    }

    private fun readAvailMb(): Int = runCatching {
        File("/proc/meminfo").readLines()
            .firstOrNull { it.startsWith("MemAvailable") }
            ?.filter { it.isDigit() }?.toInt()?.div(1024)
            ?: 0
    }.getOrDefault(0)

    fun start(context: Context, modelPath: String, port: Int, contextSize: Int = 8192): Boolean {
        if (isRunning()) return true
        // ── 顺序修正（2026-09-28；2026-09-30 简化）──────────────────────────────
        // 原则不变：必须在「整理后的真实可用内存」上做决策，避免误选 q8_0——
        // HTP 上 q8_0 KV 的逐行反量化正是长上下文 decode 的主要瓶颈
        // （实测 3.1k 上下文：q8_0 4.4 tok/s vs f16 11.2 tok/s）。
        // 决策已改为按 GGUF 架构账本计算，这里只做 System.gc + 读取真实可用内存；
        //「温和整理」由下方 planner 在装不下时按需触发，不再无条件先杀一遍后台。
        System.gc()
        val availMb = readAvailMb()

        val bin = File(context.applicationInfo.nativeLibraryDir, "libllama-server.so")
        if (!bin.exists()) {
            Log.e(TAG, "llama-server 二进制不存在：${bin.absolutePath}")
            return false
        }
        // 内存估算：dense 按文件全量（mmap 不减少推理总需求）；MoE（文件名含 -A#B）按激活参数量。
        val modelFile = File(modelPath)
        val fileMb = (modelFile.length() / 1024 / 1024).toInt()
        val moeActivatedB = Regex("""-A(\d+)B""").find(modelFile.name)?.groupValues?.get(1)?.toIntOrNull()
        val residentMb = if (moeActivatedB != null) moeActivatedB * 600 else fileMb

        // ── 内存账本（2026-09-30 重构）──────────────────────────────────────────
        // 旧版 kvMb = ctx × 0.6(f16)/0.3(q8_0) 是拍出来的常量，比真实 KV 高 4~19 倍，
        // 连锁造成两个后果：
        // ① 可用内存 <5GB 时永远选 q8_0 —— 而 HTP 上 q8_0 KV 的逐行反量化让 decode
        //    慢 2.5 倍（3.1k 上下文实测 4.4 vs 11.2 tok/s），4B 白付了这份速度；
        // ② 需求虚高后，任何 >2.4GB 的模型都被拒（catalog 里的 8B/9B 永远进不来）。
        // 现改为读 GGUF 元数据按架构算 KV：只统计真正带 KV 的层，递归层（线性注意力）
        // 另算固定 F32 状态（与 ctx 无关）。
        // 例：Qwen3-4B = 36 层 × 2 × 8 KV头 × 128 = 0.1406 MiB/token；
        //     MiMo-V2.6-9B = 8 层 × 2 × 4 KV头 × 256 = 0.0313 MiB/token（比 4B 还省 4.5 倍）。
        val footprint = LocalMemoryModel.probe(modelFile) ?: LocalMemoryModel.fallback()
        val kind = if (moeActivatedB != null) "MoE(激活 ${moeActivatedB}B->${residentMb}MB)" else "dense(全量 ${fileMb}MB)"
        Log.i(TAG, "内存账本：$kind | ${LocalMemoryModel.describe(footprint)}")

        // 决策：优先 f16、尽量给大窗口；装不下则先温和整理再重试，仍装不下才拒绝
        val plan = LocalMemoryModel.plan(residentMb, footprint, availMb, contextSize) ?: run {
            val freed = releaseExpendableProcesses()
            val availAfter = readAvailMb()
            Log.i(TAG, "温和整理释放 ${freed}MB（${availMb}→${availAfter}MB），重新决策")
            LocalMemoryModel.plan(residentMb, footprint, availAfter, contextSize) ?: run {
                val need = LocalMemoryModel.minNeedMb(residentMb, footprint)
                Log.e(
                    TAG,
                    "内存不足拒绝加载：至少需要 ${need}MB（$kind，n_ctx=${LocalMemoryModel.MIN_CTX}），" +
                        "当前 ${availAfter}MB（已温和整理 +${freed}MB）",
                )
                return false
            }
        }
        val ctx = plan.ctx
        val kvType = plan.kvType
        Log.i(TAG, "内存预检通过：${plan.summary} [$kind]")
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
            // KV 类型由 LocalMemoryModel 按 GGUF 架构账本决定（优先 f16：HTP 上快 2.5 倍）
            "-ctk", kvType,
            "-ctv", kvType,
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
                // 注：曾启用 GGML_HEXAGON_OPPOLL=1（忙轮询）。2026-09-27 A/B 实测发现它
                // 反而拖慢整体（decode 10.7 vs 13.8、prefill 65 vs 109）——忙轮询占用 CPU
                // 并干扰 NPU 调度。故不再启用。
            }
            val p = pb.start()
            process = p
            Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { Log.i(TAG, it) } }
            }.apply { isDaemon = true }.start()
            // 等待端口就绪（GB 级模型的 mmap 加载 + HTP 上传需要时间；大模型放宽到 180 秒）
            val deadline = System.currentTimeMillis() + if (fileMb > 3000) 180_000L else 90_000L
            while (System.currentTimeMillis() < deadline) {
                if (probe(port)) {
                    // 加载后实测审计（2026-09-30 新增）：预检只是估算，这里用真实可用内存兜底，
                    // 余量不足立即卸载——这是防「加载成功但拖垮系统」这类事故的最后一道防线。
                    val afterMb = readAvailMb()
                    Log.i(
                        TAG,
                        "llama-server 就绪：http://127.0.0.1:$port/v1" +
                            "（加载后可用 ${afterMb}MB，Δ${plan.availMb - afterMb}MB，" +
                            "其中权重 ${plan.residentMb}MB + KV 估算 ${plan.kvMb}MB）",
                    )
                    if (afterMb < LocalMemoryModel.POST_LOAD_MIN_AVAIL_MB) {
                        Log.e(
                            TAG,
                            "加载后审计不通过：可用 ${afterMb}MB < " +
                                "${LocalMemoryModel.POST_LOAD_MIN_AVAIL_MB}MB，自动卸载防卡死",
                        )
                        runCatching { p.destroy() }
                        process = null
                        return@runCatching false
                    }
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
