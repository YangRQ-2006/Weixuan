package cn.yangrq.weixuan.local

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 自建 llama.cpp runtime 的「后端口味」(backend profile)。
 *
 * 设计参照 PocketOrca-LLM（github.com/PocketOrca/PocketOrca-LLM）的 `profiles.json`：
 * 一个口味 = 一个**显式锁定的设备** + 一个**显式加载的后端插件** + 一组环境变量策略。
 * 它解决两个必须显式处理、靠"默认行为"一定会踩坑的问题。
 *
 * ## 坑一：`ggml_backend_score` 门闸 —— 后端"在包里"≠"能被加载"
 *
 * llama.cpp 的后端插件是按**文件名变体**从可执行文件目录自动枚举的
 * （ggml-backend-reg.cpp:534-603，匹配 `libggml-<name>-*.so` 且要求 `.so` 后缀），
 * 并且要求插件导出 `ggml_backend_score`（同文件 :534-548）。
 * 本机实测（2026-10-02，`--list-devices`）：
 * ```
 * ggml_backend_load_best: failed to find ggml_backend_score in .../libggml-vulkan-adreno.so
 * load_backend: failed to find ggml_backend_init in .../libggml-opencl.so
 * Available devices:
 *   HTP0: Hexagon (0 MiB, 0 MiB free)
 * ```
 * 也就是说：**GPU 后端一直躺在 APK 里却从未被加载**——不是驱动问题，是加载路径问题。
 * `GGML_BACKEND_PATH` 走的是另一条路（`ggml_backend_load(path)`，只要求
 * `ggml_backend_init`），所以把插件路径显式喂给它就能挂上。实测同一台机器：
 * ```
 * GGML_BACKEND_PATH=<nativeDir>/libggml-vulkan-adreno.so
 * Available devices:
 *   HTP0: Hexagon (0 MiB, 0 MiB free)
 *   Vulkan0: Adreno (TM) 840 (19123 MiB, 19123 MiB free)
 * ```
 *
 * ## 坑二：一旦真的有两个加速设备在册，默认行为就是「跨设备切层」
 *
 * 没有 `--device` 时 llama.cpp 走 default device selection（src/llama.cpp:184-280）：
 * 把注册表里**所有** `GGML_BACKEND_DEVICE_TYPE_GPU` 的设备都纳入 `model->devices`，
 * 再按默认 `split_mode=LAYER` 依据各设备空闲显存**逐层切分**权重。
 * Vulkan 报 19123 MiB、HTP 报 0 MiB，于是权重会被大量切给 Vulkan，层边界来回搬 →
 * 跨设备张量拷贝把 NPU 的收益吃光（这正是历史上 hybrid 比纯 NPU 慢 2~3 倍的同款机制）。
 * 所以每个口味都必须显式传 `--device`，把设备收敛成唯一一个。
 *
 * ## 已实测结论（小米 SM8850 / Adreno 840 / HTP v79，2026-10-02）
 * - `HTP0` 可用（NPU 路线）；
 * - `Vulkan0`（Adreno 840）可用 —— 这是本机唯一可用的 GPU 路线；
 * - **OpenCL 不可用**：即使把厂商 ICD loader（与 `/vendor/lib64/libOpenCL.so` 逐字节相同）
 *   随包发布，`ggml_opencl: platform IDs not available.` —— 因为 `/vendor/lib64/libOpenCL_adreno.so`
 *   的依赖是用 `libvndksupport`（sphal 命名空间）加载的，exec 子进程拿不到 sphal。
 *   让 OpenCL 复活必须改走「App 进程内 JNI + manifest uses-native-library」（PocketOrca 的做法），
 *   属于后续工作，故本口味默认会被设备探测判定为不可用而不出现在 UI 上。
 *
 * @param device 传给 `--device` 的 ggml 设备名（大小写不敏感，`ggml_backend_dev_by_name` 用 striequals）。
 *   `none` 是 llama.cpp 的特殊值，表示不启用任何加速设备（纯 CPU，common/arg.cpp:1116-1135）。
 * @param devicePrefix 设备名前缀，用于在 `--list-devices` 探测输出里识别可用性与取回真实设备名。
 * @param dlPlugin 通过 `GGML_BACKEND_PATH` 显式加载的后端插件文件名（相对 nativeLibraryDir）。
 * @param requiresHexagonAdapter 是否需要挂载 Hexagon/HTP 适配器插件。
 */
enum class LocalBackend(
    val id: String,
    val label: String,
    val device: String,
    val devicePrefix: String,
    val dlPlugin: String,
    val requiresHexagonAdapter: Boolean,
    val detail: String,
) {
    /** NPU：Hexagon HTP。整图单设备、零跨设备拷贝，本项目主力口味。 */
    NPU_HTP(
        id = "htp",
        label = "NPU（Hexagon HTP）",
        device = "HTP0",
        devicePrefix = "HTP",
        dlPlugin = "libggml-cpu-arm64.so",
        requiresHexagonAdapter = true,
        detail = "单设备整图执行；支持 Q4_0 / Q4_K_M / Q8_0",
    ),

    /**
     * GPU：Adreno Vulkan。**本机唯一可用的 GPU 路线**（已实测 Vulkan0 可用）。
     *
     * ⚠️ **不要指望它更快**（2026-10-05 实测+推算定案）：decode 是**内存带宽受限**——
     * 每生成一个 token 都必须把全部权重读一遍，而 NPU 与 GPU 读的是**同一颗 LPDDR5X**，
     * 因此两者上限相同。定量证据：Spark-X2.5-4B（2.6GB）实测 17.4 t/s × 2.6GB ≈ **45GB/s**，
     * 已贴近 LPDDR5X 的有效带宽上限；同机含 GPU 的混合配置实测 **慢 2.7×**（8.8 → 3.3 t/s）。
     * 另外 NPU 的能效远高于 GPU，切 GPU 会直接加重发烫。
     * **用途仅限排查**：当怀疑 HTP 后端有问题（如算子不支持）时，用它做对照实验。
     * 另注：Adreno 上出现过输出精度问题，切到这个口味后必须实读输出校验正确性。
     */
    GPU_VULKAN(
        id = "vulkan",
        label = "GPU（Adreno Vulkan · 实验，不保证更快）",
        device = "Vulkan0",
        devicePrefix = "Vulkan",
        dlPlugin = "libggml-vulkan-adreno.so",
        requiresHexagonAdapter = false,
        detail = "实验性，仅供排查 HTP 问题。decode 受内存带宽限制，NPU 与 GPU 同一颗" +
            " LPDDR5X —— 实测已贴近带宽上限（17.4 t/s × 2.6GB ≈ 45GB/s），换后端无法突破；" +
            "含 GPU 的混合配置实测慢 2.7×。且 Adreno 有输出精度风险，切换后请核对输出。",
    ),

    /**
     * GPU：Adreno OpenCL。**当前 exec 子进程模式下不可用**（无 OpenCL platform）。
     * 保留定义是为了配合「App 进程内 JNI + uses-native-library」的后续改造；
     * 现在设备探测会把它隐藏掉，不会误导用户。
     */
    GPU_OPENCL(
        id = "opencl",
        label = "GPU（Adreno OpenCL）",
        device = "GPUOpenCL",
        devicePrefix = "GPUOpenCL",
        dlPlugin = "libggml-opencl-adreno.so",
        requiresHexagonAdapter = false,
        detail = "需要 sphal 命名空间，当前子进程模式取不到驱动（暂不可用）",
    ),

    /** CPU：兜底口味。 */
    CPU(
        id = "cpu",
        label = "CPU（兼容/省电）",
        device = "none",
        devicePrefix = "",
        dlPlugin = "libggml-cpu-arm64.so",
        requiresHexagonAdapter = false,
        detail = "强制纯 CPU，不参与任何加速设备枚举",
    );

    companion object {
        private const val TAG = "LocalBackend"

        /** 未知/空值时的默认口味。 */
        val DEFAULT = NPU_HTP

        fun fromId(id: String?): LocalBackend = entries.firstOrNull { it.id == id } ?: DEFAULT

        /** Hexagon 适配器插件（桥接 GenieX 预编译的 Hexagon 后端）。 */
        const val HEXAGON_ADAPTER = "libggml-hexagon-adapter.so"

        /** 探测结果缓存：key = 口味 id。 */
        private val probeCache = ConcurrentHashMap<String, Set<String>>()

        /**
         * DSP 端库搜索路径（PocketOrca-LLM 的 v1.2.4 策略）。
         *
         * Qualcomm 的标准变量语义是**分号分隔的搜索路径列表**；本项目原实现只给
         * nativeLibraryDir。补上 vendor 侧三个标准目录后，即使将来把包内 skel 移出 APK
         * 也能命回设备自带库；包内目录仍排第一，不改变现有行为。
         */
        fun adspLibraryPath(nativeDir: String): String =
            "$nativeDir;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/dsp/cdsp"

        /**
         * 为本口味组装与**真实启动完全同源**的环境变量。
         *
         * 探测与启动共用这一个函数，避免出现"探测说能用、启动却起不来"的错配。
         */
        private fun envFor(backend: LocalBackend, nativeDir: String): Map<String, String> {
            val env = linkedMapOf(
                "LD_LIBRARY_PATH" to nativeDir,
                "ADSP_LIBRARY_PATH" to adspLibraryPath(nativeDir),
                "DSP_LIBRARY_PATH" to nativeDir,
            )
            File(nativeDir, backend.dlPlugin).takeIf { it.exists() }?.let {
                env["GGML_BACKEND_PATH"] = it.absolutePath
            }
            File(nativeDir, HEXAGON_ADAPTER).takeIf { it.exists() }?.let {
                env["WEIXUAN_HEXAGON_BACKEND"] = it.absolutePath
            }
            return env
        }

        /**
         * 让 llama-server 自己报一遍本口味能看到的设备（`--list-devices`
         * → common_print_available_devices()，源码 common/arg.cpp:1138-1162）。
         *
         * 与 PocketOrca `profiles.json.requires` 的精神一致：不猜、不硬编码，直接问运行时。
         * 失败/超时一律返回**空集合**；调用方必须把空集合理解为「探测失败」而不是「没有设备」。
         */
        fun probeDevicesFor(context: Context, backend: LocalBackend): Set<String> {
            probeCache[backend.id]?.let { return it }
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val bin = File(nativeDir, "libllama-server.so")
            if (!bin.exists()) return emptySet()
            val names = try {
                val pb = ProcessBuilder(bin.absolutePath, "--list-devices").redirectErrorStream(true)
                envFor(backend, nativeDir).forEach { (k, v) -> pb.environment()[k] = v }
                val proc = pb.start()
                // 有界等待：最重的 libggml-vulkan-adreno.so 有 44MB，dlopen + Vulkan 实例初始化需要时间；
                // 但任何后端卡死都不能拖住调用方。
                val out = StringBuilder()
                val reader = Thread {
                    runCatching {
                        proc.inputStream.bufferedReader().use { br ->
                            br.forEachLine { synchronized(out) { out.appendLine(it) } }
                        }
                    }
                }
                reader.isDaemon = true
                reader.start()
                if (!proc.waitFor(25, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    Log.w(TAG, "设备探测超时（25s），已终止探测进程")
                }
                reader.join(2000)
                val text = synchronized(out) { out.toString() }
                text.lineSequence().mapNotNull { line ->
                    val trimmed = line.trim()
                    // 形如：  Vulkan0: Adreno (TM) 840 (19123 MiB, 19123 MiB free)
                    val idx = trimmed.indexOf(':')
                    if (idx <= 0 || !trimmed.contains("MiB")) return@mapNotNull null
                    val name = trimmed.substring(0, idx).trim()
                    if (name.isEmpty() || name.contains(' ')) null else name
                }.toSet()
            } catch (t: Throwable) {
                Log.w(TAG, "设备探测失败（${backend.id}）：${t.message}")
                emptySet()
            }
            Log.i(TAG, "设备探测[${backend.id}] → ${names.ifEmpty { setOf("(空)") }}")
            // 正负结果都缓存：探测要 dlopen 最重 44MB 的 Vulkan 插件，不能每次进页面重来。
            // 用户手动切换口味 / 换 APK 时由 invalidateProbe() 清空。
            probeCache[backend.id] = names
            return names
        }

        /** 本口味是否可用。[probeDevicesFor] 返回空集（探测失败）时一律放行，避免误伤。 */
        fun isAvailable(context: Context, backend: LocalBackend): Boolean {
            if (backend == CPU) return true
            val devices = probeDevicesFor(context, backend)
            if (devices.isEmpty()) return true
            return devices.any { it.startsWith(backend.devicePrefix) }
        }

        /**
         * 解析本口味实际要传给 `--device` 的值。
         *
         * 三档策略（从确定到不确定）：
         *  1. 探测成功且看到匹配设备 → 用探测回来的**真实**设备名（比硬编码更稳）；
         *  2. 探测成功但没有匹配设备 → 返回 null（不传 `--device`）并告警。用户可能通过
         *     UI 之外的方式选中了本机跑不起来的口味，此时宁可退回默认枚举，也不要传非法
         *     设备名把进程直接打挂；
         *  3. 探测失败（空集）→ 回退到约定名。
         *
         * CPU 口味恒定 "none"。
         */
        fun resolveDevice(context: Context, backend: LocalBackend): String? {
            if (backend == CPU) return backend.device
            val devices = probeDevicesFor(context, backend)
            if (devices.isEmpty()) return backend.device
            return devices.firstOrNull { it.startsWith(backend.devicePrefix) } ?: run {
                Log.w(TAG, "本机未发现 ${backend.devicePrefix} 设备（探测到 $devices），不传 --device")
                null
            }
        }

        /** 清探测缓存（切换口味 / 换 APK 后调用）。 */
        fun invalidateProbe() = probeCache.clear()
    }
}
