package cn.yangrq.weixuan.ui.design

import cn.yangrq.weixuan.ui.design.tabler.TablerGlyph

/**
 * 微玄「爻线」保留集 —— 六爻。
 *
 * 爻线语言擅长表达**抽象概念**（门、记忆、人格、技能），
 * 不擅长表达**具象物件**（垃圾桶、键盘、麦克风、地图针）—— 强行自绘就会显得单薄又没有设计感。
 * 因此只把 6 枚抽象品牌符号留给自绘，其余 42 枚统一交给 Tabler 底座。
 */
internal val XuanBrandGlyphs: Set<XuanGlyphType> = setOf(
    XuanGlyphType.Gate, // 门 · 启动入口
    XuanGlyphType.Model, // 模型 · 端侧推理
    XuanGlyphType.Skills, // 技能
    XuanGlyphType.Memory, // 记忆
    XuanGlyphType.Mcp, // MCP 扩展
    XuanGlyphType.Character, // 人格
)

/**
 * 生成的 [TablerGlyph] 枚举成员与 [XuanGlyphType] 同名，
 * 因此按名字接线，避免再维护第二份容易写错的手工映射表。
 */
private val tablerByName: Map<String, TablerGlyph> = TablerGlyph.entries.associateBy { it.name }

/**
 * 该微玄图形对应的 Tabler 图标。
 *
 * 返回 `null` 表示属于品牌保留集，应由自绘爻线渲染。
 */
internal val XuanGlyphType.tablerGlyph: TablerGlyph?
    get() = if (this in XuanBrandGlyphs) null else tablerByName[name]
