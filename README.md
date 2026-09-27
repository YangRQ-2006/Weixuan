# 微玄 (WeiXuan)

> **玄之又玄，众妙之门。** —— 《道德经》第一章

「微玄」之名，取意于此。

- **微** —— 移动端、端侧设备的形态：一部随身的手机，微小、轻便、无处不在；
- **玄** —— 大模型的深度智能与算力：玄之又玄，是模型内部深邃难测的表征世界与推理能力。

合而观之，**微玄 = 「微小设备上的深度智能」**：不依赖云端巨兽，把大模型的「玄」装进掌心的「微」——在手机的 NPU 上直接运行大语言模型，让深度智能在端侧发生。

---

## 项目简介

微玄是一款**完全离线的端侧 AI 智能体 Android App**。深度定制的系统级助手架构与 Qualcomm GenieX 推理 SDK（llama.cpp + QAIRT）双引擎，把大语言模型推理下沉到手机的 **Adreno GPU + Hexagon NPU + CPU** 三路混合后端，实现离线、低功耗、低延迟的本地智能。

**微玄四要素**：品牌题记 · 完全离线 · NPU 加速 · 技能智能体。

## 功能特性

- 🧠 **NPU 优先推理**：Hexagon NPU（HTP）承载全部层，CPU 自动回退；实测 4B 模型简单对话 1–2 秒
- 🗜️ **KV 量化 + flash-attn**：自建运行时完整开放（KV q8_0 量化、flash-attention 等）
- 🌊 **超内存 MoE 流式**：MoE 模型按当前 token 激活的专家懒加载 + 热专家缓存，突破可用内存上限
- 💭 **思考模式开关**：可关闭思考直接作答（显著提速），或保留完整思考过程
- 📊 **推理实况**：回复下方显示解码速率（tok/s）与首 token 延迟
- 📦 **本地模型管理**：GGUF 模型一键下载 / 本地导入、加载 / 卸载、推测解码草稿模型
- 🤖 **技能智能体**：31 个内置工具（读文件、执行命令、屏幕操作…），模型可直接输出工具调用
- 🔒 **完全离线**：无任何联网模型提供商，推理全程在手机端完成，数据不出设备
- 💬 **多轮对话**：完整会话上下文、流式逐 token 上屏
- 🛡️ **资源守护**：内存 / 温度安全边界、加载后实测审计（余量不足自动卸载）、前台执行租约防进程清理
- 🎛️ **采样可配**：温度、top-p、上下文窗口（按模型 KV 密度自适应）

## 真机实测（小米 16GB · 2026-09-25）

| 模型 | 场景 | 结果 |
|------|------|------|
| Qwen3-0.6B-Q4_K_M | 全链路验证 | ✅ 流式回复（NPU，瞬时）|
| Qwen3-4B-Q4_K_M | 智能体多步任务（打开应用 → 观察 → 点击 → 输入 → 搜索）| ✅ 工具调用完整执行（NPU 后端，1–2 秒）|
| Qwen3-30B-A3B（MoE）| 超内存流式推理（模型 13–17GB > 可用内存）| 🔬 验证中（BigMoeOnEdge）|

## 技术栈

| 层级 | 选型 |
|------|------|
| 语言 | Kotlin 2.4.10 |
| UI | Jetpack Compose |
| 助手框架 | 深度定制系统级助手（Xposed 集成 / 语音助手 / 技能 / 终端）|
| 推理引擎 | 自建 [llama.cpp](https://github.com/ggml-org/llama.cpp) 运行时（Android NDK 交叉编译为 lib*.so，独立进程托管 + OpenAI 兼容本地 API）|
| 算力后端 | Hexagon NPU（HTP，经 Hexagon 后端动态库接入）优先，CPU 回退 |
| 大模型流式 | [BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge) —— MoE 专家按需加载 + 热专家缓存（模型体积超可用内存时启用）|
| 模型格式 | GGUF（Qwen3 系列，含 MoE）|

## 构建

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

要求：Android Studio 里的 AGP 9.3.2 + Gradle 9.6.1（wrapper 自带）。

## 致谢

微玄站在众多优秀开源项目的肩膀上，谨按依赖层级致谢：

### 推理引擎与运行时

- [llama.cpp](https://github.com/ggml-org/llama.cpp)（MIT）—— 端侧 LLM 推理引擎；本项目自建运行时由其源码经 Android NDK 交叉编译而来
- [BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge)（Apache-2.0）—— MoE 超内存流式推理引擎：模型大于可用内存时，按当前 token 实际激活的专家从存储懒加载，并以热专家缓存 + 预读实现 I/O 与计算重叠
- [Helldez/llama.cpp](https://github.com/Helldez/llama.cpp)（MIT）—— BigMoeOnEdge 所依赖的 llama.cpp 分支（提供 expert-ready 回调）
- Qualcomm GenieX —— 端侧大模型推理 SDK（QAIRT / Hexagon NPU 运行时）；本项目复用其 Hexagon 后端动态库接入 HTP

### 助手框架与界面

- [Eta](https://github.com/Mangi-11/Eta)（Apache-2.0）—— 系统级 AI 助手框架
- [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)（Apache-2.0）—— 模型管理 UI 基础

### 模型与量化

- [Qwen3](https://github.com/QwenLM/Qwen3)（Apache-2.0）—— 通义千问 Qwen3 系列模型（Alibaba）
- [Unsloth](https://github.com/unslothai/unsloth)（Apache-2.0）—— GGUF 动态量化（UD-Q3_K_XL 等）

License: [Apache-2.0](LICENSE)
