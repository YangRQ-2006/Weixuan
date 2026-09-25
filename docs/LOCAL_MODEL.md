# 本地模型（GenieX NPU/GPU）接入说明

本分支在**不改动 Eta 原有 Agent 框架、UI、Provider 协议**的前提下，把「云端大模型」替换为
「手机端本地模型」，数据不出手机、完全离线可跑。

## 1. 结论：本地模型能不能做系统级 Agent？

**能，但必须换掉「输入表示」而不是只换模型。**

Eta 的 GUI Agent 用的是**无障碍 UI 树 + 控件定位**（文字）+ 按需截图，而不是纯视觉截图驱动。
这对小模型是决定性的优势：

| 维度 | 云端方案（原版） | 本地方案（本分支） |
| --- | --- | --- |
| 屏幕理解 | UI 树 + 截图（多模态） | UI 树文本为主（`inputModalities=text`，图片自动降级为占位符） |
| 工具调用 | 厂商原生 function calling | chat template 原生 tools（Qwen3 等）+ 文本工具协议回退 |
| 单步输出 | 数百 token | 200~600 token（动作 JSON） |
| 8B Q4_0 实测能力 | — | 能完成「打开设置→找开关→点击」这类 3~8 步任务；复杂长链易跑偏 |
| 27B / MoE（30B-A3B） | — | 多步规划明显更稳，3B 激活的 MoE 在小核上反而比 dense 27B 更快 |

可行的关键工程手段（本分支已落地）：
1. 动作空间离散化：模型只吐 `tool_calls`（Eta 原生工具目录），不直接吐坐标；
2. 结构化输出约束：工具调用统一 `<tool_call>{...}</tool_call>` 信封 + 流式过滤器，
   把工具 JSON 从正文里摘出来（否则工具 JSON 会被当聊天内容展示）；
3. 双路径工具协议：先用 GGUF 自带 chat template 的 tools 支持，检测不到就注入文本协议；
4. 资源护栏：内存/温控/电量三路感知，避免端侧推理把手机卡死或被 LMK 杀进程。

**模型选型建议**
- 速度优先（推荐日常）：`Qwen3-30B-A3B`（MoE，3B 激活）或其它 A3B 级 MoE，Q4_0；
- 质量优先：`Qwen3-8B` Q4_0（4.7GB，稳定可跑）→ 复杂任务再上 27B 级（需 15GB 级可用内存，不流畅）；
- 推测解码：额外下载 `Qwen3-0.6B`(Q8_0) 作为 draft，可提速约 1.5~2x。

## 2. 架构：为什么改动能这么小

```
Eta 原有：AgentLoop → 工具执行 → UI（全部未改动）
                ↑
        OpenAiChatCompletionsProvider（Eta 原生，未改动）
                ↑   http://127.0.0.1:8787/v1/chat/completions （SSE）
        LocalOpenAiServer（本分支新增，回环 HTTP 服务）
                ↑
        GenieXLocalEngine（本分支新增，模板/流式生成/工具解析）
                ↑
        GenieX SDK（高通 NPU/GPU：libQnnHtp* + libgeniex-proc.so）
```

Eta 本来就支持「自定义 OpenAI 兼容服务地址」，因此本地模型被包装成**回环 OpenAI 兼容服务**后，
云端与本地可以随时切换，甚至同机共用。云端 Provider 配置完全不受影响。

## 3. 改动清单

### 新增（本地模型模块，包 `cn.yangrq.weixuan.local`）
| 文件 | 职责 |
| --- | --- |
| `LocalContracts.kt` | 契约层：`LocalChatEngine` / `LocalStreamEvent` / `LocalEngineState` |
| `LocalSettings.kt` | 独立 SharedPreferences：端口、模型名/精度、自定义路径、算力单元、draft 开关 |
| `GenieXLocalEngine.kt` | GenieX 生命周期：初始化、模型解析、加载/卸载、流式生成、取消 |
| `LocalChatConversion.kt` | OpenAI wire 消息 ↔ GenieX `ChatMessage` 转换（含多模态降级） |
| `LocalToolProtocol.kt` | 工具协议注入 + 流式工具调用过滤器 + 容错 JSON 解析器 |
| `LocalOpenAiServer.kt` | 回环 HTTP 服务：`POST /v1/chat/completions`（SSE/非流式）、`GET /v1/models` |
| `LocalResourceGuard.kt` | 内存/温控/电量护栏（移植自微玄 NPU Agent 优化套件） |
| `LocalPerfTuner.kt` | 自适应 nCtx/批大小/线程/SWA/推测解码（移植自微玄） |
| `ui/pages/local/LocalModelScreen.kt` | 「本地模型」配置页：开关服务、下载、加载、卸载、状态与速度 |
| `app/libs/geniex-android-aar-v0.7.0.aar` | GenieX SDK（仅 arm64-v8a，含高通 QNN HTP/GPU 运行时） |

### 修改（共 6 处，均为最小侵入）
| 文件 | 改动 |
| --- | --- |
| `data/provider/BuiltinProviders.kt` | 新增内置 Provider「本地模型（GenieX NPU）」，baseUrl `http://127.0.0.1:8787/v1`，预置文本模型条目（toolCall=true，attachment=false） |
| `EtaApp.kt` | 仅当用户开启本地服务时启动回环服务、初始化 SDK、按需自动加载模型 |
| `ui/navigation/AppRoute.kt` | 新增 `LocalModel` 路由 |
| `ui/app/AgentAppShell.kt` | 新路由标题 |
| `ui/app/AgentAppRoot.kt` | 注册路由入口 |
| `ui/SettingsScreen.kt` | 设置页新增「本地模型（GenieX NPU）」入口 |
| `app/build.gradle.kts` | 引入 `libs/*.aar`，ABI 收敛为 arm64-v8a |

## 4. 使用方法

1. 「设置 → 本地模型」→ 打开**启用本地回环服务**；
2. 点「下载模型」（默认 `Qwen/Qwen3-8B` Q4_0，约 4.7GB；建议同时下载 0.6B draft 开启推测解码）；
3. 点「加载模型」，状态变为「已就绪」；
4. 「设置 → 模型提供方」选择 **本地模型（GenieX NPU）** 及其模型条目；
5. 回到首页正常发指令（如「打开设置把亮度调到最低」），Agent 即用本地模型驱动手机。

## 5. 已知限制

- **图片输入**：纯文本模型下截图会被降级为占位文本，Agent 依赖无障碍 UI 树工作；如需视觉，
  需接入 GenieX 的 `VlmWrapper`（后续可扩展）。
- **内存**：加载 8B Q4_0 需约 5GB 可用内存；27B 级 dense 模型不流畅（带宽瓶颈），建议 MoE。
- **并发**：`nSeqMax=1`，同时只有一个生成任务；回环服务对并发请求排队。
- **首次加载**：NPU 图编译较慢，属正常现象。

## 6. 构建

Eta 依赖 AGP 9.3.2 / Kotlin 2.4.10 / Gradle 9.6.1 / compileSdk 37 / NDK r29 / Java 25 toolchain，
高于太墟默认工具链基准（Gradle 8.14.2 / JDK 17），因此使用专用脚本：

```sh
sh dist/eta-build.sh <Eta 工程目录> assembleDebug
```

脚本保留太墟的 ARM64 aapt2 override、SDK 禁自动下载与低内存策略，仅替换 Gradle 版本并允许
Gradle 通过 foojay 装配 aarch64 JDK 25。
