package cn.yangrq.weixuan.local

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    /** 当前子进程的监听端口与 slot 缓存文件名（用于跨重启复用系统提示 KV）。 */
    @Volatile
    private var activePort: Int = -1
    @Volatile
    private var activeSlotName: String? = null

    /**
     * **实际生效**的上下文窗口（token）。
     *
     * 为什么需要它：窗口由内存阶梯 `8192/6144/4096` 按可用内存现算（见 [LocalMemoryModel.plan]），
     * 未必等于 [LocalMemoryModel.DEFAULT_CTX_CAP]。而工具/历史的 token 预算必须按**真实窗口**分配
     * —— 否则阶梯落到 4096 时预算仍按 6144 算，请求就会撑爆上下文（2026-10-04 实测
     * `in=6122 / ctx=6144` → `run_failed` 就是这个类型的问题）。
     *
     * 0 表示尚未加载，读取方应回退到 [LocalMemoryModel.DEFAULT_CTX_CAP]。
     */
    @Volatile
    var activeContextSize: Int = 0
        private set

    /**
     * slot 快照的**定时保存**任务（方案 A，2026-10-04）。
     *
     * 为什么不能只在 `stop()` 里存：微玄的引擎跑在前台 Service 里保活，
     * 用户日常是「打开→用→切走/划掉」，`stop()` 根本不会被调用
     * —— 实测首跑 `slot restore` 永远返回 400（文件不存在），功能收益为零。
     *
     * 所以改成：READY 之后起一个后台任务，**轮询着存**——
     * save 的响应自带 `n_saved`（槽里有几个 token）与 `n_written`（写了多少字节），
     * 用它判断「槽里有没有货」即可，不需要额外的 `--slots` 端点。
     * 存到有货就收工（含 System Prompt 的前缀 KV 已经落盘），整个进程生命周期只存一次。
     */
    private val slotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var slotSaveJob: Job? = null

    /** 快照文件上限：超出就删掉（KV 实测 ≈147KB/token，8k 提示可达 ~1.2GB，得有个闸门）。 */
    private const val SLOT_MAX_BYTES = 1200L * 1024 * 1024

    /**
     * 加载状态机（2026-09-30 新增）。
     *
     * 存在的原因：Agent 侧过去用 [isRunning]（= 本进程是否持有子进程句柄）判断"要不要等
     * 本地模型冷启动"。一旦自动加载失败（旧门控要求 6099MB，启动时几乎不可能满足），它
     * 立刻为 false，Agent 就直接发请求 → 连接被拒 → 用户看到「模型请求重试」。
     * 改为上报显式状态后，Agent 可以"加载中就等它、失败时直接给出原因"，不再靠连接错误猜。
     */
    enum class LoadState { IDLE, LOADING, READY, FAILED }

    /**
     * 是否强制 `-nkvo`（--no-kv-offload，把 K/Q/V 与 KV cache 留在 CPU）。
     *
     * **默认 false** —— 2026-10-04 实测撤销：去掉它 decode **+55%**（7.05 → 10.94 t/s，
     * 同 prompt 3162 token），且带真实图片的多模态请求验证通过。详见 [buildCommand] 里
     * mmprojArgs 处的完整取证。
     *
     * 仅当某次构建变化让 ROPE abort 复发（症状＝模型加载失败）时，把它改回 true 兜底。
     */
    private const val FORCE_NO_KV_OFFLOAD = false

    @Volatile
    var loadState: LoadState = LoadState.IDLE
        private set

    /** 最近一次加载失败的可读原因（供 UI / Agent 直接展示，取代泛化的重试链）。 */
    @Volatile
    var lastFailureReason: String? = null
        private set

    private fun fail(reason: String): Boolean {
        loadState = LoadState.FAILED
        lastFailureReason = reason
        Log.e(TAG, "加载失败：$reason")
        return false
    }

    fun isRunning(): Boolean = process?.isAlive == true

    /**
     * 启动 llama-server。参数（全部来自官方 llama.cpp，2026-09-25 源码确认）：
     * - `-ctk/-ctv`：KV cache 类型。**默认 f16**（保守选择），仅当 f16 装不下时降 q8_0。
     *
     *   ⚠️ 关于「q8_0 KV 到底慢不慢」，历史上得到过**三个互相矛盾**的结论，根因是测量协议有缺陷：
     *   早两次测的是「1~2 个 token 的样本」（提示词让模型回 "OK" 就 EOS 了），等于在测首 token 延迟；
     *   而且**混淆变量是设备状态而不是 KV 类型** —— 2026-10-02 用「强制长输出 + cache_prompt:false
     *   + 同轮内配对」的正确协议测出：同一轮里 f16 与 q8_0 只差 <2%（16.45 vs 16.22；7.14 vs 7.25），
     *   而**跨轮次**的绝对速度在 ~16 t/s 与 ~7.2 t/s 之间摆动，差 2.3 倍。
     *   也就是说：**KV 类型对 decode 速度的影响在当前证据下测不出**，之前那个「1.7×/2.5×」的差值
     *   是设备状态被错误归因给了 KV 类型。
     *   唯一确定性结论：`-ctk/-ctv q4_0` 会 3/3 直接把进程打死，HTP 不支持，绝对不要用。
     *
     *   结论：保持 f16 默认（无速度代价的保守选择）；内存吃紧时降 q8_0 也**未必有速度损失**，
     *   可以放心用作兜底。若要做定论，需要先把「设备状态」这个变量控制住（比如固定大核策略、
     *   冷却后再测、同轮配对 + ≥5 轮取中位数）。
     *
     * - `-fa on`：flash attention（更快 prefill）
     * - `--mmap`（默认）：权重按需分页（14B 只驻留几百 MB —— 解决崩溃的核心）。
     *   注意 `--no-mmap`（PocketOrca 的 htp 口味参数）在 2.5GB 模型上**实测必崩**，不要照抄。
     * - `-c`：上下文窗口，由 [LocalMemoryModel] 按 GGUF 架构账本与可用内存决策
     * - `-ub`：**不显式指定**，用 llama.cpp 默认 512。2026-10-02 用 cache_prompt=false
     *   的干净方法学做过 ub 512/1024/2048 × 3 轮交替 A/B：decode 中位数 15.32 / 15.79 / 14.91，
     *   差异完全落在跑内方差内 —— PocketOrca 的 `-ub 1024` 在本机**没有收益**，不要抄。
     *
     * @param contextSize 上下文窗口上限（不是固定值；实际窗口取"装得下的最大档"）。
     *   默认为 [LocalMemoryModel.DEFAULT_CTX_CAP]，需要长会话时可调大。
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
            append("com.miui.wallpaper|com.android.providers|com.miui.contentcatcher|")
            // 太墟（TaiXu Harness）：本机的 AI 宿主 App。它跑在前台时会因为下面 L2 的
            // `am kill <第三方包>` 被一起杀掉——实测 2026-09-30：只要用户在微玄里加载
            // 本地模型，太墟进程就被回收，AI 会话随之中断（工具报"应用进程中断"、PRoot
            // 运行时失效）。它不是"可回收的缓存式负载"，必须保护。
            append("top.wkbin.taixu")
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

    /**
     * CPU 线程数（2026-09-30：由硬编码 4 改为按核心数自适应）。
     *
     * 本机 8 核 → 取 4，与旧值一致，所以这次改动**不会**直接改变本机行为；
     * 真正要调大必须靠在**冷却状态下**做 A/B 才能确认（当前设备 60~65°C、电池 48°C、
     * 充电中，任何测速都不可信）。
     */
    private fun threadCount(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(4, 8)

    fun start(
        context: Context,
        modelPath: String,
        port: Int,
        contextSize: Int = LocalMemoryModel.DEFAULT_CTX_CAP,
    ): Boolean {
        if (isRunning()) {
            loadState = LoadState.READY
            return true
        }
        // ── 后端口味解析（2026-10-02）──────────────────────────────────────────
        // 必须在最前面定下"这台机器这次到底用哪个计算单元"，后面的 argv / env 全部由它派生。
        val backend = LocalBackend.fromId(LocalSettings.localBackend)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        Log.i(TAG, "后端口味：${backend.id}（${backend.label}）→ --device ${backend.device}")
        loadState = LoadState.LOADING
        lastFailureReason = null
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
            return fail("llama-server 二进制不存在：${bin.absolutePath}")
        }
        // ── 兼容性前置门控（2026-10-02）────────────────────────────────────────
        // 抄自 PocketOrca 的兼容性体系：在**启进程之前**就判定「后端 × 量化格式 × 设备」，
        // 而不是等 llama-server 起来、甚至等 decode 慢十倍才发现不对。
        // UNSUPPORTED 直接拒绝并给出可读原因；DEGRADED 放行但记日志（UI 上也有提示）。
        val quant = ModelCompat.quantOf(File(modelPath))
        val verdict = ModelCompat.check(backend, quant)
        Log.i(TAG, "[compat] ${ModelCompat.badgeFull(verdict)}｜${quant?.describe() ?: "量化未知"}")
        if (!verdict.canRun) {
            return fail("后端「${backend.label}」与本机不兼容：${verdict.headline}。${verdict.detail}")
        }
        if (verdict.level == ModelCompat.Level.DEGRADED) {
            Log.w(TAG, "[compat] DEGRADED ${verdict.headline}｜${verdict.detail}")
        }
        // 内存估算：按文件全量（mmap 不减少推理总需求）。
        val modelFile = File(modelPath)
        val fileMb = (modelFile.length() / 1024 / 1024).toInt()
        val residentMb = fileMb

        // ── 内存账本（2026-09-30 重构）──────────────────────────────────────────
        // 旧版 kvMb = ctx × 0.6(f16)/0.3(q8_0) 是拍出来的常量，比真实 KV 高 4~19 倍，
        // 连锁造成两个后果：
        // ① 可用内存 <5GB 时永远选 q8_0 —— 而 HTP 上 q8_0 KV 的逐行反量化让 decode
        //    慢 2.5 倍（3.1k 上下文实测 4.4 vs 11.2 tok/s），4B 白付了这份速度；
        // ② 需求虚高后，任何 >2.4GB 的模型都被拒（catalog 里的 8B/9B 永远进不来），
        //    且旧门控要 6099MB 才肯加载 4B，直接导致启动时自动加载必然失败。
        // 现改为读 GGUF 元数据按架构算 KV：只统计真正带 KV 的层，递归层（线性注意力）
        // 另算固定 F32 状态（与 ctx 无关）。
        // 例：Qwen3-4B = 36 层 × 2 × 8 KV头 × 128 = 0.1406 MiB/token；
        //     MiMo-V2.6-9B = 8 层 × 2 × 4 KV头 × 256 = 0.0313 MiB/token（比 4B 还省 4.5 倍）。
        val footprint = LocalMemoryModel.probe(modelFile) ?: LocalMemoryModel.fallback()
        val kind = "dense(全量 ${fileMb}MB)"
        Log.i(TAG, "内存账本：$kind | ${LocalMemoryModel.describe(footprint)}")

        // 决策：优先 f16、尽量给大窗口；装不下则先温和整理再重试，仍装不下才拒绝
        val plan = LocalMemoryModel.plan(residentMb, footprint, availMb, contextSize) ?: run {
            val freed = releaseExpendableProcesses()
            val availAfter = readAvailMb()
            Log.i(TAG, "温和整理释放 ${freed}MB（${availMb}→${availAfter}MB），重新决策")
            LocalMemoryModel.plan(residentMb, footprint, availAfter, contextSize) ?: run {
                val need = LocalMemoryModel.minNeedMb(residentMb, footprint)
                return fail(
                    "内存不足：至少需要 ${need}MB（$kind，n_ctx=${LocalMemoryModel.MIN_CTX}），" +
                        "当前 ${availAfter}MB（已温和整理 +${freed}MB）",
                )
            }
        }
        val ctx = plan.ctx
        val kvType = plan.kvType
        activeContextSize = ctx
        Log.i(TAG, "内存预检通过：${plan.summary} [$kind]")
        // 投机解码（draft 0.6B，2026-09-27）：同词表小模型草拟 → 4B 并行验证，
        // decode 吞吐可提升 2-3x，且输出与目标模型一致（无损）。draft 与 target 同为
        // Qwen3 系列，词表相同（都是 151936）。
        val draftFile = File(modelPath).parentFile?.let { File(it, "Qwen3-0.6B-Q4_K_M.gguf") }
        // 实测（2026-09-27）：在 Hexagon NPU 后端上投机解码**无收益**——
        // decode 11.0 vs 12.0 tok/s（持平），prefill 107.8 vs 254 tok/s（腰斩，draft 抢占
        // NPU 算力），且多占 380MB 内存。故默认禁用；代码保留以备 CPU 后端场景启用。
        val speculativeArgs = emptyList<String>()

        val threads = threadCount()
        // Qwen3-VL 这类视觉模型的视觉塔走独立的 mtmd 图（不是主 GGUF 的一部分）。
        // 不传 --mmproj，模型会退化成纯文本；找不到就静默不传（纯文本模型照常）。
        val mmprojFile = modelFile.parentFile?.listFiles()
            ?.firstOrNull { f ->
            if (!f.name.startsWith("mmproj-") || !f.name.endsWith(".gguf", true) || f.length() <= 0) {
                return@firstOrNull false
            }
            // ★ 必须与【主模型】配对（2026-10-01 修正）：同一目录里可能并存多个模型的视觉塔，
            // 只按目录匹配会把别的模型的 mtmd 图挂上来 → 架构不匹配 → 加载直接失败
            // （实测表现：「运行失败」+ 4B 不再自动加载）。
            // 配对规则：双方都去掉「-<量化>.gguf」后缀后必须相等或互为前缀。
            fun stem(n: String) = n.replace(Regex("-(Q\\d[^.]*|F16|F32|BF16|IQ\\d[^.]*)\\.gguf$"), "")
                .removeSuffix(".gguf")
            val tower = stem(f.name.removePrefix("mmproj-"))
            val main = stem(modelFile.name)
            tower == main || main.startsWith(tower) || tower.startsWith(main)
        }
        val mmprojArgs = mmprojFile?.let {
            Log.i(TAG, "已附加视觉塔：${it.name}")
            // ★★ 关于 -nkvo：**2026-10-04 已实测撤销**（这是本轮最大的速度修复，+55%）★★
            //
            // 【当初为什么加】2026-10-01 判定：多模态模型的 mRoPE 会让 ggml 图出现独立的 ROPE
            // 算子，HTP 白名单没有它，而 llama.cpp 是「先把 KV 张量（cache_k_l0）预分配进 HTP0、
            // 之后才校验算子支持」→ 直接 ggml_abort / SIGABRT。当时的对策是 `-nkvo`
            // （= --no-kv-offload，cparams.offload_kqv=false）把 K/Q/V 与 KV cache 全留在 CPU。
            //
            // 【为什么现在撤销】2026-10-04 用 `scripts/bench/nkvo_test.sh` 实测（同一 prompt
            // 3162 token、ctx6144、f16、t4）：
            //   P1 mmproj + -nkvo      → decode **7.05** t/s，prefill 697
            //   P2 mmproj + 无 -nkvo   → decode **10.94** t/s（10.63/11.02/11.17），prefill 806
            //   P3 无 mmproj + 无 -nkvo → decode 10.78 t/s  ← 与 P2 持平
            //   ⇒ ① **去 -nkvo 快 +55%**；② **视觉塔本身不吃速度**（P2≈P3）。
            // 并且**带真实图片**的请求也验证通过（`imgtest.sh`）：
            //   请求 → `{"content":"蓝色背景上有一个白色的矩形。"}` 识别正确，无 abort。
            // 服务端日志显示真正跑不了的是 **CLIP 图里的少数算子**：
            //   `WARNING: the CLIP graph uses unsupported operators by the backend (HTP0)`
            //   —— 这些算子会**自动回退 CPU**，功能正常。
            // 也就是说：当初的 -nkvo 是**拿大炮打蚊子** —— 为规避视觉塔里几个算子，
            // 把**整个 KV/注意力**也推给了 CPU，而 KV 越长 CPU 越堵
            // （实测 decode 随 prompt 线性下降：每 1000 token −1.4 t/s，斜率主因就是这个）。
            //
            // 【风险与回退】若某次构建变化让 ROPE abort 复发，症状是**模型加载失败**
            // （会以「本地模型未就绪」明确报出，不再是无尽的「模型请求重试」）。
            // 回退方式：把下面 FORCE_NO_KV_OFFLOAD 改回 true 即可（单点开关）。
            if (FORCE_NO_KV_OFFLOAD) {
                Log.w(TAG, "已手动开启 NO_KV_OFFLOAD（-nkvo）：注意力将落回 CPU，decode 约降 45%")
                listOf("--mmproj", it.absolutePath, "-nkvo")
            } else {
                listOf("--mmproj", it.absolutePath)
            }
        } ?: emptyList()
        // 设备锁定参数（2026-10-02）：优先用设备探测回来的**真实**设备名，探测不到匹配设备时
        // 省略该参数（宁可退回默认枚举，也不要传非法设备名把进程直接打挂）。
        val deviceName = LocalBackend.resolveDevice(context, backend)
        val deviceArgs = deviceName?.let { listOf("--device", it) }.orEmpty()

        // ── 系统提示 KV 的跨重启复用（2026-10-02）──────────────────────────────
        // 背景：Agent 每轮带 ~8k token 的「系统提示 + 工具定义」，实测 prefill ≈1000 tok/s
        // → 冷启动光是重新 prefill 就要 ~8 秒。llama-server **在进程存活期间**本来就会
        // 复用同一 slot 的公共前缀 KV，所以真正缺的只有「跨进程重启」这一段：
        // 每次 App 重载模型/被 MIUI 杀掉后重启，都要白付一次 8 秒。
        //
        // 做法（**注意不要用 --prompt-cache**）：`--prompt-cache` 在 arg.cpp:1869 被
        // `.set_examples({LLAMA_EXAMPLE_COMPLETION})` 限死为 llama-cli 专用，server 侧
        // 根本没有消费它的代码（grep prompt_cache_file 零命中），传给 llama-server 会因
        // 「未知参数」直接退出。server 上正确的机制是 `--slot-save-path`（arg.cpp:3614
        // 明确 `.set_examples({LLAMA_EXAMPLE_SERVER})`）+ 路由
        // `POST /slots/:id_slot?action=save|restore|erase`（server-context.cpp:4791-4794）。
        //
        // 文件名绑定模型指纹：换模型后自动用另一个文件名，杜绝把 A 模型的 KV 恢复到 B 模型上。
        val slotDir = File(context.filesDir, "slotcache").apply { runCatching { mkdirs() } }
        val slotName = if (slotDir.isDirectory) {
            val fingerprint = "$modelPath|${runCatching { modelFile.length() }.getOrDefault(0L)}|" +
                runCatching { modelFile.lastModified() }.getOrDefault(0L)
            "slot_" + Integer.toHexString(fingerprint.hashCode()) + ".bin"
        } else {
            null
        }
        val slotArgs = if (slotName != null) listOf("--slot-save-path", slotDir.absolutePath) else emptyList()
        if (slotName == null) Log.w(TAG, "slot 缓存目录不可用，跳过前缀 KV 复用")
        if (slotName != null) pruneStaleSlots(slotDir, slotName)

        val cmd = listOf(
            bin.absolutePath,
            "-m", modelPath,
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "-c", ctx.toString(),
            "-t", threads.toString(),
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
            // ★ 后端锁定（2026-10-02 关键修复）────────────────────────────────────
            // 不传 --device 时，llama.cpp 的 default device selection（src/llama.cpp:184-280）
            // 会把注册表里**所有** GGML_BACKEND_DEVICE_TYPE_GPU 的设备都纳入 model->devices，
            // 并按默认 split_mode=LAYER 依据显存逐层切分。本机 nativeLibraryDir 里同时存在
            // libggml-vulkan-adreno.so（Vulkan0）与 libggml-hexagon-adapter.so（HTP0），
            // 两者都会被注册成 GPU 设备 → 权重被切给两个设备 → 每跨一次设备边界就多一次
            // 主机侧张量拷贝。显式 --device 把设备列表收敛成唯一一个，彻底消除隐性 hybrid。
            // CPU 口味传 "none"：llama.cpp 的特殊值，语义=不启用任何加速设备（纯 CPU）。
        ) + speculativeArgs + mmprojArgs + deviceArgs + slotArgs
        Log.i(TAG, "启动 llama-server（n_threads=$threads）：${cmd.joinToString(" ")}")
        return runCatching {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            // ── 环境变量：全部按口味派生（2026-10-02 重构）─────────────────────────
            // 交叉编译产物无 RPATH：显式指定依赖库搜索路径（nativeLibraryDir 内含全部依赖 .so）
            pb.environment()["LD_LIBRARY_PATH"] = nativeDir
            // HTP(NPU) 的 DSP 端库搜索路径（Qualcomm 标准变量——缺它 HTP session 打不开：
            // "HTP0 failed to open session ... libggml-htp-v81.so not found"）。
            // 2026-10-02：补上 vendor 侧标准目录兜底（PocketOrca-LLM 的策略），
            // 包内目录仍排第一，不改变「优先用包内 skel」的现有行为。
            pb.environment()["ADSP_LIBRARY_PATH"] = LocalBackend.adspLibraryPath(nativeDir)
            pb.environment()["DSP_LIBRARY_PATH"] = nativeDir
            // ggml 后端插件（2026-10-02 重构，关键）：把**本口味要用的加速插件**显式喂给
            // GGML_BACKEND_PATH。走的是 ggml_backend_load(path) 这条路，只要求插件导出
            // ggml_backend_init，绕开自动扫描对 ggml_backend_score 的门闸——
            // 实测 libggml-vulkan-adreno.so 正是因为缺 score 而被自动扫描静默丢弃，
            // 导致 GPU 一直躺在 APK 里却从未被加载。
            File(nativeDir, backend.dlPlugin).takeIf { it.exists() }?.let {
                pb.environment()["GGML_BACKEND_PATH"] = it.absolutePath
            } ?: Log.w(TAG, "后端插件缺失：${backend.dlPlugin}（口味 ${backend.id} 可能退化为 CPU）")
            // 加速后端插件：Hexagon 适配器桥接 GenieX 预编译的 Hexagon 后端。
            // 注：曾启用 GGML_HEXAGON_OPPOLL=1（忙轮询）。2026-09-27 A/B 实测发现它
            // 反而拖慢整体（decode 10.7 vs 13.8、prefill 65 vs 109）——忙轮询占用 CPU
            // 并干扰 NPU 调度。故不再启用。
            if (backend.requiresHexagonAdapter) {
                File(nativeDir, LocalBackend.HEXAGON_ADAPTER).takeIf { it.exists() }
                    ?.let { pb.environment()["WEIXUAN_HEXAGON_BACKEND"] = it.absolutePath }
                    ?: Log.w(TAG, "Hexagon 适配器插件缺失，NPU 口味可能退化为 CPU")
                // 权重 repack 并行化（2026-10-05）：预编译 HTP 后端的权重重打包是**单线程**的，
                // 2.3GB 权重实测要 ~5.4s，占首次加载的大头。libhex_repack_preload.so 通过
                // LD_PRELOAD 拦截公开 API ggml_backend_tensor_set（零写入预编译 .so），
                // 命中"HTP 权重整张量上传"时用多线程完成同一 tiled 布局转换。
                // 实测：首次加载 8.49s → 5.97s（−29.7%）；输出与基线逐字一致（temperature=0 对照）。
                // 回退：删掉该 .so 即自动失效。
                File(nativeDir, "libhex_repack_preload.so").takeIf { it.exists() }?.let {
                    pb.environment()["LD_PRELOAD"] = it.absolutePath
                    Log.i(TAG, "已启用并行 repack 预加载：${it.absolutePath}")
                }
                // 【已撤销 2026-10-05】曾在此注入 GGML_HEXAGON_OPPOLL=1 / OPQUEUE=64。
                // 撤销原因：初测（~20 token 上下文的空 KV 场景）显示 decode +8.9%，但在**真实长 prompt**
                // 下复测为负优化——同一 900-token prompt：OPPOLL=0 时 prefill 853 t/s / decode 12.81 t/s，
                // OPPOLL=1 时降到 759 t/s / 12.10 t/s（忙轮询占住 CPU 核，拖慢 host 侧给 DSP 备料）。
                // 结论：该开关收益依赖上下文长度、不可靠，不进入默认路径。若将来要重试，
                // 必须用**接近真实 Agent prompt（5k token 级）**做同机背靠背对照。
            }
            val p = pb.start()
            process = p
            // 最近输出环形缓冲（2026-10-02）：llama.cpp 的后端/设备类错误只打在 stdout，
            // 失败时把它带进 lastFailureReason，UI 与 Agent 才能看到真正的原因
            //（如 "invalid device: HTP0" / "no backends are loaded" / "unknown argument"）。
            val tail = ArrayDeque<String>()
            Thread {
                runCatching {
                    p.inputStream.bufferedReader().forEachLine {
                        Log.i(TAG, it)
                        synchronized(tail) {
                            if (tail.size >= 40) tail.removeFirst()
                            tail.addLast(it)
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
            // 等待端口就绪（GB 级模型的 mmap 加载 + HTP 上传需要时间；大模型放宽到 180 秒）
            val readyBudgetMs = if (fileMb > 3000) 180_000L else 90_000L
            val deadline = System.currentTimeMillis() + readyBudgetMs
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
                        runCatching { p.destroy() }
                        process = null
                        return@runCatching fail(
                            "加载后内存审计不通过：可用 ${afterMb}MB < " +
                                "${LocalMemoryModel.POST_LOAD_MIN_AVAIL_MB}MB，已自动卸载防卡死",
                        )
                    }
                    loadState = LoadState.READY
                    activePort = port
                    activeSlotName = slotName
                    // 跨重启恢复系统提示 KV（best-effort）：命中时省掉一段冷 prefill。
                    // 放在置 READY 之前，避免 Agent 立刻发请求把 slot 占住导致 restore 返回 busy。
                    if (slotName != null) slotApi(port, "restore", slotName)
                    lastFailureReason = null
                    // 起后台任务：等到槽里真有 KV 了就把快照落盘，供下次启动恢复（方案 A）
                    if (slotName != null) scheduleSlotSave(port, slotName, slotDir)
                    return@runCatching true
                }
                if (!p.isAlive) {
                    return@runCatching fail(
                        "llama-server 进程提前退出（口味 ${backend.id}，多为后端/设备初始化失败）：" +
                            tailText(tail),
                    )
                }
                Thread.sleep(500)
            }
            fail("llama-server 就绪超时（${readyBudgetMs / 1000} 秒），可能仍在加载或后端挂起" + tailText(tail))
        }.getOrElse {
            fail("llama-server 启动失败：${it.message}")
        }
    }

    fun stop() {
        // 先把当前 slot 的 KV 落盘，供下次启动恢复（best-effort）。
        // 注意这不是唯一时机——后台还有轮询保存任务（方案 A），因为日常使用根本不会走到 stop()。
        slotSaveJob?.cancel()
        slotSaveJob = null
        val name = activeSlotName
        if (name != null && process?.isAlive == true) slotApi(activePort, "save", name)
        runCatching { process?.destroy() }
        process = null
        activePort = -1
        activeSlotName = null
        loadState = LoadState.IDLE
        lastFailureReason = null
    }

    /** 把子进程最近的输出压成一行，供失败原因里携带（最多 6 行，避免刷爆 UI）。 */
    private fun tailText(tail: ArrayDeque<String>): String {
        val lines = synchronized(tail) { tail.toList() }
        if (lines.isEmpty()) return ""
        val picked = lines.takeLast(6).joinToString(" ｜ ") { it.trim().take(160) }
        return "｜最近输出：$picked"
    }

    /**
     * 调 server 的 slot 存取接口。
     *
     * **协议要点（全部源码取证，别按直觉写）**：
     * - `action` 走**查询串**：`POST /slots/0?action=save|restore|erase`
     *   （server-context.cpp:4791-4797 按 `req.get_param("action")` 分发）；
     * - **文件名必须在 JSON body 里**：`{"filename":"..."}`
     *   （server-context.cpp:5292-5293 `json::parse(req.body)` + `request_data.at("filename")`）；
     *   把 filename 放查询串或发空 body 会 **HTTP 500**（实测报
     *   "attempting to parse an empty input"）；
     * - 文件名要过 `fs_validate_filename(filename, allow_subdirs=false)`（common.cpp:825），
     *   必须非空、≤255 字符、合法 UTF-8、不含路径分隔符，所以"模型指纹"只取十六进制；
     * - 未用 `--slot-save-path` 启动时会回 `This server does not support slots action`。
     *
     * **一律 best-effort**：失败只记日志，绝不影响推理。
     * `restore` 失败是常态（首跑没有缓存文件、或上次是强杀没来得及保存），完全正常。
     * slot 正在处理请求时会返回 busy——所以 restore 要尽量在 READY 之前做完。
     */
    private fun slotApi(port: Int, action: String, filename: String): String? {
        if (port <= 0) return null
        return runCatching {
            val conn = (URL("http://127.0.0.1:$port/slots/0?action=$action").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                // save 要把整个 KV 落盘（可能上 GB），读超时给宽一点
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val body = "{\"filename\":\"$filename\"}".toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = runCatching {
                (if (code == 200) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText()
            }.getOrNull().orEmpty()
            conn.disconnect()
            Log.i(TAG, "slot $action($filename) → HTTP $code${if (code == 200) " ${text.take(160)}" else "：${text.take(160)}"}")
            if (code == 200) text else null
        }.getOrElse {
            Log.w(TAG, "slot $action 调用失败（忽略）：${it.message}")
            null
        }
    }

    /**
     * 后台轮询保存 slot 快照（方案 A）。整个进程生命周期只成功存一次。
     *
     * 判定「槽里有货」用的是 save 响应里的 `n_saved`，不依赖 `--slots` 端点
     * （那个端点需要额外 `--slots` 开关，server 会明确拒绝未开启的请求）。
     */
    private fun scheduleSlotSave(port: Int, slotName: String, slotDir: File) {
        slotSaveJob?.cancel()
        slotSaveJob = slotScope.launch {
            // 先等一会儿，让 Agent 有机会发出第一轮请求（首轮之后槽里才有 System Prompt 的 KV）
            delay(20_000)
            var attempt = 1
            while (attempt <= 20 && process?.isAlive == true && isRunning()) {
                val resp = slotApi(port, "save", slotName)
                if (resp != null) {
                    val nSaved = Regex("\"n_saved\"\\s*:\\s*(\\d+)").find(resp)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val nWritten = Regex("\"n_written\"\\s*:\\s*(\\d+)").find(resp)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    if (nSaved > 0 && nWritten > 0) {
                        if (nWritten > SLOT_MAX_BYTES) {
                            // 太大就不留：写都写了，但不该长期占盘
                            Log.w(TAG, "slot 快照 ${nWritten / 1048576}MB 超过上限 ${SLOT_MAX_BYTES / 1048576}MB，删除不留")
                            runCatching { File(slotDir, slotName).delete() }
                        } else {
                            Log.i(
                                TAG,
                                "slot 快照已保存：$nSaved tokens / ${nWritten / 1024}KB" +
                                    "（下次启动可省去这段前缀的 prefill），耗时 ${attempt} 次轮询",
                            )
                        }
                        return@launch
                    }
                    Log.i(TAG, "slot 还是空的（n_saved=$nSaved），第 $attempt 次轮询后再试")
                }
                attempt++
                delay(15_000)
            }
            Log.i(TAG, "slot 快照放弃：轮询 ${attempt - 1} 次仍未拿到非空槽（引擎已停或始终无请求）")
        }
    }

    /**
     * 删掉非当前模型的 slot 缓存，避免换模型后无限占盘。
     *
     * 注意量级：实测 KV ≈ **147 KB/token**（f16），所以 8k 的 Agent 系统提示对应
     * **~1.2GB** 的 slot 文件。这里是磁盘占用的唯一闸门，必须把目录里所有非当前文件都清掉
     * （不能只匹配 `slot_` 前缀，否则手写/试探留下的文件会一直躺着）。
     */
    private fun pruneStaleSlots(slotDir: File, keep: String) {
        runCatching {
            slotDir.listFiles { f -> f.isFile && f.name != keep }?.forEach { f ->
                Log.i(TAG, "清理过期 slot 缓存：${f.name}（${f.length() / 1048576}MB）")
                f.delete()
            }
        }
    }

    private fun probe(port: Int): Boolean = runCatching {        val conn = (URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection)
        conn.connectTimeout = 800
        conn.readTimeout = 800
        val code = conn.responseCode
        conn.disconnect()
        code == 200
    }.getOrDefault(false)
}
