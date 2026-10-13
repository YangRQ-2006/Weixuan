# 第三方许可声明（THIRD PARTY NOTICES）

微玄（WeiXuan）本身以 **AGPL-3.0** 许可。本文件列出随产品分发的第三方
组件及其许可。

## 本地图像生成引擎（GPU / Vulkan 后端）

### stable-diffusion.cpp

- 来源：https://github.com/leejet/stable-diffusion.cpp
- 许可：**MIT**
- 用途：本地文本生成图像（SD1.5），Vulkan 后端
- 说明：其内嵌的 patched ggml 子模块随源码一同使用（同为 MIT）

### 模型（不随仓库分发）

- Stable Diffusion 1.5 权重：CreativeML Open RAIL-M（非 OSI，含使用限制）。
  由用户在首次使用时自行下载，微玄不分发该权重。

## 本地语言模型运行时

### llama.cpp / GenieX fork

- 用途：本地 LLM 推理服务（llama-server）
- 许可：MIT（llama.cpp）；GenieX fork 的许可以其仓库为准

## 工具链产物

- `libQnnHtp*.so` 等 Qualcomm 运行时：**未随微玄分发**（NPU 路线后续集成时
  需遵循 Qualcomm AI Runtime SDK 的许可条款；local-dream 的做法是把
  `QNN_SDK_ROOT/LICENSE.pdf` 与 `NOTICE.txt` 随包分发）。

## 授权记录

- **local-dream**（https://github.com/xororz/local-dream，CC BY-NC 4.0）：
  作者 xororz 于 2026-10-07 在其 issue #327 授权微玄
  「在非商用用途下、以 AGPL-3.0 使用其代码，并保留原版权与许可声明」。
  该授权记录见：https://github.com/xororz/local-dream/issues/327
  （微玄当前为个人非商用项目；若未来商用需另行取得授权。）
