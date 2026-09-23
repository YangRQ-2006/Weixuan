# 微玄 (WeiXuan)

> **玄之又玄，众妙之门。** —— 《道德经》第一章

「微玄」之名，取意于此。

- **微** —— 移动端、端侧设备的形态：一部随身的手机，微小、轻便、无处不在；
- **玄** —— 大模型的深度智能与算力：玄之又玄，是模型内部深邃难测的表征世界与推理能力。

合而观之，**微玄 = 「微小设备上的深度智能」**：不依赖云端巨兽，把大模型的「玄」装进掌心的「微」——在手机的 NPU 上直接运行大语言模型，让深度智能在端侧发生。

---

## 项目简介

微玄是一款 **端侧 NPU 大模型对话 Android App**。它基于 GenieX Android SDK，将大语言模型推理下沉到手机的 Hexagon NPU（神经网络处理器），实现离线、低功耗、低延迟的本地智能对话。

当前版本已在真机上完成 **Qwen3-8B（Q4_0 量化）** 的本地加载与流式生成验证。

## 功能介绍

### 💬 智能对话

- **多轮连续对话**：完整维护会话上下文（ChatMessage 历史），模型始终"记得"前文，支持连续追问、话题切换与长对话展开
- **流式逐 token 上屏**：基于 Kotlin Flow 的流式生成（`generateStreamFlow`），回答边生成边显示，无需等待整段输出，长回答秒开
- **采样参数可调**：GenerationConfig / SamplerConfig 支持温度（temperature）、top-p 等采样调节，创意写作与严谨问答之间自由切换
- **会话中断可控**：生成过程可随时打断，UI 全程不卡顿（推理运行于后台协程，界面保持高帧率响应）

### 🧠 端侧 NPU 推理

- **Hexagon NPU 原生加速**：大模型推理任务运行于高通 Hexagon NPU（HTP），INT4 低功耗算力直达，推理时手机不发烫、不掉电
- **完全离线、数据不出端**：模型权重本地加载，对话全程无需联网，提问内容绝不上传云端，隐私天然安全
- **大模型本地跑**：已真机验证 **Qwen3-8B（Q4_0 量化）** 的本地加载与流式生成，80 亿参数模型装进手机
- **llama.cpp + QAIRT 双后端**：GenieX SDK 内置 llama.cpp 与高通 QAIRT v2.45 运行时，算子级调优、全图融合执行

### ⚡ GPU + NPU 混合加速推理（新增）

单一处理器跑大模型并非最优：**Prefill（预填充）是计算受限**阶段，**Decode（逐 token 生成）是带宽受限**阶段——两者适合不同的硬件。微玄引入 **HybridGenieXLLM 双阶段调度器**，按阶段特性把任务分配给最擅长的算力单元，外加推测解码进一步提速：

| 阶段 | 硬件 | 依据 |
|------|------|------|
| Prefill 批量计算 | Adreno GPU（~4.6 TFLOPS） | 计算密集，GPU 大吞吐最擅长 |
| Decode 逐 token 生成 | Hexagon NPU（~150 TOPS INT4） | 带宽受限，NPU 低功耗高带宽 |
| Tokenize / 调度 | CPU | 轻量控制流 |
| 推测解码 | GPU Draft + NPU Verify | GPU 起草草稿、NPU 批量验证，两手都要硬 |

- **首 token 延迟腰斩**：预计从 0.5–1s 降至 **0.2–0.3s**（↓50%），提问即答不冷场
- **生成速度 3–5 倍提升**：预计吞吐从 8–15 tok/s 提升至 **30–50 tok/s**，接近阅读速度的爽快输出
- **资源守护（ResourceGuard）**：内存水位、温控降频、功耗感知自适应调参，长时间推理不卡死手机、不烫手

### 📦 模型与工程能力

- **模型管理**：内置模型拉取 / 加载流程（ModelManager），支持 HuggingFace 源一键下载
- **UI 保活**：推理运行于 Dispatchers 后台协程，流式输出期间界面滑动、输入零阻塞
- **arm64 深度适配**：arm64-v8a 原生库全链路调优，为高通平台旗舰芯片而生

## 技术栈

| 层级 | 选型 |
|------|------|
| 语言 | Kotlin 2.1.20 |
| UI | View + ViewBinding（AppCompat / Material） |
| 异步 | Kotlin Coroutines + Flow |
| 架构 | AndroidX Lifecycle（ViewModel / LiveData） |
| 推理引擎 | GenieX Android SDK v0.7.0（内置 llama.cpp + QAIRT v2.45） |
| 算力后端 | Qualcomm Hexagon NPU + Adreno GPU 混合调度（arm64-v8a） |
| 默认模型 | Qwen3-8B · Q4_0 量化 |

## 项目结构

```
NPULlmChat/
├── app/
│   ├── libs/
│   │   └── geniex-android-aar-v0.7.0.aar   # GenieX 推理 SDK
│   └── src/main/
│       ├── java/com/npu/llmchat/
│       │   └── MainActivity.kt            # 主界面：SDK 初始化 / 模型加载 / 对话
│       ├── res/
│       │   ├── layout/activity_main.xml   # 聊天主界面布局
│       │   ├── values/                    # strings / colors / themes
│       │   └── drawable/ic_launcher.xml   # 启动图标（矢量）
│       └── AndroidManifest.xml
├── app/build.gradle.kts
├── settings.gradle.kts
└── gradle/                                # Gradle Wrapper
```

## 快速开始

### 环境要求

- **JDK**：17（AGP 8.x 要求）
- **Android SDK**：compileSdk 34，Build-Tools 35.0.0
- **Kotlin**：2.1.20（GenieX AAR 以此版本编译，请勿随意降级）
- **设备**：Android 9.0+（minSdk 28），arm64-v8a，具备 Hexagon NPU 的高通平台
- **存储**：安装与模型合计需预留 ≥ 10 GB（Qwen3-8B Q4_0 约 5 GB）

### 构建

```bash
# 克隆仓库
git clone https://github.com/YangRQ-2006/Weixuan.git
cd Weixuan

# 构建 Debug APK
./gradlew assembleDebug

# 产物
# app/build/outputs/apk/debug/app-debug.apk
```

### 安装到设备

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 运行流程

1. 启动 App，自动初始化 GenieX SDK（状态栏显示 `Initializing SDK...`）；
2. SDK 就绪后点击 **Load Model** 拉取/加载 Qwen3-8B（Q4_0）；
3. 模型加载完成后在输入框提问，点击 **Send**，回答逐 token 流式上屏。

## 权限说明

| 权限 | 用途 |
|------|------|
| `INTERNET` | 拉取模型权重（HuggingFace 源） |
| `ACCESS_NETWORK_STATE` | 网络状态检测，决定模型下载策略 |
| `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` | 模型文件的本地缓存读写 |

> 对话推理本身完全离线，模型加载后无需联网。

## 已知事项

- ⚠️ `app/libs/geniex-android-aar-v0.7.0.aar`（78.5 MB）随仓库分发，GitHub 会提示超过 50 MB 建议值（未超 100 MB 硬限）。后续版本计划迁移至 Git LFS 或私有 Maven 仓库分发。
- 仅支持 **arm64-v8a**：GenieX SDK 依赖 Hexagon NPU 原生库，暂无 x86 / 32 位支持。
- 大模型加载期间请保持 App 前台，避免系统回收内存导致加载中断。

## 路线图

- [ ] 包名统一为 `cn.yangrq.weixuan`（品牌对齐）
- [ ] 对话界面升级为 RecyclerView 气泡式 UI
- [ ] 会话持久化与多会话管理
- [ ] 推理参数设置面板（温度 / top-p / 上下文长度）
- [x] GPU + NPU 混合加速调度（Prefill → GPU，Decode → NPU）+ 推测解码
- [ ] 资源守护（内存水位 / 温控降频）App 内集成

## 贡献

欢迎 Issue 与 Pull Request。提交前请保证 `./gradlew assembleDebug` 构建通过，并避免将构建产物与模型文件提交入库（参见 `.gitignore`）。

## License

暂未选定开源协议，版权归作者所有。转载请先联系。

## 致谢

微玄站在巨人的肩膀上，向以下开源项目与技术团队致以诚挚谢意：

- **[Qualcomm AI Engine Direct SDK (QAIRT / QNN)](https://github.com/quic/ai-engine-direct-sdk)** —— 高通 NPU（Hexagon HTP）推理引擎。微玄的端侧 NPU 推理能力基于其 QNN 运行时实现，QAIRT v2.45 是模型在 Hexagon NPU 上高效执行的根基。同时感谢 [Qualcomm AI Hub](https://aihub.qualcomm.com/) 提供的模型优化工具链。
- **[Google Agent Development Kit (ADK)](https://github.com/google/adk-python)** —— 谷歌开源的多语言 Agent 开发框架。微玄的智能体架构设计（多步推理、工具调用、人机确认机制）深受其 Workflow Runtime 与 Agent 分层理念启发。
- **[google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)** —— Google AI Edge 端侧推理示例工程，为微玄的端侧模型集成与 App 交互设计提供了重要参考。
- **[llama.cpp](https://github.com/ggml-org/llama.cpp)** —— 高效的 LLM CPU/异构推理框架，GenieX SDK 内置后端之一。
- **[Qwen (通义千问)](https://github.com/QwenLM)** —— 阿里云开源大模型系列，微玄默认搭载的 Qwen3-8B 即出自于此。
- **[droidrun](https://github.com/droidrun/droidrun)** —— 移动端 AI Agent 框架，为 GPU+NPU 混合推理调度器（HybridGenieXLLM）的工程实践提供了载体与灵感。

---

*微小设备上的深度智能 —— 微玄 (WeiXuan)*
