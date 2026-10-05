package cn.yangrq.weixuan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.yangrq.weixuan.ui.components.EtaPreferenceGroupTitle
import cn.yangrq.weixuan.ui.components.MiuixScaffoldPage
import cn.yangrq.weixuan.ui.design.TablerPaths
import cn.yangrq.weixuan.ui.design.XuanGlyph
import cn.yangrq.weixuan.ui.design.XuanGlyphLegacy
import cn.yangrq.weixuan.ui.design.XuanGlyphType
import cn.yangrq.weixuan.ui.design.drawTablerGlyph
import cn.yangrq.weixuan.ui.design.tabler.TablerGlyph
import cn.yangrq.weixuan.ui.design.tablerGlyph
import kotlin.math.abs
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 尺寸阶梯：覆盖微玄实际的 14 / 16 / 20 / 24 / 28dp 五档调用尺寸。 */
private val PreviewSizes = listOf(14.dp, 16.dp, 20.dp, 24.dp, 28.dp)

/**
 * 图标系统对比预览（设计验收页）。
 *
 * 三列含义：**爻线自绘（旧） / Tabler 底座（新） / 生产路径实际输出**。
 * 第三列走 [XuanGlyph] 的真实分发逻辑，用来验证「42 枚已切 Tabler、6 枚仍为爻线」。
 */
@Composable
internal fun IconPreviewScreen(onBack: () -> Unit) {
    val tint = MiuixTheme.colorScheme.onBackground
    var boost by remember { mutableFloatStateOf(TablerPaths.MaxOpticalBoost) }

    MiuixScaffoldPage(title = "图标对比预览", onBack = onBack) {
        item(key = "preview_hint") {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = "爻线自绘（旧）　·　Tabler 底座（新）　·　生产路径实际",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tint,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "底座：Tabler Icons 42 枚 / 125 条路径（MIT License）；" +
                        "余 6 枚为微玄品牌爻线。",
                    style = MaterialTheme.typography.bodySmall,
                    color = tint.copy(alpha = 0.6f),
                )
            }
        }

        item(key = "preview_boost") {
            EtaPreferenceGroupTitle("描边补偿强度")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BoostChip("纯等比 1.00", 1.0f, boost, tint) { boost = it }
                BoostChip("轻度 1.15", 1.15f, boost, tint) { boost = it }
                BoostChip("默认 1.25", 1.25f, boost, tint) { boost = it }
            }
        }

        item(key = "preview_ladder") {
            EtaPreferenceGroupTitle("尺寸阶梯（Send）")
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                PreviewSizes.forEach { size ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${size.value.toInt()}dp",
                            style = MaterialTheme.typography.labelSmall,
                            color = tint.copy(alpha = 0.6f),
                            modifier = Modifier.width(44.dp),
                        )
                        XuanGlyphLegacy(type = XuanGlyphType.Send, size = size, tint = tint)
                        Spacer(modifier = Modifier.width(20.dp))
                        val glyph = XuanGlyphType.Send.tablerGlyph
                        if (glyph == null) {
                            XuanGlyphLegacy(type = XuanGlyphType.Send, size = size, tint = tint)
                        } else {
                            TablerPreviewIcon(glyph, size, boost, tint)
                        }
                        Spacer(modifier = Modifier.width(20.dp))
                        XuanGlyph(type = XuanGlyphType.Send, size = size, tint = tint)
                    }
                }
            }
        }

        item(key = "preview_matrix_title") {
            EtaPreferenceGroupTitle("全量对比（${XuanGlyphType.entries.size} 枚）")
        }

        XuanGlyphType.entries.forEach { type ->
            item(key = "preview_row_${type.name}") {
                val glyph = type.tablerGlyph
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    XuanGlyphLegacy(type = type, size = 24.dp, tint = tint)
                    Spacer(modifier = Modifier.width(20.dp))
                    if (glyph == null) {
                        XuanGlyphLegacy(type = type, size = 24.dp, tint = tint)
                    } else {
                        TablerPreviewIcon(glyph, 24.dp, boost, tint)
                    }
                    Spacer(modifier = Modifier.width(20.dp))
                    XuanGlyph(type = type, size = 24.dp, tint = tint)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = if (glyph == null) {
                            "${type.name} · 品牌爻线（保留）"
                        } else {
                            "${type.name} · ${glyph.name}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = tint.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/** 直接走低层渲染，便于预览不同补偿强度（生产路径用 [XuanGlyph] 的默认强度）。 */
@Composable
private fun TablerPreviewIcon(glyph: TablerGlyph, size: Dp, boost: Float, tint: Color) {
    Canvas(modifier = Modifier.size(size)) {
        drawTablerGlyph(
            glyph = glyph,
            color = tint,
            strokeWidth24 = TablerPaths.strokeFor(
                renderDp = this.size.minDimension / density,
                boost = boost,
            ),
        )
    }
}

@Composable
private fun BoostChip(
    label: String,
    value: Float,
    selected: Float,
    tint: Color,
    onSelect: (Float) -> Unit,
) {
    val active = abs(selected - value) < 0.001f
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (active) tint else tint.copy(alpha = 0.55f),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) tint.copy(alpha = 0.12f) else Color.Transparent)
            .clickable { onSelect(value) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
