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

- 🧠 **GPU+NPU 混合推理**：prefill 走 GPU、decode 走 NPU、调度走 CPU（hybrid 后端），实测 4B 模型 prefill ≈ 280 tok/s
- 📦 **本地模型管理**：GGUF 模型一键下载 / 本地导入、加载 / 卸载、推测解码草稿模型
- 🤖 **技能智能体**：31 个内置工具（读文件、执行命令、屏幕操作…），模型可直接输出工具调用
- 🔒 **完全离线**：无任何联网模型提供商，推理全程在手机端完成，数据不出设备
- 💬 **多轮对话**：完整会话上下文、流式逐 token 上屏
- 🛡️ **资源守护**：内存 / 温度安全边界、加载后实测审计（余量不足自动卸载）、前台执行租约防进程清理
- 🎛️ **采样可配**：温度、top-p、上下文窗口（按模型 KV 密度自适应）

## 真机实测（小米 16GB · 2026-09-25）

| 模型 | 场景 | 结果 |
|------|------|------|
| Qwen3-0.6B-Q4_K_M | 全链路验证 | ✅ 流式回复 |
| Qwen3-4B-Q4_K_M | 8k tokens 智能体 prompt | ✅ 正常回复 + 工具调用（read_file / run_command）|

## 技术栈

| 层级 | 选型 |
|------|------|
| 语言 | Kotlin 2.4.10 |
| UI | Jetpack Compose |
| 助手框架 | 深度定制系统级助手（Xposed 集成 / 语音助手 / 技能 / 终端）|
| 推理引擎 | GenieX Android SDK v0.7.0（llama.cpp + QAIRT）|
| 算力后端 | hybrid：Adreno GPU + Hexagon NPU + CPU |
| 模型格式 | GGUF（Qwen3 / DeepSeek-R1-Distill 等）|

## 构建

```bash
sh dist/eta-build.sh . assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

要求：Android Studio 里的 AGP 9.3.2 + Gradle 9.6.1（wrapper 自带）。

## 致谢

- [Eta](https://github.com/Mangi-11/Eta) —— 系统级 AI 助手框架（Apache-2.0）
- [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) —— 模型管理 UI 基础
- Qualcomm GenieX —— 端侧大模型推理 SDK

License: [Apache-2.0](LICENSE)
