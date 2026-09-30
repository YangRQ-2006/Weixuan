# 微玄 UI 深度定制方案（Design Language & Rollout Plan）

> 版本：v1 · 2026-09-30
> 对象：`/workspace/Eta`（包 `cn.yangrq.weixuan`，git remote `YangRQ-2006/Weixuan`）
> 技术栈：Kotlin + Jetpack Compose + **Miuix KMP 0.9.4-rc01**（`top.yukonga.miuix.kmp`）+ Miuix Nav
> 上游：ETA（`github.com/Mangi-11/Eta`，Apache-2.0）

---

## 0. 一句话结论

**换色只是把 ETA 的皮换了颜色，骨相没动。** 当前 UI 的骨架、组件命名、几何、动效全部来自上游 ETA：

- 二级页（设置/工具/技能/角色/权限/MCP/终端…）统一是 **MIUI/ColorOS 式「分组卡片 + 图标槽 + 右箭头 + 缩进分割线」列表**；
- 全项目 **69 个文件 import Miuix，其中 `ui/components` 占 28/37**，聊天主界面（Body/InputBar/ModelControls + ChatMessageItem）累计 58 处 Miuix 依赖 —— 观感由 HyperOS 组件决定；
- 上游自己的组件文档 `docs/UI_COMPONENTS.md` 明确写着「视觉采用 **ColorOS 17 风格**」。

要真正"去 ETA 化"，必须做三件事：**① 清残留（含被吞掉的品牌色）→ ② 建微玄 Design Token + 适配层 → ③ 门面屏自绘**。

---

## 1. 现状诊断（why it still looks like ETA）

### 1.1 品牌色在浅色设置页被"吃掉" 🔴

`ui/components/EtaPreferenceStyle.kt:44-53`：

```kotlin
val pageColors = if (!appearance.monetEnabled && colors.background.luminance() > 0.5f) {
    colors.copy(background = Color(0xFFF0F1F2), primary = EtaPreferenceColors.Blue)  // ← MIUI 蓝 #0080FF
} else colors
```

`EtaPreferenceTheme` 是**全 App 设置页的公共外壳**，也就是说：只要用户不开 Monet 动态取色、又处于浅色模式，**所有设置/管理页的主色就是上游的 MIUI 蓝 `#0080FF`、背景是 `#F0F1F2`** —— 微玄的玄紫 `#5B4EC2` 完全失效。这是"换了主题色还是像 ETA"最直接的技术原因。

### 1.2 彩色图标墙（ColorOS/HyperOS 标志性观感）🔴

`EtaPreferenceStyle.kt:27-32` 的 `EtaPreferenceColors`：

| 常量 | 值 | 性质 |
|---|---|---|
| `Blue` | `#0080FF` | 上游 MIUI 蓝 |
| `Orange` | `#FF7700` | 上游鲜艳橙 |
| `Green` | `= StatusSuccess #00BD13` | 过饱和绿 |
| `Yellow` | `= StatusWarning #FFB200` | 过饱和黄 |

引用规模：`SettingsScreen.kt` 约 32 处、`LocalModelScreen.kt` 约 12 处、`SystemEnhanceScreen.kt` 约 9 处 —— 满屏彩色分组图标，是 HyperOS 设置页的强视觉符号。

`StatusColors.kt:12-13` 的 `#00BD13` / `#FFB200` 同样过于鲜亮，与「玄墨/留白」的克制调性冲突。

### 1.3 骨架与组件仍是上游原样

- 二级页范式（`MiuixScaffoldPage` + `LazyColumn` + `EtaPreferenceGroupTitle/Group/ArrowPreference` + 0.33dp 缩进分割线）在 20+ 页面复用；
- 组件命名与实现全为 `Eta*`：`EtaCard`(20 文件引用)、`EtaControls`(`EtaTextButton` 22 文件)、`EtaPreferenceRow`(52dp 行高 + `MiuixIcons.Basic.ArrowRight`)、`EtaSelectionPreferences`(9 文件)、`EtaFeatureCard`、`EtaPreferenceGroupItem`；
- 圆角只在 `EtaCardDefaults.CornerRadius = 16.dp` 收敛（上游 24dp），但 `ChatMessageItem.kt` **17 处 `RoundedCornerShape(10.dp/12.dp)` 旁路**，未走总开关；
- 顶栏毛玻璃（`TopBarBackdrop.kt` 的 `textureBlur/progressiveTextureBlur`）是 HyperOS 强符号；
- 全 App `Color(0x...)` 硬编码 50 处、`.dp` 字面量 755 处（`ChatMessageItem.kt` 独占 178 处）。

### 1.4 资源层与标识残留

| 类别 | 位置 | 说明 |
|---|---|---|
| Google 四色模板图标 | `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher(_round).png` ×10 | 白底 + `#4285F4/#34A853/#EA4335/#FBBC05`（逐像素吻合）；minSdk 31 下运行时不生效，纯残留 |
| AS 模板死色 | `values/colors.xml:3-9`（purple/teal 全套） | 全项目零引用 |
| Material 父主题 | `values/styles.xml:3/14/21`、`values-night/styles.xml` | `@android:style/Theme.Material.*` |
| ETA 类名 | `AndroidManifest.xml:46/71/85/99/104/118` | `.EtaApp`、`.EtaVoiceAssistActivity`、`EtaVoiceInteractionService`、`EtaVoiceInteractionSessionService`、`EtaRecognitionService`、`EtaAssistantOverlayService`（改类名必须同步 `res/xml/voice_interaction_service.xml:3-4`） |
| 上游定位文案 | `values/strings.xml:6` `xposed_description` | 仍是 ETA 的"接管 Breeno/超级小爱…解锁 Gemini"表述 |
| 上游仓库链接 | `SettingsScreen.kt:749` | 关于页「源代码」仍指向 `github.com/Mangi-11/Eta` |
| 死代码 | `ui/components/ToolChip.kt`（零引用）、`drawable/ic_kimi_code.xml`（零引用） | |

> 好消息：用户可见文案的「Eta」已基本清零（值已是「微玄/WeiXuan」）；品牌色板 `AgentAppTheme.kt:78-133`（玄墨/留白）、自适应图标（玉璧爻象）、启动屏 `#0D0D12` 都已完成品牌化。**这批资产是本轮方案的立足点。**

### 1.5 缺失维度（"自有视觉语言"的空白项）

| 维度 | 现状 | 影响 |
|---|---|---|
| 品牌字体 | `res/font` 不存在，无任何 .ttf/.otf；仅终端用内置 Monospace | 无排版个性 |
| 形状 token | 无；圆角散落 27 处 | 无法一处改全 |
| 间距 token | 无；755 处 `.dp` 字面量 | 密度不可调 |
| 动效 token | 无；由 Miuix `folmeSpring`（小米弹簧）主导 + 21 处 `AnimatedVisibility` | 手感是"MIUI 的手感" |
| 图标系统 | `material-icons-extended` | Google Material 图标语义 |

### 1.6 Miuix 的能力边界（决定技术路线）

来自 AAR `javap` + 官方文档（`compose-miuix-ui/miuix`，已从 `Yukonga/...` 迁移）：

- **只有两类 token**：`Colors`（53 槽）+ `TextStyles`（14 项）。**没有 Shapes / Spacing / Elevation / Motion token，也没有组件级 `Style/StyleScope` 抽象**。
- 组件默认值（`CardDefaults.CornerRadius`、`ButtonDefaults.InsideMargin`、`TopAppBarDefaults.*Padding`、TextField/SearchBar 字号）**是只读常量，无 setter** —— 想全局改只能逐调用点传参或包一层。
- 官方定位：**「所有组件严格遵循 Xiaomi HyperOS Design Guidelines」**，且库自述 **experimental，API 可能随时变化**。
- 结论：**仅靠换色永远去不掉 MIUI 骨相**，必须建适配层 + 少量自绘。

---

## 2. 目标视觉语言：微玄 ·「墨玄」

### 2.1 定位

> **墨玄 —— 一张会思考的宣纸。**
> 与 HyperOS/ColorOS 的「圆润玻璃卡片 + 彩色图标墙」相对：微玄走 **墨色/宣纸底 + 0.5dp 墨线 + 通栏分区 + 极简排版 + 微光（流金）点缀**，把"玄"的幽深、书卷气做成触摸得到的东西。

三条设计准则：
1. **纸感优于玻璃**：默认无毛玻璃、无大投影，靠底色差 + 墨线分层。
2. **少色优于多色**：全 App 主色仅玄紫，强调仅流金；彩色图标墙收敛为 5 个低饱和分类色。
3. **字重优于装饰**：用排版层级与留白建立节奏，而非圆角卡片与彩色图标。

### 2.2 色彩规范（Token 级）

**深色「玄墨」**（主推）

| Token | 值 | 用途 |
|---|---|---|
| `background` | `#0D0D12` | 玄墨底（已落地） |
| `surface` / `surfaceContainer` / `High` / `Highest` | `#14141A` / `#191920` / `#1E1E27` / `#24242E` | 浮层阶梯（已落地） |
| `onSurface` | `#ECEAF2` | 正文（已落地） |
| `primary` / `onPrimary` / `container` | `#9B8CFF` / `#1A1233` / `#2C2450` | 玄紫（已落地） |
| `secondary` / `container` | `#7FB3A0` / `#1C3A31` | 竹青（已落地） |
| `tertiary`（**新增使用**） | `#E8C56A` | **流金**：数字指标、选中态、品牌标记 |
| `outline` / `outlineVariant`（新增） | `#3A3A47` / `#26262F` | 墨线 / 弱墨线 |

**浅色「留白」**

| Token | 值 | 用途 |
|---|---|---|
| `background` / `surface` | `#FAF8F4` / `#FFFFFF` | 宣纸底（已落地） |
| `surfaceContainer` → `Highest` | `#F6F4EF` → `#EBE8E1` | 素笺阶梯（已落地） |
| `primary` / `container` | `#5B4EC2` / `#E9E4FF` | 玄紫（已落地） |
| `secondary` / `container` | `#3E7A5E` / `#D3EBDD` | 竹青（已落地） |
| `tertiary`（**新增使用**） | `#B08A2E` | 流金（浅色下加深以保证对比度） |
| `outline` / `outlineVariant` | `#D8D3C8` / `#E7E3DA` | 暖灰墨线 |

**分类色（取代 `EtaPreferenceColors` 的 4 个鲜艳色）**

| 语义 | 深色 | 浅色 | 适用 |
|---|---|---|---|
| 玄紫 | `#9B8CFF` | `#5B4EC2` | 模型 / 推理 / 主入口 |
| 竹青 | `#7FB3A0` | `#3E7A5E` | 工具 / 技能 / 成功 |
| 流金 | `#E8C56A` | `#B08A2E` | 记忆 / 上下文 / 关键数字 |
| 青黛 | `#8FA3BF` | `#4A5A72` | 权限 / 系统 / 次要信息 |
| 朱砂 | `#E07A6E` | `#B5453A` | 错误 / 删除 / 停止 |

**语义状态色收敛**：`StatusSuccess #00BD13 → 竹青`、`StatusWarning #FFB200 → 流金`、`StatusError → 朱砂`。

### 2.3 形状 / 间距 Token

```
XuanRadius:  xs 6  |  sm 10  |  md 14  |  lg 18  |  seal 2  |  pill 999
             标签     工具行    内容卡     浮层/弹窗   印章标记
XuanSpace:   xs 4 | sm 8 | md 12 | lg 16 | xl 20 | xxl 28
             页边距 16（宽屏 24）· 组间距 20 · 设置行高 56（通栏）/ 列表行 52
XuanStroke:  hairline 0.5dp（墨线）· 1dp（选中）
```

要点：**设置型页面弃用卡片**，改「通栏分区 + 1dp 墨线分隔 + 20dp 组间距」，只在聊天内容、浮层、弹窗保留卡片 —— 与聊天气泡形成层级差。

### 2.4 排版

- **品牌衬线**：引入 `Noto Serif SC` 子集（otf，仅用于：首页空态大标题、关于页题记、角色名、分组标题可选）。正文/UI 保持系统字体（可读性 + 体积可控）。
- **数字指标**（`tok/s`、首字延迟、NPU 状态、内存）：等宽 + 流金（现状已有 Monospace 基础，13 处，统一收口）。
- 字号微调：`title1` 32 → 30sp；`subtitle` 加 `letterSpacing 0.4sp`；分组标题由 14sp 灰 → 13sp + 主色左侧 2dp 小竖线（"签"标记）。

### 2.5 动效（"玄缓"）

```
XuanMotion: fast 120ms | base 220ms | emphasis 320ms
曲线：CubicBezier(0.22, 0.61, 0.36, 1.0)   // ease-out-cubic，缓入缓出
```
- **默认无回弹**（替换 Miuix `folmeSpring` 的小米手感）；仅"印章落纸"（发送、保存成功）用一次 12% overshoot。
- 流式文字：保留 `SmoothTextReveal` 的逐字揭示，改为「晕染」——行间 24ms 错峰渐显。
- 思考中脉冲：改为「爻线呼吸」（2.4s 循环，透明度 0.35↔1.0）。

### 2.6 图标与文案

- 图标：自绘 24dp「爻线」风格（1.6dp stroke / round cap），先覆盖顶栏 6 个 + 侧栏 Dock 6 个 + 设置分组；其余保留 Material 过渡。
- 文案语气：文言克制。分组标题可用四字（玄机 / 器用 / 行止 / 源流 / 护持），空态沿用「玄之又玄，众妙之门」。

---

## 3. 分阶段实施计划

### P0 · 品牌贯通与痕迹清除（0.5–1 天，低风险）

> 目标：让品牌色真正生效、清掉 Google/ETA 可见残留。**不改结构，可立即交付并装机验证。**

| # | 改动 | 位置 |
|---|---|---|
| 1 | 删除浅色强制覆写（`#F0F1F2` / MIUI 蓝），让 `EtaPreferenceTheme` 直通 Miuix 主题 | `EtaPreferenceStyle.kt:44-53` |
| 2 | `EtaPreferenceColors` 四色 → 微玄五分类色 | `EtaPreferenceStyle.kt:27-32` |
| 3 | 状态色降饱和（成功→竹青、警告→流金、错误→朱砂） | `StatusColors.kt:12-13` |
| 4 | `values/colors.xml` 模板色 → 微玄品牌色板（XML 层品牌化），删无引用项 | `values/colors.xml` |
| 5 | 删 10 个 Google 四色模板 PNG（minSdk 31 下运行时不用） | `mipmap-*/ic_launcher(_round).png` |
| 6 | 类名 `EtaApp/EtaVoice*/EtaRecognition*/EtaAssistantOverlay*` → `WeiXuan*`，同步 `res/xml/voice_interaction_service.xml:3-4` | `AndroidManifest.xml:46/71/85/99/104/118` |
| 7 | `xposed_description` 改写为微玄定位语；关于页「源代码」链接改 `YangRQ-2006/Weixuan` | `values/strings.xml:6`、`SettingsScreen.kt:749` |
| 8 | 删死代码：`ToolChip.kt`、`drawable/ic_kimi_code.xml` | 零引用 |
| 9 | `ChatMessageItem.kt` 17 处圆角字面量 → 统一常量（为 P1 token 铺路） | `ChatMessageItem.kt` |

**验收**：浅色模式设置页主色 = 玄紫（截图取证）；`rg -i "eta" app/src/main` 仅剩无法立即改的内部键名；APK 内无 Google 四色位图。

### P1 · 建微玄 Design System（2–4 天，中风险，**本方案主干**）

| # | 改动 | 说明 |
|---|---|---|
| 1 | 新建 `ui/design/`：`XuanColors.kt`、`XuanType.kt`、`XuanShape.kt`、`XuanSpace.kt`、`XuanMotion.kt` | 单一事实源（§2 的 token 全落地），取代散落的 `Color(0x…)` 与 `.dp` |
| 2 | 新建适配层 `ui/design/components/`：`XuanSection`、`XuanRow`、`XuanCard`、`XuanButton`、`XuanDialog`、`XuanLabel`、`XuanEmptyState`、`XuanIconSlot` | 对外只暴露 Xuan token，内部翻译成 Miuix 参数（**必须薄**：Miuix 为 experimental） |
| 3 | `Eta*` → `Xuan*` 迁移（`EtaCard/EtaControls/EtaPreference*/EtaFeatureCard/EtaSelectionPreferences/EtaDropdownPreference`） | 先加兼容别名，逐页替换，最后删别名 |
| 4 | 设置页范式重构：分组卡片 → 通栏分区 + 墨线；分组标题文言化 + 竖线标记；弱化/去除非必要右箭头 | `EtaPreferenceStyle.kt:56-105`、`SettingsScreen.kt`、各 `*Screen` |
| 5 | 顶栏去玻璃：`TopBarBackdrop` 增加「墨层」模式并设为默认，毛玻璃降级为用户可选 | `TopBarBackdrop.kt`、`AgentAppShell.kt` |
| 6 | 引入 `res/font` 品牌衬线子集 + 覆写 Miuix `textStyles` | `AgentAppTheme.kt`、`res/font/` |
| 7 | 聊天三件套视觉升级（输入槽、chip、工具行、思考行） | `AgentChatInputBar.kt`、`ChatMessageItem.kt`、`AgentChatBody.kt` |

**验收**：新增代码 100% 走 Xuan token；`Eta*` 引用数下降 ≥ 80%；设置页与对话页截图与 ETA 并排可见明显差异；构建通过并装机。

### P2 · 门面屏自绘（3–5 天，较高风险）

| # | 屏 | 做法 |
|---|---|---|
| 1 | 首页空态 | 「玄之门」首屏：衬线大标题 + 题记 + 2×2 建议卡改「符/签」样式（细线 + 印章角标） |
| 2 | 会话页 | 用户气泡 → 「签条」（左 2dp 朱砂/玄紫竖线 + `seal` 圆角）；AI 正文加左侧爻线引导；工具调用行 → 水墨时间线（细线 + 圆点） |
| 3 | 输入区 | 悬浮白卡 → 底部「墨槽」（顶部 1dp 墨线 + 内嵌按钮组）；发送按钮由圆改「方印」 |
| 4 | 会话侧栏 | 分组标题文言化；Dock 六宫格 → 「卦格」（细线分格，去图标底色） |
| 5 | 启动/关于页 | Splash 改水墨晕染（`ic_splash_animated.xml` 已有品牌色，改运动节奏）；关于页题记居中衬线 |
| 6 | 图标系统 | 自绘爻线图标集，替换 `material-icons-extended` 的关键项 |

**验收**：首页 / 设置 / 对话 三屏与 ETA 截图并排对比，视觉语言显著差异化；深浅色双跑一遍。

### P3 · 深化与一致性（持续）

- 空态 / 错误态 / 加载态统一到 `XuanEmptyState`；
- 动效曲线全局收敛（消灭 Miuix 弹簧残留）；
- 对比度与字号缩放可访问性检查（WCAG AA）；
- zh/en 双语文案的语气分层（中文文言、英文克制短句）。

---

## 4. 技术路线与风险

```
① 换皮（已完成）  →  ② 适配层（P1 主干）  →  ③ 门面自绘（P2）
   成本低/效果中       成本中高/效果高          成本高/效果最高
```

- **为什么以 ② 为主干**：Miuix 无 Shapes/Spacing/Motion token，组件默认值只读，唯一可持续的"去 MIUI 骨相"办法是自建 token + 薄 wrapper；且可增量、可回退。
- **③ 只用于门面屏**：全量自绘会丢失 Miuix 的 nav 预测返回、blur、squircle、preference 等能力，并造成混搭割裂。
- **风险 1**：Miuix `0.9.4-rc01` 为候选版且官方声明 experimental，升级可能破坏 wrapper → **对策**：wrapper 保持薄、只传参不改状态；锁版本；升级前先跑截图基线。
- **风险 2**：`AgentAppState.kt`(143KB)、`ChatMessageItem.kt`(118KB)、`SettingsScreen.kt`(45KB) 为巨型文件，改动易冲突 → **对策**：按文件分批提交，每批一个可编译、可截图的原子提交。
- **风险 3**：用户自定义（accentColor/monet/paletteStyle/纯黑/毛玻璃开关）与品牌色板可能互相覆盖 → **对策**：微玄色板仅在 `accentColor == XUAN` 时生效（现状已如此），其余色板保持 Miuix 行为。
- **未验证项**：Miuix 组件内部是否存在硬编码颜色/字号（未能逐一反编译 546 个 class）；squircle 与 folme 的默认启用范围。若 P1 中发现某组件不吃 token，直接列入 P2 自绘清单。

---

## 5. 验证与回归

**构建**（已知可用通道）：`sh /workspace/Eta/dist/eta-build.sh /workspace/Eta assembleDebug`
**装机**：`cp` 到 `/data/local/tmp` → `pm install -r`（Shizuku/Root 通道）
**视觉验收**：浅色/深色各截 6 张（首页空态、会话、设置首页、外观设置、工具页、关于页），与 ETA 原截图并排对比
**回归清单**：启动屏 → 首页 → 会话流式 → 工具调用卡 → 侧栏 → 设置 8 分组 → 供应商/本地模型 → 终端 → 权限页
**残留扫描**：`rg -i "eta|gallery" app/src/main` 应仅剩 DB 文件名、通知渠道 ID 等**不可改的内部标识**

---

## 6. 建议执行顺序（提交粒度）

1. `style(ui): P0 品牌贯通——移除浅色 MIUI 蓝覆写 + 分类色/状态色收敛`
2. `chore: P0 清理 Google 模板图标、模板色、Eta* 类名与死代码`
3. `feat(ui): P1 新增微玄 Design Token 层（XuanColors/Type/Shape/Space/Motion）`
4. `refactor(ui): P1 组件适配层 Xuan* 与 Eta* 迁移（设置页范式重构）`
5. `feat(ui): P1 顶栏墨层 + 品牌衬线与 textStyles 覆写`
6. `feat(ui): P2 首页空态与会话页/输入区自绘`
7. `chore: P2 图标系统与动效统一`

---

## 7. 实施记录（2026-09-30，本轮已落地）

### 7.1 提交

| commit | 内容 |
|---|---|
| `880ea74` | style(ui): P0 品牌贯通——移除浅色 MIUI 蓝覆写 + 分类色/状态色收敛 + 品牌色板 XML 化 |
| `9761335` | chore: P0 清理 Google 模板图标与零引用死代码 |
| `70516c8` | feat(ui): P1 新增微玄设计令牌层与适配层组件（`ui/design`） |
| `3d73ffa` | style(ui): P1+P2 组件 token 化与门面屏改版 |

### 7.2 已落地内容

**P0（品牌贯通与清理）**
- `EtaPreferenceStyle.kt`：删除「非 Monet + 浅色 → primary=`#0080FF`、background=`#F0F1F2`」的上游覆写（品牌色被吃掉的根因）。
- `EtaPreferenceColors` → 微玄五分类色（玄紫/竹青/鎏金/青黛/朱砂，深浅自适应）；旧名 `Blue/Green/Orange/Yellow` 保留为别名，既有 60+ 调用点零改动即变色。
- `StatusColors`：`#00BD13`→`#4E8F72`、`#FFB200`→`#C79A3E`。
- `values/colors.xml`：AS 模板色 → 微玄品牌色板；`styles.xml`/`values-night` 启动屏底 → `@color/xuan_ink`。
- 删除 10 个 Google 四色 `mipmap-*/ic_launcher(_round).png`、`ToolChip.kt`、`ic_kimi_code.xml`。
- `xposed_description` 中英繁三语改写；关于页「源代码」→ `YangRQ-2006/Weixuan`。

**P1（令牌层与组件）**
- 新增 `ui/design/XuanTokens.kt`（Shape/Space/Stroke/Motion/Type/Colors）与 `ui/design/XuanComponents.kt`（Rule/Motto/SectionHeader/Label/Metric）。
- `EtaCard` 圆角 16→14dp；`EtaControls` 按钮 22→14dp、弹窗 22→18dp。
- `EtaPreferenceGroup`：分组卡片 → **通栏纸面 + 上下发丝墨线**；分组标题加玄紫竖签；行高 52→56dp；分割线 0.33dp 透明灰 → 0.5dp 暖灰墨线。
- `TopBarBackdrop`：默认顶栏补底墨线（墨层）。

**P2（门面屏）**
- 首页空态：自绘「玄之门」标记 + 衬线题记；建议卡改 6dp 小圆角「签」。
- 用户气泡 → 「签条」（14dp / 左下 3dp）。
- 输入槽 16→18dp「墨槽」+ 墨线；发送键正圆 → 14dp「方印」。
- 侧栏 Dock 加墨线分界（「卦格」）。

### 7.3 验证证据

- 构建：`sh dist/eta-build.sh /workspace/Eta assembleDebug` → **BUILD SUCCESSFUL**（每阶段各一次，共 4 次；P0 首次失败原因为英文串未转义撇号 `phone's`，已修）。
- 装机：`pm install -r` Success，版本 3.0.5，`lastUpdateTime=2026-09-30 14:40:24`；`dumpsys`/`pidof` 确认进程存活，logcat 无 `FATAL EXCEPTION`。
- **像素级验证**（浅色「留白」主题，主屏 1220×2656 截图）：

| 探针 | 设置页 | 聊天页 | 结论 |
|---|---|---|---|
| `#0080FF`（MIUI 蓝） | 0% | 0% | 上游品牌蓝已彻底清除 |
| `#FF7700` / `#00BD13` / `#FFB200` | 0% | — | 彩色图标墙已消失 |
| `#5B4EC2`（玄紫） | 0.21%（≈`(89,78,187)`） | 0.199% | 玄紫在浅色下真正生效 |
| `#3E7A5E`（竹青） | 0.36%（≈`(77,121,96)`） | — | 分类色生效 |
| `#B08A2E`（鎏金浅色） | 0.13%（≈`(170,139,64)`） | — | 分类色生效 |
| 白色底占比 | **92.74%** | 72.3% | 卡片背景消失 → 通栏纸面成立 |
| `#D8D3C8`（墨线） | 0.51% | 1.54% | 墨线分隔在用 |

### 7.4 本轮未做（含理由）

| 项 | 理由 |
|---|---|
| `EtaApp`/`EtaVoice*` 等类名重命名 | `EtaApp` 同时是 Xposed 模块入口（`XposedServiceHelper.OnServiceListener`），被 15+ 文件与 `res/xml/voice_interaction_service.xml` 引用，重命名有破坏 Xposed/语音服务的风险，且用户不可见 —— 建议单独一轮带回归验证地做。 |
| 品牌衬线字体文件 | 需内嵌 `Noto Serif SC` 子集（+数 MB）；当前已用系统 `FontFamily.Serif` 实现品牌题记，待确认观感后再决定是否内嵌。 |
| 爻线图标系统 | 需成套设计 24dp 矢量图标（顶栏/侧栏/设置分组），工作量大，建议按需分批替换。 |
| 启动页水墨晕染 | 启动屏一闪而过，收益低；现有 `ic_splash_animated` 配色已是品牌色。 |

### 7.5 后续建议（按性价比）

1. **装机目视验收**：重点看设置页通栏节奏、首页空态「玄之门」、输入槽与方印 —— 像素验证只能证明"色对了"，美观度需人眼确认。
2. 深色「玄墨」模式单独走查一遍（本次装机时系统为浅色，深色分支代码路径相同但未截图取证）。
3. 若希望进一步脱离 MIUI 观感：推进 P2.6 图标系统 + 把 `Eta*` 组件逐步换成 `Xuan*`（适配层已就位）。
4. `EtaPreferenceColors` 的 `Blue/Green/Orange/Yellow` 别名在完成调用点迁移后删除。

---

## 8. 第二轮实施记录（2026-09-30，去 ETA 化攻坚）

### 8.1 提交

| commit | 内容 |
|---|---|
| `2e6afb6` | feat(ui): 微玄「爻线」自绘图标系统，替换 Dock/设置页/顶栏/建议卡 Material 图标 |
| `852a193` | feat(ui): 启动页重做为「玄之门 · 水墨」动画（清除 Google 四色与 orbit 旋转） |

### 8.2 启动页：清掉最后一个"第一眼 Google 痕迹"

`ic_splash_animated.xml` 上游是 **Google AI 动效**：四色元素 `green_circle` / `yellow_star` / `red_capsule` / `blue_clover` 在 `orbit` 容器里 135° 旋转 + 缩放弹出，颜色为 `#4285F4` / `#34A853` / `#EA4335` / `#FBBC05`（逐像素吻合 Google 四色）。**这是用户打开 App 第一眼看到的画面，此前从未品牌化**（上一轮资源审计误判为"已完成品牌化"，本轮已纠错）。

重做为「玄之门 · 水墨」：鎏金拱门 `trimPathEnd 0→1`（0–560ms 自下而上写出）→ 玄紫漩涡外环（140–560ms）→ 内环（280–560ms）→ 门心鎏金渐显（620–820ms）→ 两点星火（700–960ms）。全程只有晕染/擦除/渐显，**无旋转、无弹跳**（「玄缓」）。背景沿用 `@color/xuan_ink`（玄墨 `#0D0D12`）。

### 8.3 爻线图标系统（微玄的图形语言）

上游全量使用 `material-icons-extended`（**262 处引用**），Google Material 的圆润实心语义是"还像 ETA/Google"的最后一块硬骨头——**仅换色永远改不掉图形语言**。

新增 `ui/design/XuanGlyphs.kt`：**27 枚自绘矢量图形**（Canvas，24 格坐标系，1.6dp 圆头细线），三条硬规则：

1. **线，不是块**：除门心/星火外不做实心填充；
2. **爻的骨架**：以「三横 / 断横（阴爻）/ 竖线 / 圆」为基本笔画 —— 例如**「设置」直接是一枚爻（阳·阴·阳），不再画齿轮**；
3. **留白呼吸**：笔画内缩 ≥ 2.5 格，该开口的地方就开口。

| 图形 | 语义 | 图形 | 语义 | 图形 | 语义 |
|---|---|---|---|---|---|
| `Gate` | 玄之门（拱+环+心） | `Settings` | 一枚爻 | `Model` | 枢（环+心+引线） |
| `Tools` | 斜杆+环 | `Skills` | 三横+右竖 | `Permission` | 门+门内点 |
| `Character` | 双圆（阴阳） | `Terminal` | 折角符+底线 | `Browser` | 圆+横弦 |
| `Memory` | 纸页+两行字 | `Mcp` | 三节点连线 | `History` | 圆+指针 |
| `More` | 三点竖排 | `Plus` / `Close` | 十字 / 叉 | `Check` | 勾 |
| `Send` | 上行箭头 | `Stop` | 方 | `Search` | 环+斜柄 |
| `Refresh` | 近全弧+引线 | `Download` | 下箭头+底线 | `Delete` | 两竖+上横 |
| `Play` | 三角 | `Globe` | 圆+经线 | `Folder` | 折页框 |

**接入点**（保留语义不明确的 Material 图标，逐批替换）：
- **侧栏 Dock 六格**：设置=爻、模型=枢、工具、技能、权限=门、角色=阴阳；
- **设置页 17 处分组图标**（Python 脚本按语义映射批量替换，`Palette` 无对应保留 Material）；
- **首页顶栏 5 处**（会话历史=History、更多=More、新建=Plus、终端、浏览器=Globe）；
- **首页空态建议卡 4 处**（`SuggestionItem.icon: ImageVector` → `glyph: XuanGlyphType`）。
- 新增 `EtaPreferenceIcon(glyph: XuanGlyphType, …)` 重载，使设置页组件可逐步换图形而不改调用结构。

### 8.4 验证

- 构建：`assembleDebug` → **BUILD SUCCESSFUL**（两次，3m58s / 3m57s；中途两次编译错误已修：`XuanGlyphType` 缺 import、`MiuixTheme` 缺 import；一次 XML 解析错误：**XML 注释里不能出现 `--`**）。
- 装机：`pm install -r` Success；`am start` 后 `pidof` 确认进程存活（pid 7096），logcat **无 FATAL EXCEPTION**。
- ⚠️ **本轮截图取证未能完成**：设备处于锁屏 + Dozing（`isKeyguardShowing=true`），主屏截图全黑（1220×2656 纯 `(0,0,0)`），且设备有密码锁，无法在不打扰用户的前提下解锁。已尝试 `KEYCODE_WAKEUP` / `KEYCODE_MENU` / 上滑 / `wm dismiss-keyguard` 均停留在锁屏。
  → **请唤醒手机后打开微玄自查**：① 冷启动看「玄之门」水墨动画；② 侧栏 Dock 六格是否为爻线图形；③ 设置页分组图标是否已由彩色 Material 变为单色爻线；④ 首页顶栏三枚图标。

### 8.5 后续可继续项

1. **其他二级页图标**：`LocalModelScreen` / `AgentSkillsScreen` / `WorkspaceScreen` / `ToolCard` / `PermissionHealthScreen` 等仍用 Material 图标（`EtaPreferenceIcon(glyph=…)` 与 `XuanGlyph` 已就绪，可逐页替换）。
2. **信息架构文言化**：设置页分组标题（模型供应商 / 工具 / 通用 / 权限 / 关于）可改为微玄四字签（枢机 / 器用 / 行止 / 护持 / 玄迹），需同步中英繁三语并评估可用性。
3. **`Eta*` 组件与 `EtaApp` 类名**迁移（适配层已就位；`EtaApp` 为 Xposed 入口，需单独回归）。
4. 品牌衬线字体内嵌、动效曲线全局收敛（`XuanMotion` 已定义但尚未替换 Miuix 弹簧）。


