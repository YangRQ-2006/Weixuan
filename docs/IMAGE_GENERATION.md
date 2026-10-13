# 微玄 · 本地图像生成（GPU / Vulkan 后端）

> 状态：**已实现代码，未编译验证**（2026-10-07）。
> 编译与真机实测待执行（见文末「待办」）。

## 1. 它是什么

在微玄内**完全本地**生成图像：
- 引擎：[stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp)（MIT，⭐7.6k）
- 后端：**Vulkan**（Adreno 840）
- 模型：SD1.5 的 **GGUF**（单文件，约 2GB）
- 入口：Agent 工具 `generate_image`（设置 → 工具能力 → 本地图像生成，默认关）

## 2. 为什么是 Vulkan，而不是 OpenCL / NPU

| 候选 | 结论 | 依据 |
|---|---|---|
| **OpenCL** | ✗ 不可用 | Android 普通 App 因 linker 命名空间限制访问不到 `/vendor/libOpenCL.so`（`ggml_opencl: platform IDs not available`）；只有声明 `uses_libraries` 的系统 App 可用。GPU 加速的正确路径是 Vulkan。 |
| **NPU（QNN/HTP）** | ⏸ 后续路线 | 需要 Qualcomm AI Runtime SDK（用户自行注册下载）+ AI Hub 模型。**许可已解决**：local-dream 作者 xororz 于 issue #327 授权「AGPL-3.0 + 非商用 + 保留声明」。详见 §7。 |
| **Vulkan** | ✓ 本路线 | `libggml-vulkan-adreno.so` 已注册 89 个算子（含 CONV_2D / CONV_TRANSPOSE / GROUP_NORM / IM2COL / PAD / SILU），扩散模型所需算子齐全；零外部依赖、许可干净（MIT）。 |

## 3. 内存仲裁（本设计的核心约束）

本机 16GB（实际可用 5–7GB，HyperOS 占满）：

```
LLM 服务（4B fp16 + KV）稳态 ≈ 3.4GB
SD1.5 fp16                   ≈ 1.7GB
⇒ 二者不能同时驻留
```

**解法**：出图期间暂停 LLM 服务，工具返回前恢复。

关键观察：**agent 的工具调用发生在两次 LLM 请求之间**（此时 LLM 空闲、无在途请求），所以「停 LLM → 出图 → 恢复 LLM → 返回结果」这条链路是安全的。恢复在 `ImageGenerationEngine.release()` 内**同步完成**（`LlamaServerProcess.start(...)` 用出图前记录的模型路径与端口），agent 下一轮请求不受影响。

温度门控：出图前 SoC 温度 > 40°C 直接拒绝（与 LLM 热保护同源的 `LocalResourceGuard.maxTemperatureC()`）。

## 4. 构建

```bash
sh scripts/build_sd_android.sh
```

- 用 NDK toolchain（`/opt/android-sdk/ndk/<ver>`）+ `-DSD_VULKAN=ON`
- 着色器用宿主机 `/usr/bin/glslc` 编译（设备端不编译）
- ggml 用 sd.cpp **自带的 patched 子模块**（上游 ggml 缺 SD 算子）
- 产物：`app/src/main/jniLibs/arm64-v8a/libweixuan_sd.so`
  （JNI 桥 `app/src/main/jni/sd_jni.cpp` 静态链接 sd.cpp + ggml + ggml-vulkan）

## 5. 模型获取

SD1.5 的 GGUF 单文件模型（约 2GB）放到：

```
/sdcard/Android/data/cn.yangrq.weixuan/files/models/sd/*.gguf
```

（App 专属外部目录 —— 清除数据不丢；内部 `/data/data/<pkg>` 会被系统
清除数据整个删除，2026-10-12 实测，故不放在内部。）

HuggingFace 在国内不可达，引擎默认下载基址为 `https://hf-mirror.com`
（`ImageGenerationEngine.MODEL_BASE_URL`，可在设置里改）。

> 模型许可：SD1.5 权重为 CreativeML Open RAIL-M（非 OSI，含使用限制条款）。
> 微玄是 AGPL-3.0 —— 代码与模型许可分离分发（模型不随仓库分发，用户自行下载），
> 与 AGPL 兼容；README 需注明模型许可。

## 6. 工具行为

```
generate_image(prompt, negative_prompt?, width=512|768, height=512|768,
               steps=4..30, cfg_scale=1..20, seed?)
→ { ok, path, width, height, seconds, note }
```

- 图片存到 `<filesDir>/images/img-<时间戳>.png`
- 结果里给绝对路径，模型可用 `read_image` 回看
- 失败码：`IMAGE_DISABLED` / `MODEL_MISSING` / `IMAGE_ERROR`

## 7. NPU 路线（后续，已获授权）

- 授权：local-dream 作者 xororz，issue #327（2026-10-07）：
  「You're free to use the code from this project for non-commercial purposes.
   You can use it under AGPL-3.0. But please keep the original copyright and license notices.」
- 待办：Qualcomm AI Runtime SDK 2.50.0.260828（用户从
  https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk 下载；
  developer.qualcomm.cn 在国内不可达，用 www.qualcomm.com）
- 注意：npu-lab 实测 **V79 的 context binary 不能跑在 V81 上** → 模型必须匹配 Hexagon 版本
- 集成后与 Vulkan 版是**两个独立引擎**，由设置项切换

## 8. 待办（按优先级）

1. **编译验证**（`scripts/build_sd_android.sh`）+ 真机出一张 512×512 图，实测耗时
2. 温度/内存实测（出图期间 SoC 温度曲线、峰值内存）
3. 设置页 UI（开关 + 模型下载 + 引擎选择 Vulkan/NPU）
4. 内容安全过滤（local-dream 自带 11.9MB `safety_checker.mnn` 的做法值得照做）
5. 模型下载器（hf-mirror.com 断点续传 + 进度）

## 9. 性能预期（**未实测，均为估计**）

- NPU（QNN，w8a16）：约 2–5 秒/张（20 步）
- GPU（Vulkan，fp16）：约 10–30 秒/张（20 步）；LCM 4 步可降到 2–10 秒
- CPU：30–120 秒/张（不实用）

**这些数字必须实测验证** —— 尤其是 GPU 版，它是决定「要不要为 NPU 去折腾
Qualcomm SDK」的关键数据。
