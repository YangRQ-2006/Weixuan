# 微玄自建 llama.cpp Runtime（工程说明）

> 2026-09-25 立项 —— 目标：让 **14B+ 模型在这台手机上真正可用**。

## 为什么自建

| 维度 | GenieX SDK（原方案） | **官方 llama.cpp（自建）** |
|---|---|---|
| 模型加载 | 大块预分配 —— 实测 14B 加载 RSS 冲到 **4.3GB+** 并拖垮系统（Launcher 被杀、壁纸丢失） | **mmap 按需分页** —— 9GB 模型仅需几百 MB 物理内存 |
| KV cache | 不可配置 | **`-ctk/-ctv q8_0`** —— KV 内存减半，窗口可加倍 |
| 注意力 | 不可配置 | **`-fa on`** flash-attention（更快 prefill） |
| 前缀缓存 | Java 层未暴露（native 有） | **`--prompt-cache`** —— Agent 场景可跳过大部分 prefill |
| 参数面 | Java 层只暴露 10% | **全量可配** |

## 架构（最简集成，Agent 侧零改动）

```
微玄 App
 ├─ 自建模式（LocalSettings.useSelfBuiltEngine = true）
 │    └─ LlamaServerProcess ──启动──> libllama-server.so 子进程（监听 127.0.0.1:<port>）
 │                                    参数：--mmap -ctk q8_0 -ctv q8_0 -fa on -c <ctx>
 │
 └─ Agent 本地 Provider baseUrl（127.0.0.1:<port>/v1）——不变，OpenAI 兼容
```

- llama-server 提供 **OpenAI 兼容** `/v1/chat/completions`（与微玄原服务同协议）
- 与微玄原服务**同端口**（默认 18787）→ Agent 侧无感切换
- 依赖库（libllama/libggml 等）随 APK 打包进 `jniLibs/arm64-v8a/`，
  运行时经 `LD_LIBRARY_PATH=nativeLibraryDir` 解析

## 编译（可复现）

```bash
# 源码：/workspace/GenieX/third-party/llama.cpp（GenieX 同源，含高通后端）
# NDK 29.0.14206865 + CMake 3.25
cmake -B b-android -S <llama.cpp> \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
  -DLLAMA_BUILD_APP=OFF -DLLAMA_BUILD_SERVER=ON -DLLAMA_BUILD_TOOLS=ON \
  -DLLAMA_BUILD_UI=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TESTS=OFF \
  -DGGML_OPENMP=OFF
cmake --build b-android -j6 --target llama-server
```

**产物**（打包进 jniLibs/arm64-v8a/）：
`llama-server` → `libllama-server.so`（launcher）；
`libllama-server-impl.so`、`libllama.so`、`libllama-common.so`、`libmtmd.so`、
`libggml.so`、`libggml-cpu.so`、`libggml-base.so`

**坑位记录**：
1. `LLAMA_BUILD_TOOLS=OFF` 会导致 server 目标不存在（server 在 tools/ 下）→ 必须 ON
2. 不可编译 `llama-app` 目标（缺 build-info.h）→ 用 `--target llama-server` 精确构建
3. Android 可执行文件需 `.so` 后缀 + `useLegacyPackaging=true` 才能提取到 nativeLibraryDir
4. 交叉产物无 RPATH → 运行必须设 `LD_LIBRARY_PATH`

## 预期效果（待实测填充）

| 项目 | 预期 |
|---|---|
| 14B 加载内存足迹 | 权重 mmap 驻留 ~0.5-1GB（vs SDK 的 4.3GB+） |
| 14B KV（4096 ctx, q8_0） | ~1.2GB |
| 总可用性 | 16GB 手机（可用 5GB）**可加载成功** |

## 后续优化方向（编译已就绪，参数即可启用）

- `--prompt-cache <file>`：Agent 前缀缓存（多轮对话跳过重复 prefill）
- hexagon/opencl 后端（源码已含 ggml-hexagon/ggml-opencl，可编入获得 NPU/GPU 加速）
- KV 量化进一步下调（q4_0）→ 窗口再翻倍
