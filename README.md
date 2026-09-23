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

## 功能特性

- 💬 **多轮对话**：维护完整会话上下文（ChatMessage 历史），支持连续追问
- ⚡ **流式输出**：基于 Kotlin Flow 的流式生成（`generateStreamFlow`），逐 token 上屏不等待
- 🧠 **NPU 端侧推理**：推理任务运行于 Hexagon NPU，不依赖网络、不出端
- 📦 **模型管理**：内置模型拉取/加载流程（ModelManager），支持 HuggingFace 源
- 🎛️ **采样参数可配**：GenerationConfig / SamplerConfig 支持温度、top-p 等采样调节
- 🛡️ **UI 保活**：推理运行于后台协程（Dispatchers），流式输出不阻塞界面

## 技术栈

| 层级 | 选型 |
|------|------|
| 语言 | Kotlin 2.1.20 |
| UI | View + ViewBinding（AppCompat / Material） |
| 异步 | Kotlin Coroutines + Flow |
| 架构 | AndroidX Lifecycle（ViewModel / LiveData） |
| 推理引擎 | GenieX Android SDK v0.7.0（内置 llama.cpp + QAIRT v2.45） |
| 算力后端 | Qualcomm Hexagon NPU（arm64-v8a） |
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
- [ ] 资源守护（内存水位 / 温控降频）集成
- [ ] 多模型切换（Qwen 系列 / 更小的端侧模型）
- [ ] 混合 GPU + NPU 调度（Prefill → GPU，Decode → NPU）

## 贡献

欢迎 Issue 与 Pull Request。提交前请保证 `./gradlew assembleDebug` 构建通过，并避免将构建产物与模型文件提交入库（参见 `.gitignore`）。

## License

暂未选定开源协议，版权归作者所有。转载请先联系。

---

*微小设备上的深度智能 —— 微玄 (WeiXuan)*
