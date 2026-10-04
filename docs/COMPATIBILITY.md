# 微玄 · 设备兼容性矩阵

> 数据来源：**本机实测**。没测过的一律标 ⚠️ 未测，不做外推。
> **最后更新：2026-10-04** —— 本次订正了 4 处已被实测推翻的旧结论，并新增「root 与权限」章节。
> 逐条订正记录见文末[修订记录](#修订记录)。

---

## 结论速查

| 你的机器 | 推荐后端 | 说明 |
|---|---|---|
| 骁龙 8 系（含 Hexagon NPU，HTP v73+） | **NPU（Hexagon HTP）** | 最快；本机 v79 实测通过，其它代际未测 |
| 其它 arm64 手机（天玑 / 麒麟 / 老骁龙） | **CPU** | 能跑，约 1/3 速度 |
| Adreno GPU（Vulkan） | ⚠️ **不要用** | 实测输出乱码（详见下文） |
| 任何机器 | 需要 **Android 14+ / arm64** | minSdk 34 |

**比芯片更关键的是可用内存** —— 见[内存建议](#六内存建议)。

---

## 一、root 与权限：无 root 的手机能用吗？

**能用。** 无 root 是本项目的设计目标之一，不是"凑合跑"。代码里有多处显式的降级路径。

### 1.1 不需要 root 的部分

| 能力 | 说明 |
|---|---|
| **本地模型推理** | 应用自带 DSP 库（`libcdsprpc.so` / `libqti_dsp_v1*.so`），`ADSP_LIBRARY_PATH` 指向自己的 `nativeLibraryDir` |
| **对话 / 记忆 / 角色 / 技能** | 纯应用层 |
| **浏览器 / 文件工具** | 应用私有工作区 |
| **屏幕观察与点按** | 走**无障碍服务**（普通权限，需手动开启） |
| **终端 + Linux 环境** | 走 **PRoot**（用户态方案，不需要 root）—— 代码里显式分流：有 root 用 `chroot`，无 root 用 `PRoot` |
| **模型下载与管理** | 无 root 时模型落在外部私有目录，代码注释原文：「静默退回外部目录，功能不受影响，只是慢」 |

### 1.2 需要 root 的部分（无 root 时**自动摘掉**，不会报错）

工具按 `AgentToolRequirements.RootRequirement` 分三级，未满足门槛的工具**不会被下发给模型**：

| 级别 | 数量 | 代表工具 |
|---|---|---|
| `NONE` | 约 45 | `get_current_context` / `launch_app` / `browser_use` / `observe_screen` / `tap` `tap_element` / `input_text` / `wait` `wait_for_text` / `set_alarm` / `memory_*` / `skills_*` |
| `PARTIAL` | 11 | `press_key` / `terminal` / `run_command` / `read_file` / `write_file` / `list_directory` / `read_image` / `network_info` / `get_setting` |
| **`REQUIRED`** | 约 27 | 联系人 / 短信 / 通话记录 / 相册 / 文件搜索 / 日历 / 剪贴板历史 / 微信与 QQ 图片、`logcat`、改系统设置、冻结应用、`top_memory_apps` `top_storage_apps` |

### 1.3 LSPosed / Xposed 是**可选增强**

代码里是 `compileOnly(libs.libxposed.api)` —— 这个语义就是「框架不在也能编译运行」，
只是缺少**系统助手接管 / 小爱同学 hook**能力。**不装 LSPosed 不影响正常使用。**

### 1.4 ⚠️ 一条未验证项（必须先说清）

> **NPU 路径从未在未 root 的设备上验证过**（开发机的测试设备是 KernelSU root）。
> 理论上不需要 root（应用自带全部 FastRPC/DSP 库），但 FastRPC 的 **unsigned PD** 在零售机上的
> 可用性依赖厂商与 DSP 固件配置。**欢迎未 root 用户跑通后回报，我们把它补进这张表。**

**另一条实际影响**：无 root 时应用无法执行「分层内存整理」（L1 结束附属进程 / L2 `am kill` 后台 /
L3 丢页缓存 —— 这三步全部依赖 `su`，且整体包在 `runCatching` 里，失败静默）。
后果是**大模型加载更容易被内存预检拒绝**，用户可手动清理后台绕过。

---

## 二、推理后端矩阵

### 2.1 CPU —— 理论上是 2023 年后的任意 arm64 Android 手机

ARMv8+ 的骁龙 / 天玑 / 其它都能跑。老芯片（A53/A55 小核）能跑但很慢。
**本机实测（SM8850，4B Q4_K_M，`-t 4`）：decode ≈4.2 t/s。**（短 prompt，长 prompt 会更低）

### 2.2 GPU（Vulkan / OpenCL）—— ⚠️ 当前不要用

| SoC | GPU | 后端 | 状态 |
|---|---|---|---|
| Snapdragon 8 Elite Gen 5 | Adreno 840 | Vulkan | ❌ **输出乱码**：`1+1=?` 返回 `@@@@@@@@@@@@@@@@` |
| Snapdragon 8 Elite Gen 5 | Adreno 840 | OpenCL | ❌ 取不到驱动（`platform IDs not available`） |
| 其它 Adreno | — | OpenCL | ⚠️ 未测 |
| MediaTek（Mali） | — | 两者 | ❌ 不支持，请用 CPU |

- Vulkan 后端实测 prefill 4.3 t/s —— 相对 NPU 慢数十倍，**精度问题修复前不要用**。
- OpenCL 不可用的根因：真正的驱动 `libOpenCL_adreno.so` 依赖 `libvndksupport`（sphal 命名空间）加载下游库，
  而 exec 子进程没有 sphal 命名空间。复活它需改走 App 进程内 JNI + manifest `uses-native-library`。
- 注：Vulkan 后端插件**必须**用 `GGML_BACKEND_PATH` 显式加载，它不导出 `ggml_backend_score`，进不了自动扫描。

### 2.3 NPU（Hexagon HTP）—— 推荐路径

**APK 内打包的 HTP 后端只有四代**（`jniLibs/arm64-v8a/`）：

```
libggml-htp-v73.so   libggml-htp-v75.so   libggml-htp-v79.so   libggml-htp-v81.so
```

| HTP arch | 常见对应代际 | 状态 |
|---|---|---|
| **v79** | 骁龙 8 Elite 一代（本机 SM8850 / Adreno 840） | ✅ **实测通过** |
| v81 | 更新一代 | 📦 已打包，⚠️ 未测 |
| v75 | 骁龙 8 Gen 3 一代 | 📦 已打包，⚠️ 未测 |
| v73 | 骁龙 8 Gen 2 一代 | 📦 已打包，⚠️ 未测 |
| **无对应库**（HTP v69 及更早、骁龙 7 系、非骁龙） | Snapdragon 888 / 8 Gen 1 及更早 | ❌ **只能走 CPU** |

> ⚠️ 上表的「arch ↔ 代际」是**常见对应关系，不是确证结论** —— 机型繁多，
> 后端由运行时的 Hexagon 适配层按设备实际 arch 自动选择。
> **建议以 `logcat` 里实际加载的 `libggml-htp-v*.so` 为准**；跑通后欢迎回报校正这张表。

**实测数据（本机 v79）**

| 场景 | prefill | decode |
|---|---|---|
| 短 prompt（约 400 token） | ≈1000 t/s | **≈10 t/s** |
| Agent 真实 prompt（约 3200 token） | ≈600–800 t/s | **≈6.5 t/s** |

> **decode 强烈依赖上下文长度**，实测约 **每 1000 token 的上下文慢 1.4 t/s**，
> 所以引用 tok/s 时**必须同时给出 prompt 长度**，否则数字没有可比性。

---

## 三、量化格式白名单（NPU 引擎）

HTP 只对下列格式有**原生 tiled 布局**（权重被重排成 DSP 侧 tile，**没有逐行反量化**）；
其余格式会退化成慢路径。依据是后端源码里的
`ggml_hexagon_is_repack_type()` + `ggml_hexagon_is_hmx_weight_type()`
（`ggml/src/ggml-hexagon/ggml-hexagon.cpp:266-278`）：

```
Q4_0  Q4_1  Q8_0  IQ4_NL  MXFP4  Q6_K  Q4_K  F16  F32
```

⚠️ **注意与 PocketOrca 的差异**：PocketOrca 用的上游构建白名单里**没有 Q4_K / Q6_K**
（其 README 明确写「Q4_K_M falls back to CPU」）。微玄所用的 GenieX 预编译后端**多了这两个**
（源码里另有 Q4_K 专用 tile 尺寸 `HTP_MM_WEIGHT_TILE_SIZE_Q4_1`）。
微玄因此能直接吃社区最常见的 **Q4_K_M** —— 这也是市场里 15 个模型全部选 Q4_K_M 的原因。

**不在白名单里的格式（Q5_0 / Q5_1 / Q2_K / Q3_K / Q5_K / IQ1–IQ3 / TQ 等）**
在 UI 上会被标成 ⚠️ 降级并建议换格式，但不会硬拦。

### 判定是怎么做的

1. 读 GGUF 头部：走完元数据段拿 `general.file_type`，再读**张量信息表**，
   按**元素数加权**统计 ggml 类型分布，取占比最高的作为 `dominant`
   （比文件名可靠 —— 文件名可能写 `-Q4_K_M` 而实际混了 Q6_K/F32）。
   实现见 `local/LocalMemoryModel.kt` 的 `probeQuant()`。
2. 读不到头部（分片 / 非标准文件）时退回**文件名正则**。
3. 与白名单比对 + 叠加设备侧硬伤，得出三档结论：

| 档位 | 含义 | 行为 |
|---|---|---|
| ✅ OK | 格式在原生白名单内 | 正常启动 |
| ⚠️ DEGRADED | 能跑但明显变慢 / 有已知风险 | 放行 + UI 提示 + 日志告警 |
| ⛔ UNSUPPORTED | 本机确定跑不了 | **启动前直接拒绝**，给出可读原因 |

实现见 `local/ModelCompat.kt`。

---

## 四、已知限制

### 4.1 KV cache 类型：**不要用 q8_0**（重要订正）

| 配置（ctx 6144、f16 权重、prompt 2997 token） | decode | prefill |
|---|---|---|
| **`-ctk/-ctv f16`** | **6.49 t/s** | 638 |
| `-ctk/-ctv q8_0` | **1.93 t/s**（**慢 3.4×**） | 520 |
| `-ctk/-ctv q4_0` | ❌ 3/3 进程必崩 | — |

**为什么 q8_0 这么慢**：微玄的 KV 留在 CPU 侧参与注意力计算，
q8_0 每次注意力都要**逐行反量化**，代价随 KV 长度增长 → 上下文越长越吃亏。

> **旧结论错在哪**：此前用**短 prompt**（345~2000 token）测，KV 短、反量化代价小，
> 于是得出「f16 / q8_0 差 <2%」并写进了本文档 —— 那是**测不出差异**，不是**没有差异**。
> 2026-10-04 用 2997-token prompt + 组内配对复测（脚本 `scripts/bench/kv_ctx_ab.sh`，
> 重复性 ±1%）才定位到真相。**默认保持 f16。**

### 4.2 多模态**不需要** `-nkvo`（重要订正）

`--mmproj` 与 `-nkvo` **可以同时去掉**：

| 配置 | decode | prefill |
|---|---|---|
| mmproj + `-nkvo` | 7.05 t/s | 697 |
| **mmproj + 无 `-nkvo`** | **10.94 t/s**（**+55%**） | 806 |
| 无 mmproj + 无 `-nkvo` | 10.78 t/s | 786 |

- **带真实图片的请求也验证通过**（返回「蓝色背景上有一个白色的矩形」识别正确，无 abort）。
- 服务端日志显示真正跑不了的是 **CLIP 图里的少数算子**：
  `WARNING: the CLIP graph uses unsupported operators by the backend (HTP0)` ——
  这些算子会**自动回退 CPU**，功能正常。
- ⇒ 早先「必须 `-nkvo`」的结论是**过度治疗**：为规避视觉塔里几个算子，把**整个 KV/注意力**
  也推给了 CPU，白付 55% 速度。现已由单点开关 `FORCE_NO_KV_OFFLOAD = false` 控制。
- 复现脚本：`scripts/bench/nkvo_test.sh`、`scripts/bench/imgtest.sh`。

### 4.3 其它限制（仍然有效）

- **`--no-mmap` 会让子进程直接死亡**：2.5GB 模型实测必崩（PocketOrca 的 htp 口味用了这个，不能照抄）。
  微玄保持 mmap 开。
- **`-ub` 不用调**：512 / 1024 / 2048 在修正协议下测不出差异。
- **推测解码（draft 模型）在本机 NPU 路径上无收益**：draft 会与主模型争抢 NPU 算力，
  实测 decode 11.0 vs 12.0（持平）、prefill 107.8 vs 254（腰斩），另多占 380MB。默认禁用。
- **模型加载受存储带宽限制**：2.9GB 约需 **15~25 秒**（实测 ≈150MB/s）。
  换存储路径无效：`/sdcard`（FUSE）冷读要 49 秒，内部 f2fs 与 `/data/local/tmp` 才在 16~20 秒。
- **dense 大模型受内存物理上限约束**：权重 + KV 必须全量驻留，16GB 手机实际可用 5–7GB。
  **dense 14B 不可行。**
- **MoE 也不可行**（订正）：`Qwen3-30B-A3B` 一类在本机实测**读 139GB / 0 token** ——
  专家扫描范围不受 `--ubatch` 控制，长 prompt 每轮触达近乎全部专家。MoE 引擎**已从代码中移除**。
- **线性注意力模型不可行**：MiMo / Qwen3.5 系（32 层里 24 层无 KV）依赖
  `GATED_DELTA_NET / SSM_CONV / SSM_SCAN`，而 HTP 算子白名单里没有这三个 → NPU 结构性接不住，
  只能落 CPU（慢到不可用）。
- **`-ctk/-ctv q4_0` 绝对不要用**：HTP 无该算子，3/3 进程必崩。

---

## 五、模型规模建议

| 可用内存 | 建议模型规模（Q4_K_M） |
|---|---|
| 4–6 GB | ≤ 1.7B |
| 6–8 GB | ≤ 4B（**推荐区间**） |
| 8–12 GB | ≤ 7B |
| 12 GB+ | ≤ 8B（本机 16GB 机型可跑 8B，需先清后台） |
| 任意 | ❌ 14B / MoE / 线性注意力：**本机不可行**，见 4.3 |

上下文窗口：默认上限 6144（阶梯 8192 / 6144 / 4096，由可用内存自动决定），
且**工具与对话历史的 token 预算会跟着实际窗口动态分配**，不会出现"历史一长就撑爆"。

---

## 六、怎么补充这份矩阵

打包好数据开 issue / 直接改这张表：**SoC + HTP arch + 后端 + 量化格式 + 模型 + prompt 长度 + 实测 tok/s**。
判定逻辑在 `ModelCompat.kt`，加机型只需加一条设备硬伤结论。

> 复现命令与脚本见 `docs/BACKEND_COMPARISON.md` 附录；
> 基准方法学（为什么要带样本数与温度）见 `scripts/bench/README.md`。

---

## 修订记录

### 2026-10-04

**订正 4 处已被实测推翻的结论**（旧的错误结论在开源前必须清掉，否则会误导使用者）：

| # | 原文 | 实测结论 | 依据 |
|---|---|---|---|
| 1 | 「KV f16 / q8_0 影响测不出（<2%）」 | **q8_0 慢 3.4×**（1.93 vs 6.49 t/s） | `kv_ctx_ab.sh`，prompt 2997 token，重复性 ±1% |
| 2 | 「多模态模型**必须**加 `-nkvo`」 | **去掉 -nkvo 快 55%**，多模态功能与识别结果均正常 | `nkvo_test.sh` + `imgtest.sh`（真实图片） |
| 3 | 「要跑大模型**只能走 MoE**」 | MoE 实测读 139GB / 0 token，**引擎已从代码删除** | 2026-09-30 受控实验 |
| 4 | 「推测解码不可用（GenieX v0.7.0 会崩）」 | 已换自建 llama.cpp runtime，结论应为**实测无收益** | draft 0.6B 对照实验 |

**另撤回 1 处自证伪的判断**：

| 原文 | 结论 |
|---|---|
| 「decode 在 ≈16 与 ≈7.2 t/s 间摆动（2.3×），来源未确证」 | **撤回**。12 轮连续实测重复性为 **±6%**，不存在 2.3× 摆动；那个 7.2 t/s 是测量方自己反复加载模型造成的热态。**教训：热的源头是反复加载，不是推理本身。** |

**新增**：第一章「root 与权限」（无 root 可用性 + 未验证项标注）、第五章的规模建议按实测重写。
