# 微玄图标库选型调研（静态底座 + 动态图标）

> 采集时间：2026-10-04（UTC）。数据源：GitHub REST API（star / license / pushed_at）、repo1.maven.org（Gradle 坐标可用性实测）、raw.githubusercontent.com（README / SVG 头部）。
> 凡未能用命令实测的数值，一律标注「未核实」。GitHub HTML 页在沙箱内不可达，star 数均取自官方 API。

---

## 一、现状诊断（实测证据）

| 项 | 实测结果 | 来源 |
|---|---|---|
| 自绘「爻线」 | `ui/design/XuanGlyphs.kt` 308 行，47 枚 `XuanGlyphType` | 源码 |
| 自绘绘制方式 | `DrawScope.drawGlyph` 内**只有 `drawLine` 直线原语**（`l()` 辅助函数），无曲线/圆弧 | 源码 L58 起 |
| 规范声明 | 24 格画布 / 2.0dp 圆头细线 / 笔画 4–20 格 | 源码注释 |
| 实际调用尺寸 | 14dp(`SectionIconSize`) / 16dp(`SendIconSize`) / 20dp(`ActionIconSize`) / 21dp(`ThinkingIconSize`) / 22dp(`TopBarMenuIconSize`) / 24dp(`DockEntryIconSize`, `ChatInputActionIconSize`) | 各调用点 |
| 爻线使用面 | 84 处调用，涉及多文件 | grep |
| 仍用 Material 图标 | **207 处** `androidx.compose.material.icons` 引用 | grep |
| 已有动态图标引擎 | `ui/design/morph/` **1929 行**，移植自 `guillermolg00/morphicons`；`XuanMorphGlyphs.kt` 仅 86 行 / 12 枚图形，仅 3 个文件在用 | 源码 |

**结论：不完全是「库不好看」，而是三条硬伤叠加**
1. **几何质量**：47 枚图形全部由直线段拼成，没有曲线与圆弧——这是「有风格但没设计感」的第一位原因。成熟图标库（Tabler/Lucide/Phosphor）都大量使用 `C`(三次贝塞尔) 与圆弧来收口。
2. **光学重量失配（此处 2026-10-04 已订正）**：爻线自绘路径传给 `drawLine` 的是**未经缩放的 `strokeWidth.toPx()`**，即线宽在任意尺寸下恒为 2dp；而坐标按 `size.minDimension / 24` 缩放。结果 16dp 渲染时图形只占约 10.7dp 宽、线宽却仍是 2dp —— 真实症状是**图形内缩、线显粗、四边留白过大**，而**不是**「线条发虚」（"2.0dp 在 16dp 只剩 1.33dp" 是 **Tabler 这类等比缩放图标库**的行为，也正是 `TablerPaths` 要做描边补偿的原因）。
3. **三套图形语言混排**：Material 207 处 + 自绘爻线 84 处 + Miuix 自带图标，同一屏可能三套端点/圆角/留白规范，视觉直接散架。

---

## 二、静态图标库实测对比

| 库 | 仓库 | Stars | License | 图标数 | 网格·描边 | 线宽可调 | Compose 产物 |
|---|---|---|---|---|---|---|---|
| **Tabler Icons** | `tabler/tabler-icons` | **21,893** | **MIT** | **6,200+**（官方） | 24×24 / `sw 2` / round | ✅ | ✅ `com.composables:icons-tabler-outline-android:2.2.1`（Maven 实测 200）；`dev.seyfarth:tabler-icons-kmp`（Maven 实测 200） |
| **Lucide** | `lucide-icons/lucide` | **24,842** | ISC（官网）/ API 报 NOASSERTION | 2,130（npm 实测） | 24×24 / `sw 2` / round | ✅ | ✅ `com.composables:icons-lucide-android:2.2.1`（Maven 实测 200） |
| **Phosphor** | `phosphor-icons/core` | 384（主页仓库 7,591） | **MIT** | 1,512（tarball 实测）× 6 字重 | 24×24 / 字重决定线宽 | ✅ 6 字重 + Duotone | ⚠️ `com.composables:icons-phosphor-cmp` **404 不存在**（实测） |
| **Iconoir** | `iconoir-icons/iconoir` | 4,567 | MIT | 1,383+ | 24×24 / `sw 1.5` | ✅ | ❌ 仅社区库 |
| **Solar** | `saoudi-h/solar-icons` | 179 | MIT + **部分 CC BY 4.0** | 1,451 / 8,706 变体 | 24×24 / ~1.5 | ✅ 6 风格 + Duotone | ❌ |
| **MingCute** | `mingcute-design/mingcute-icons` | 1,648 | **Apache-2.0** | 1,663 + Filled | 24×24（未逐文件核实） | ✖ | ❌ |
| **Remix Icon** | `Remix-Design/RemixIcon` | 8,396 | **自定义许可（API 报 NOASSERTION）** | 3,231 | 24×24 Line/Fill | ✖ | ❌ |
| **Material Symbols** | `google/material-design-icons` | **54,070** | Apache-2.0 | 3,000+（未精确核实） | 24px 可变字体 | ✅ weight/fill/grade 轴 | ✅ `icons-material-symbols-rounded-cmp:2.2.1`（实测 200） |
| **Hugeicons** | `hugeicons/hugeicons` | 1,201 | MIT（免费 6,000+） | Pro 6 万+ 需付费 | 24×24 / 1.5 | ✅ | ❌ |
| **Bootstrap** | `twbs/icons` | 8,138 | MIT | 2,078 | **16×16 为主** | ✖ | ✅ |
| **Feather** | `feathericons/feather` | 26,005 | MIT | **287，已停更** | 24×24 / 2 | ✅ | ✅ |

**Compose 一站式集合**
- `composablehorizons/compose-icons`：320★ MIT，17,000+ 图标（含 Tabler / Lucide / Material Symbols / Bootstrap / Feather / Heroicons 等 17 包），坐标 `com.composables:icons-<pack>-{android,cmp}:2.2.1`。**推荐入口。**
- `DevSrSouza/compose-icons`：856★ MIT，但最后推送 2024-09-15，**已一年多未更新**。

---

## 三、动态/动画图标：实测事实（这是本次最关键的一条）

**结论：Android/Compose 生态里，「开箱即用 + 许可干净 + 风格可用」的动画图标库基本是空白。** 现实只有三条技术路线：

| 路线 | 代表项目 | Stars | License | 评价 |
|---|---|---|---|---|
| **① 路径变形 Morph（纯 Compose，零体积）** | `guillermolg00/morphicons` | **2,784** | **MIT** | 2026-09-26 仍在更新（API 实测）。**微玄已在用**（`ui/design/morph/` 1929 行移植版）。任意描边图标可变形为另一枚，弹簧过渡——最契合「爻线→爻线」的品牌语言 |
| | `lisonge/morph-compose` | 1 | Apache-2.0 | KMP morph 库，2026-09-22 更新，但仅 1★，弃用风险高 |
| **② Lottie 引擎 + 图标包** | `airbnb/lottie-android` | **35,735** | **Apache-2.0** | 引擎事实标准，`lottie-compose` 最新 **6.7.1**（Maven 实测） |
| | `useAnimations/react-useanimations` | 1,247 | NOASSERTION | 图标集是 Lottie JSON，可取出复用；**许可需复核** |
| | `icons8/titanic` | 2,433 | **无 LICENSE 文件** | 2,400+ 动画图标，**许可不明 → 不可直接商用** |
| | Lordicon（官网） | — | 商业产品 | GitHub org **404 不存在**（实测）→ 非纯开源；免费层需署名，商用需授权 |
| **③ Native AVD / SVG 动画** | `cyberalien/line-md`（Material Line Icons） | 375 | **MIT** | Iconify 作者出品，**纯 SVG 动画、无需 JS**，理念是「rendering animation：图形逐笔绘出」，作者明确反对循环 bounce 动效（省电、不抢焦点）——**与「描边生长」的克制微动效诉求高度一致**。提供 `svg`(SMIL) / `svg-style`(CSS) / `svg-frames-120fps` 逐帧四种格式 |
| | `andremion/Android-Animated-Icons` | 117 | Apache-2.0 | 示例工程，非库 |
| | `alexjlockwood/adp-delightful-details` | 1,082 | MIT | 示例工程（图标动画教学） |
| | `tarek360/Animated-Icons` | 226 | **无许可** | 2015 年代老库，勿用 |

> 注意：`Android-Animated-Icons` / `adp-delightful-details` 是**教学示例**，`line-md` 是 **SVG 资源集**（不是 Android 库），二者都不能直接当依赖用。

---

## 四、推荐方案

### 方案 A｜稳（首选落地）
- **静态底座 = Tabler outline**（MIT / 6,200+ / 24×24·2px·round），坐标 `com.composables:icons-tabler-outline-android:2.2.1`。
- **品牌符号 = 自绘爻线，收窄到 6–8 枚**（Gate / Model / Skills / Mcp / Character / Memory …），其余全部让位给 Tabler。
- **动态 = 复用现有 morph 引擎**（不引入 Lottie），只用于状态切换：发送↔停止、播放↔暂停、展开↔收起、思考中。
- 代价：新增依赖约几十 KB 级（ImageVector 代码），无运行时引擎。

### 方案 B｜雅（水墨鎏金，最符「微玄」气质）
- **静态底座 = Phosphor**，用 **Light/Regular/Thin 字重**表达「淡水墨淡描」，强调项用 **Duotone** 做「玄紫线身 + 鎏金副层（24–30% 透明）」。
- 风险：`icons-phosphor-cmp` 坐标不存在（实测 404），需自行 SVG→ImageVector 转换或改走 Material Symbols 可变字体路线。

### 方案 C｜最强动效（不推荐作主选）
- Lottie 引擎（+~1MB APK）+ 精选图标。问题：① 许可干净的 Lottie 图标集稀缺（titanic 无许可 / Lordicon 商业 / useAnimations 待复核）；② 风格是欧美 web 味，与道家水墨气质冲突；③ 循环动画耗电，作者生态也在反思（见 line-md 的论述）。

### 无论选哪个方案，必须先定的「统一红线」
- 画布 24×24，keyline 22×22，四边 1dp 光学边距；`stroke-linecap=round` + `stroke-linejoin=round`。
- 描边只允许一个档位：**2.0dp（劲挺，对齐 Tabler）** 或 1.5dp（秀逸）。**禁止按尺寸各自为政**——若必须适配小尺寸，用「16–18dp→1.5 / 20–24dp→2.0 / 28dp+→2.5」的换算表，而不是全部沿用 2dp。
- 同一屏内**线型底座只能有一个库**，Duotone/强调库最多再叠一个，否则端点与圆角差异立刻暴露。
- 爻线补齐曲线：单个爻线可以只用直线（符合周易意象），但**外轮廓（门/云/水/器）必须引入三次贝塞尔**，否则永远「像示意图」。

---

## 五、集成路径（方案 A，可执行）

1. `app/build.gradle.kts` 增依赖：`implementation("com.composables:icons-tabler-outline-android:2.2.1")`（版本已在 Maven Central 实测）。
2. 新建 `ui/design/XuanIconResolver.kt`：把 `XuanGlyphType` 与 Tabler / Material Symbols 图标做映射，保留 `XuanGlyph(type, size, tint)` 旧签名，内部改派发到新库 → **84 处调用点零改动**。
3. 爻线白名单收紧到 6–8 枚（品牌位），其余图标改 Tabler。
4. 用 `ui/design/morph/` 引擎接管状态切换动效；爻线补齐贝塞尔收口。
5. 清理剩余 207 处 `androidx.compose.material.icons`（material-icons-extended 体积大户，可显著瘦身 APK）。
6. 验收：真机截图对比同屏图标光学重量；`./gradlew assembleRelease` 通过 + APK 体积变化记录。

---

## 六、未核实 / 风险边界

- Lucide：官网声明 ISC，但 GitHub API 返回 `NOASSERTION`（LICENSE 文件为 ISC 全文，检测器未归类）——商用前建议人工看一眼 `LICENSE` 原文。
- Remix Icon：自定义许可 v1.0，API 报 NOASSERTION，条款有更新历史 → 不建议作主底座。
- Solar：含 CC BY 4.0 第三方图标，商用需逐枚核对。
- MingCute / Remix / Hugeicons / Solar 的**逐 SVG 网格与描边数值未逐文件核实**。
- Material Symbols 图标总数（约 3,000+）未精确核实（`fonts.google.com/metadata/icons` 在本沙箱不可达）。
- 各库「APK 体积增量」为经验判断，未实测；需在选定后实测。
