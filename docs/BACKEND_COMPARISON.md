# 后端对比：PocketOrca-LLM × 微玄（NPU / GPU）

> 2026-10-02 · 对照项目：[PocketOrca-LLM](https://github.com/PocketOrca/PocketOrca-LLM)（master @ 2026-09-25）
> 对象：微玄自建 llama.cpp runtime（`/workspace/Eta`，SM8850 / Adreno 840 / HTP v79 / Android 16）
> 本文所有结论都在本机实测取过证，取证命令附在每节末尾。

---

## 0. 一句话结论

PocketOrca 值得抄的不是它的 NPU 参数，而是它的**「引擎 = 显式设备 + 显式插件 + 显式环境变量」的工程纪律**。
微玄原来的自建 runtime 在这一点上是「靠默认行为碰运气」：GPU 后端在 APK 里躺了一个月、**从未被加载过**，
而一旦真的加载进来，默认的设备枚举又会把权重同时切给 Adreno 和 HTP。
本次改造把这套纪律落地成代码里的 `LocalBackend` 口味层。

---

## 1. 架构对比矩阵

| 维度 | PocketOrca-LLM | 微玄（改造前） | 微玄（本次改造后） |
|---|---|---|---|
| 运行时来源 | 自建 llama.cpp（`GGML_HEXAGON=ON` + OpenCL + CPU） | 自建 llama.cpp（`GGML_BACKEND_DL=ON`）+ GenieX 预编译 Hexagon 后端 | 同前 |
| 进程模型 | **双路**：NPU/CPU 走 exec 子进程；GPU(OpenCL) 走 **App 进程内 JNI** | 仅 exec 子进程 | 仅 exec 子进程（JNI 路未做，见 §5） |
| 引擎定义 | `assets/profiles.json`：一个引擎 = 一个二进制 + 一组 argv + 量化白名单 | 硬编码在 `LlamaServerProcess` 里，只有一条路 | `local/LocalBackend.kt`：枚举口味 = `--device` + `GGML_BACKEND_PATH` 插件 + env |
| 设备选择 | **每个口味都显式** `--device HTP0` | 无 `--device`，靠默认枚举 | 每个口味都显式 `--device`，且设备名由**探测**取回 |
| 后端插件加载 | 编进同一个 `libllamaserver.so`，无需插件机制 | 依赖 `ggml_backend_load_all()` 自动扫描 | 显式 `GGML_BACKEND_PATH` 指向本口味插件（绕开 score 门闸） |
| 可用性判定 | 量化白名单（`requires`）静态声明 | 无 | `--list-devices` **运行时探测**，不可用的口味不进 UI |
| DSP 搜索路径 | `nativeDir;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/dsp/cdsp` | 只有 `nativeDir` | 补齐 vendor 三目录（包内仍排第一） |
| vendor 闭包 | 随包发布 `libcdsprpc` 等一串 | 已随包发布 | 同前 |
| 非 NPU 引擎的 Hexagon 抑制 | `GGML_HEXAGON_ARCH` + `NDEV=0` | 无 | 用 `--device` 单设备收敛（见 §3.2，比 env 抑制更干净） |
| mmap | htp 口味 `--no-mmap` | mmap 开 | **保持 mmap 开**（实测 `--no-mmap` 直接进程死亡，见 §4.4） |
| 失败诊断 | 完整 stdout 转发到 UI | 只报「进程提前退出」 | 40 行环形缓冲，失败原因携带 llama.cpp 原生输出 |

---

## 2. 取证：GPU 后端「在包里」≠「被加载」

`ggml_backend_load_all()` 的后端插件自动扫描有两个硬条件（`ggml/src/ggml-backend-reg.cpp:534-603`）：

1. 文件名必须匹配 `libggml-<name>-*.so`（短横线变体 + `.so` 后缀）；
2. 插件必须导出 **`ggml_backend_score`**。

而芯片后端只实现了 `GGML_BACKEND_DL_IMPL(...)`（`ggml-vulkan.cpp:15870`、`ggml-opencl.cpp:12918`），
**没有 `GGML_BACKEND_DL_SCORE_IMPL`**，于是被静默丢弃。

真机取证（用已安装 APK 的 nativeLibraryDir 直接跑 `libllama-server.so --list-devices`）：

```
ggml_backend_load_best: failed to find ggml_backend_score in .../libggml-vulkan-adreno.so
load_backend: failed to find ggml_backend_init in .../libggml-opencl.so
Available devices:
  HTP0: Hexagon (0 MiB, 0 MiB free)
```

改走 `GGML_BACKEND_PATH`（内部是 `ggml_backend_load(path)`，**只要求 `ggml_backend_init`**）之后：

```
Available devices:
  HTP0: Hexagon (0 MiB, 0 MiB free)
  Vulkan0: Adreno (TM) 840 (19123 MiB, 19123 MiB free)
```

→ **结论：GPU 一直用不上，根因不是驱动、不是精度，而是加载路径。** 这是本次最有价值的一条。

```sh
ND=<installed apk>/lib/arm64
cd $ND && LD_LIBRARY_PATH=$ND \
  GGML_BACKEND_PATH=$ND/libggml-vulkan-adreno.so \
  WEIXUAN_HEXAGON_BACKEND=$ND/libggml-hexagon-adapter.so \
  ./libllama-server.so --list-devices
```

---

## 3. 取证：为什么「必须显式 `--device`」

### 3.1 默认设备枚举会跨设备切层

`src/llama.cpp:184-280`：不给 `--device` 时，**所有** `GGML_BACKEND_DEVICE_TYPE_GPU` 型的设备都会被
塞进 `model->devices`，再按默认 `split_mode = LLAMA_SPLIT_MODE_LAYER` 依据各设备空闲显存逐层切分。

Vulkan 报 19123 MiB、HTP 报 0 MiB ⇒ 权重会被大量切给 Vulkan，层边界来回搬 ⇒ 每跨一次设备边界多一次
主机侧张量拷贝。**这就是「hybrid 比纯 NPU 慢 2~3 倍」的机制性根因**——hybrid 不是三设备并行，是层在设备间搬家。

（`ggml_backend_score` 只在**同一 name 家族内**挑变体，不跨家族决定「优先用谁」；所以
「adapter 的 score=900 > CPU 的 100 ⇒ llama.cpp 会优先选 NPU」这个说法是错的，实际是「两个都注册」。）

### 3.2 用 `--device` 收敛，比用 env 抑制更干净

PocketOrca 对非 NPU 引擎用 `GGML_HEXAGON_ARCH` + `GGML_HEXAGON_NDEV=0` 来阻止 Hexagon 枚举。
但微玄的 Hexagon 来自 GenieX 预编译产物，其 `NDEV` 解析是 `n<1 → n=1`（`ggml-hexagon.cpp:7826-7900`），
`NDEV=0` 拿不到「零设备」的语义，照抄无效。

改用官方参数即可等价达成，且不依赖任何后端私有 env：

| 口味 | `--device` | 语义 |
|---|---|---|
| NPU | `HTP0` | 只留 Hexagon 设备 |
| GPU | `Vulkan0` / `GPUOpenCL` | 只留该 GPU 设备 |
| CPU | `none` | llama.cpp 特殊值：不启用任何加速设备（`common/arg.cpp:1116-1135`） |

---

## 4. 真机实测数据（4B Q4_K_M，SM8850）

### 4.1 设备可用性（App 进程内探测，最终验证）

```
I LocalBackend: 设备探测[htp]    → [HTP0]
I LocalBackend: 设备探测[vulkan] → [HTP0, Vulkan0]
I LocalBackend: 设备探测[opencl] → [HTP0]
```
→ UI 上最终只出现 `NPU（Hexagon HTP）` / `GPU（Adreno Vulkan）` / `CPU（兼容/省电）`，
OpenCL 因探测不到设备被自动隐藏，不会误导用户。

### 4.1b 生产链路端到端验证（最硬的一条）

改造后的 App 装机启动、自动加载模型，实际起来的子进程 argv 为：

```
libllama-server.so -m .../Qwen3VL-4B-Instruct-Q4_K_M.gguf --host 127.0.0.1 --port 18787
  -c 6144 -t 4 -ctk f16 -ctv f16 -fa on -ngl 99 --no-warmup --cache-reuse 256 -np 1
  --mmproj .../mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf -nkvo
  --device HTP0                     ← 本次新增，设备名由探测取回
```

且 `/health` = `{"status":"ok"}`，`1+1=?` 返回 `"2"`。
说明「探测 → 解析真实设备名 → 注入 argv → 单设备执行」这条链在生产路径上是通的。

### 4.2 NPU vs GPU 输出正确性

| `--device` | 输出 | prompt | decode |
|---|---|---|---|
| `HTP0` | `"5"` ✅ | 184.0 tok/s | 14.6 tok/s |
| `Vulkan0` | `"@@@@@@@@@@@@@@@@"` ❌ 乱码 | 4.3 tok/s | 11.9 tok/s |

→ **Vulkan(Adreno 840) 目前不可用于生产**：能跑起来、能分配 19GB，但输出是坏的，且 prefill 慢 40 倍。
这与微玄原先记录的「Adreno 840 精度问题」一致，现在有了可复现的证据。

### 4.3 OpenCL 在 exec 子进程下彻底不可用

把与 `/vendor/lib64/libOpenCL.so` **逐字节相同**（md5 `64ea263…`）的厂商 ICD loader 随包发布、
并放进 `LD_LIBRARY_PATH` 之后：

```
ggml_opencl: platform IDs not available.
```

原因：真正的驱动 `libOpenCL_adreno.so` 依赖 `libvndksupport`（sphal 命名空间）去加载它的下游库，
而 **exec 子进程没有 sphal 命名空间**。PocketOrca 正是因此才把 GPU 单独做成
「App 进程内 JNI + `AndroidManifest` 的 `uses-native-library libOpenCL.so`」——
只有 App 进程的 classloader 命名空间才允许通过 sphal 拿到厂商驱动。

### 4.4 NPU 参数 A/B

| 变体 | prompt | decode |
|---|---|---|
| `-t 4`（基线，ub 默认 512） | 137.6 tok/s | 13.5 tok/s |
| `-t 4 -ub 1024` | 140.7 tok/s | **14.5 tok/s** |
| `-t 8` | 134.9 tok/s | 13.0 tok/s |
| `-t 2` | 129.8 tok/s | 12.8 tok/s |
| `-t 8 -ub 1024` | 136.8 tok/s | 14.0 tok/s |
| `--no-mmap`（PocketOrca htp 口味） | **进程直接死亡** | — |

> ⚠️ **本表已被 2026-10-02 的修正协议推翻，保留仅作反面教材。**
> 当时 `cache_n=254 / prompt_n≈20`，prefill 只建立在 20 个新 token 上；更严重的是
> 生成任务被模型以 EOS 截断，`predicted_per_second` 实际是 **1~2 个 token 的样本**（= 首 token 延迟）。
>
> 用「强制长输出 + `cache_prompt:false` + 同轮配对」重测后：
> - **`-ub` 512 / 1024 / 2048 无差异**（decode 中位数 15.32 / 15.79 / 14.91，全在跑内方差内）
>   —— 「+7%」是协议缺陷造出来的；
> - 真实全量 prefill 是 **≈1000 tok/s**（旧协议因 KV 前缀复用报成 155 t/s，差 6.4 倍）。
>
> **仍然成立的一条**：PocketOrca 的 `--no-mmap` 在这块设备 + 2.5GB 模型上会直接把子进程打死，**不可照抄**。
> 方法学与全部更正见 `scripts/bench/README.md`。

---

## 4.5 NPU 侧专项对比：PocketOrca 的 NPU 到底强不强

### 4.5.1 公开数字不可比

PocketOrca 的性能表**全部取在 Galaxy S25（SM8750 / HTP v75）**上：1B Q4_0 ≈ 23~30 t/s、
Qwen2.5-7B-Q4_0 ≈ 10 t/s。而它的兼容性矩阵里 **SM8850（HTP v79）= ⚠️ Untested**——
微玄这台机器的芯片，人家根本没有数据。所以「他比我的快吗」在公开资料上既不能成立也不能否证。

### 4.5.2 同芯片直接换件实测（本次做的实验）

同一台机器、同一个 host 后端（GenieX `libggml-hexagon-htp.so`）、同一个模型
（Qwen3VL-4B-Q4_K_M）、同一组参数（`--device HTP0 -ngl 99 -fa on -t 4 -ctk/-ctv f16`），
**只替换 DSP 侧 skel 搜索路径**（`ADSP_LIBRARY_PATH`）：

| 轮次 | 微玄（GenieX）skel decode | PocketOrca `htp-libs-16k` skel decode |
|---|---|---|
| 实验1 | 15.56 t/s | 17.78 t/s |
| G1 / P1 | 17.83 | 18.25 |
| G2 / P2 | 18.69 | 14.08 |
| G3 / P3 | 17.67 | 16.90 |
| **均值（4 次）** | **≈17.44 t/s** | **≈16.75 t/s** |

→ **两套 skel 的差异落在跑间方差（±10%，含热漂移）之内，没有可复现的优势。**
第一轮看到的「+14%」是噪声。skel 可以直接互换使用，说明两者同源。

### 4.5.3 一个反直觉发现：微玄的后端量化覆盖更宽

用 `--device none`（纯 CPU）跑同一模型做对照：

| 后端 | prompt | decode |
|---|---|---|
| `HTP0`（NPU） | 155.9 t/s | 15.6 t/s |
| `none`（纯 CPU，-t 4） | 8.5 t/s | 4.2 t/s |

**NPU 相对 CPU 是 3.7× decode / 18× prefill** ⇒ Q4_K_M 确实跑在 NPU 上，
而不是「回落 CPU」。

对照 PocketOrca README 原话：
> Upstream llama.cpp support for Hexagon is still early days: the NPU path has native kernels
> only for plain 4-bit formats like Q4_0, while popular formats like **Q4_K_M fall back to CPU**.

而微玄所用的 GenieX ggml-hexagon 源码里，Q4_K / Q6_K 明确在原生支持集合内
（`ggml-hexagon.cpp:267-270`，并有 `htp_mm_q8_1_tiled_row_size` 的 Q4_K 专用 tile 路径）：

```cpp
return type == GGML_TYPE_Q4_0 || type == GGML_TYPE_Q4_1 ||
       type == GGML_TYPE_Q8_0 || type == GGML_TYPE_IQ4_NL ||
       type == GGML_TYPE_MXFP4 || type == GGML_TYPE_Q6_K ||
       type == GGML_TYPE_Q4_K;
```

**⇒ 微玄的 NPU 侧（GenieX 预编译后端）在量化格式覆盖上比 PocketOrca 那套上游构建更新一档。**
PocketOrca 的 NPU 最大验证模型只到 Qwen2.5-7B-Q4_0；微玄现在 4B Q4_K_M 就能全量上 NPU。

### 4.5.4 结论

- **NPU 内核层面：他不比你快。** 同一套上游 ggml-hexagon，你这份还新一些。
- ~~值得白拿的只有一条：`-ub 1024`~~ → **修正：`-ub` 无可测收益**（见 §4.4 的更正说明）；
  PocketOrca 的参数里在本机**一条都不能白拿**。
- PocketOrca 真正领先的不是 NPU 算力，而是**工程完整度**——三引擎可切换 + 前台服务保活 +
  OpenAI 端点 + 兼容性矩阵文档化。这些跟"快"无关，但值得单独抄。
- **要更快，方向不在换 skel**，而在：① 落 `-ub 1024`；② 用 `GGML_HEXAGON_VERBOSE` 把单次推理里
  仍在 CPU 上跑的算子分布打出来，逐个推给 HTP（这才是 decode 的真实瓶颈：算子白名单）；
  ③ 在新参数组合下复测 KV 量化（旧的「q8_0 慢 2.5×」结论是在 ub512 下得出的）。

### 4.5.5 复现脚本

```sh
# 换 skel 做 A/B：只需改 ADSP_LIBRARY_PATH 的第一段
ADSP_LIBRARY_PATH=<po_skels>:$ND   → PocketOrca 的 16k skel
ADSP_LIBRARY_PATH=$ND              → 微玄（GenieX）的 skel
```
脚本：`/workspace/NPULlmChat/dist/wx_skel_ab.sh`、`/workspace/NPULlmChat/dist/wx_skel_rep.sh`

---

## 5. 不能直接迁移的部分（诚实清单）

| PocketOrca 的做法 | 微玄能否照抄 | 原因 |
|---|---|---|
| `--device HTP0` 显式锁设备 | ✅ 已落地 | 官方参数，通用 |
| 显式加载芯片后端插件 | ✅ 已落地（`GGML_BACKEND_PATH`） | 绕开 `ggml_backend_score` 门闸 |
| `-ub 1024` | ✅ 已实测可采信 | 收益约 +2%/+7% |
| `ADSP_LIBRARY_PATH` 加 vendor 兜底 | ✅ 已落地 | 包内目录仍排第一，行为不变 |
| `GGML_HEXAGON_NDEV=0` 抑制 Hexagon | ❌ | 微玄用的 GenieX 预编译后端把 `n<1` 归一到 `n=1`，该 env 无此语义；已用 `--device` 替代 |
| `--no-mmap` | ❌ | 本机实测进程死亡 |
| GPU 走 OpenCL（exec） | ❌ | 无 sphal 命名空间，`platform IDs not available` |
| GPU 走 OpenCL（App 进程内 JNI） | ⏳ 未做 | 需要新增 JNI shim + `uses-native-library` + 独立进程托管，属后续工作 |
| 手动 `aapt2 + d8` 免 Gradle 打包 | ❌ 不需要 | 微玄已有可用的 Gradle 流水线 |

---

## 6. 本次落地清单

新增/修改（`:app:compileDebugKotlin` / `:app:assembleDebug` 均通过，已装真机验证）：

| 文件 | 变更 |
|---|---|
| `local/LocalBackend.kt`（新增） | 后端口味枚举；每口味 = `--device` + 插件 + env；`--list-devices` 运行时探测 + 进程内缓存；`resolveDevice()` 三档回退（真实设备名 → 省略 → 约定名）；`adspLibraryPath()` |
| `local/LocalSettings.kt` | 新增 `local_backend` 持久化项 |
| `local/LlamaServerProcess.kt` | 启动时解析口味；追加 `--device`；env 按口味派生；新增输出环形缓冲，失败原因携带 llama.cpp 原生日志 |
| `ui/pages/local/LocalModelScreen.kt` | 新增「推理后端（自建引擎）」下拉选择器；并发探测 + 自动回落 |
| `EtaApp.kt` | 进程启动时预热后端探测（避免页面首次组合被回收导致只探到一半） |

**行为变化**：默认口味仍为 NPU（`--device HTP0`），对现有用户零影响；
新增 `GPU（Adreno Vulkan）` 入口，但**默认不切换**，且口味说明里已写明需核对输出正确性。

---

## 7. 后续建议（按性价比排序）

1. **确认 `-ub 1024` 是否设为 NPU 默认值**（再跑 2~3 轮取中位数，确认收益稳定）。
2. **GPU 真正可用**：照 PocketOrca 做「App 进程内 JNI + `uses-native-library libOpenCL.so`」，
   并建议跑在 `android:process=":infer"` 的独立进程里（既是 App 进程、有 sphal，又能与 UI 进程做崩溃隔离）。
   这是 Adreno 上拿到 GPU 的唯一现实路径。
3. **Vulkan 精度问题**：可对比上游 llama.cpp 更新版本是否修好了 Adreno 840 的乱码；若能修好，
   Vulkan 是比 OpenCL 省事得多的 GPU 路线（不需要 sphal）。
4. 量化白名单（PocketOrca 的 `requires`）可以补进 `LocalBackend`，在 UI 上提前拦住不支持的量化。

---

## 附：复现脚本

- `/workspace/NPULlmChat/dist/wx_backend_test.sh` —— 指定 `--device` 跑一次最小对话 + 回收日志
- `/workspace/NPULlmChat/dist/wx_npu_ab2.sh` —— HTP 参数 A/B（nonce prompt 防 KV 前缀复用）
