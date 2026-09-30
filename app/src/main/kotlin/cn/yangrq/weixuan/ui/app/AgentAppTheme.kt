package cn.yangrq.weixuan.ui.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import cn.yangrq.weixuan.data.model.AppearanceAccentColor
import cn.yangrq.weixuan.data.model.AppearancePaletteStyle
import cn.yangrq.weixuan.data.model.AppearanceSettings
import cn.yangrq.weixuan.data.model.AppearanceThemeMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.platformDynamicColors

@Composable
fun AgentAppTheme(
    appearance: AppearanceSettings,
    applyInterfaceScale: Boolean,
    onResolvedDarkModeChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (appearance.themeMode) {
        AppearanceThemeMode.SYSTEM -> systemDark
        AppearanceThemeMode.LIGHT -> false
        AppearanceThemeMode.DARK -> true
    }
    val colorSchemeMode = when {
        !appearance.monetEnabled && appearance.themeMode == AppearanceThemeMode.LIGHT -> ColorSchemeMode.Light
        !appearance.monetEnabled && appearance.themeMode == AppearanceThemeMode.DARK -> ColorSchemeMode.Dark
        !appearance.monetEnabled -> ColorSchemeMode.System
        appearance.themeMode == AppearanceThemeMode.LIGHT -> ColorSchemeMode.MonetLight
        appearance.themeMode == AppearanceThemeMode.DARK -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.MonetSystem
    }
    val systemSeedColor = if (
        appearance.monetEnabled && appearance.accentColor == AppearanceAccentColor.SYSTEM
    ) {
        platformDynamicColors(isDark).primary
    } else {
        null
    }
    // 微玄：非 Monet 模式下，显式选中的品牌色（默认玄紫）同样要驱动调色板；
    // 仅「跟随系统」时才回落 miuix 默认调色板。
    val keyColor = when {
        !appearance.monetEnabled && appearance.accentColor == AppearanceAccentColor.SYSTEM -> null
        appearance.accentColor == AppearanceAccentColor.SYSTEM -> systemSeedColor
        else -> appearance.accentColor.seedColor()
    }
    val controller = remember(appearance, colorSchemeMode, keyColor, isDark) {
        ThemeController(
            colorSchemeMode = colorSchemeMode,
            keyColor = keyColor,
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = appearance.paletteStyle.toMiuixPaletteStyle(),
            isDark = isDark,
        )
    }
    val colors = controller.currentColors()
    val themedColors = remember(colors, isDark, appearance.monetEnabled, appearance.pureBlackEnabled, appearance.accentColor) {
        val base = if (appearance.monetEnabled && appearance.pureBlackEnabled && isDark) {
            colors.copy(
                background = Color.Black,
                surface = Color.Black,
            )
        } else {
            colors
        }
        // 微玄品牌色板（2026-09-28 重做）：
        //   深色 = 「玄墨」——墨黑底 + 竹青/流金点缀，静雅书卷气；
        //   浅色 = 「留白」——宣纸底 + 暖灰细线，干净克制。
        // 仅在选中品牌色 XUAN 时生效（其余主题色沿用 miuix 原色板），
        // 全部为 ColorScheme 定点覆写，不改动任何 miuix 组件实现。
        if (appearance.accentColor != AppearanceAccentColor.XUAN) {
            base
        } else if (isDark) {
            base.copy(
                // —— 玄墨：底 ——
                background = Color(0xFF0D0D12),
                onBackground = Color(0xFFECEAF2),
                surface = Color(0xFF14141A),
                onSurface = Color(0xFFECEAF2),
                surfaceContainer = Color(0xFF191920),
                surfaceContainerHigh = Color(0xFF1E1E27),
                surfaceContainerHighest = Color(0xFF24242E),
                surfaceVariant = Color(0xFF2A2A35),
                // —— 玄紫（深色下提亮以保证对比度）——
                primary = Color(0xFF9B8CFF),
                onPrimary = Color(0xFF1A1233),
                primaryContainer = Color(0xFF2C2450),
                onPrimaryContainer = Color(0xFFE5DEFF),
                // —— 竹青：次级强调 ——
                secondary = Color(0xFF7FB3A0),
                onSecondary = Color(0xFF0E1F1A),
                secondaryContainer = Color(0xFF1C3A31),
                onSecondaryContainer = Color(0xFFBFE6D8),
                // —— 描边：极暗，保持"墨"的克制 ——
                outline = Color(0xFF3A3A47),
            )
        } else {
            base.copy(
                // —— 留白：底（宣纸）——
                background = Color(0xFFFAF8F4),
                onBackground = Color(0xFF1C1B20),
                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF1C1B20),
                surfaceContainer = Color(0xFFF6F4EF),
                surfaceContainerHigh = Color(0xFFF1EEE8),
                surfaceContainerHighest = Color(0xFFEBE8E1),
                surfaceVariant = Color(0xFFF2EFE9),
                // —— 玄紫 ——
                primary = Color(0xFF5B4EC2),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFE9E4FF),
                onPrimaryContainer = Color(0xFF1F1746),
                // —— 竹青（浅色下加深）——
                secondary = Color(0xFF3E7A5E),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFD3EBDD),
                onSecondaryContainer = Color(0xFF0F2A1E),
                // —— 描边：暖灰细线，替代冷灰 ——
                outline = Color(0xFFD8D3C8),
            )
        }
    }

    LaunchedEffect(isDark) { onResolvedDarkModeChange(isDark) }

    MiuixTheme(colors = themedColors) {
        val platformDensity = LocalDensity.current
        val appDensity = remember(platformDensity, appearance.interfaceScale, applyInterfaceScale) {
            if (applyInterfaceScale) {
                Density(
                    density = platformDensity.density * appearance.interfaceScale,
                    fontScale = platformDensity.fontScale,
                )
            } else {
                platformDensity
            }
        }
        val miuixColors = MiuixTheme.colorScheme
        val materialColors = if (isDark) {
            darkColorScheme(
                primary = miuixColors.primary,
                onPrimary = miuixColors.onPrimary,
                primaryContainer = miuixColors.primaryContainer,
                onPrimaryContainer = miuixColors.onPrimaryContainer,
                secondary = miuixColors.secondary,
                onSecondary = miuixColors.onSecondary,
                secondaryContainer = miuixColors.secondaryContainer,
                onSecondaryContainer = miuixColors.onSecondaryContainer,
                background = miuixColors.background,
                onBackground = miuixColors.onBackground,
                surface = miuixColors.surface,
                onSurface = miuixColors.onSurface,
                surfaceVariant = miuixColors.surfaceVariant,
                onSurfaceVariant = miuixColors.onSurfaceSecondary,
                error = miuixColors.error,
                onError = miuixColors.onError,
                errorContainer = miuixColors.errorContainer,
                onErrorContainer = miuixColors.onErrorContainer,
                outline = miuixColors.outline,
            )
        } else {
            lightColorScheme(
                primary = miuixColors.primary,
                onPrimary = miuixColors.onPrimary,
                primaryContainer = miuixColors.primaryContainer,
                onPrimaryContainer = miuixColors.onPrimaryContainer,
                secondary = miuixColors.secondary,
                onSecondary = miuixColors.onSecondary,
                secondaryContainer = miuixColors.secondaryContainer,
                onSecondaryContainer = miuixColors.onSecondaryContainer,
                background = miuixColors.background,
                onBackground = miuixColors.onBackground,
                surface = miuixColors.surface,
                onSurface = miuixColors.onSurface,
                surfaceVariant = miuixColors.surfaceVariant,
                onSurfaceVariant = miuixColors.onSurfaceSecondary,
                error = miuixColors.error,
                onError = miuixColors.onError,
                errorContainer = miuixColors.errorContainer,
                onErrorContainer = miuixColors.onErrorContainer,
                outline = miuixColors.outline,
            )
        }

        CompositionLocalProvider(
            LocalAppearanceSettings provides appearance,
            LocalBlurEnabled provides appearance.blurEnabled,
            LocalTopBarBlurStyle provides appearance.topBarBlurStyle,
            LocalPlatformDensity provides platformDensity,
            LocalDensity provides appDensity,
        ) {
            // MaterialTheme 仅向 markdown-renderer-m3 提供与 Miuix 一致的颜色上下文。
            MaterialTheme(
                colorScheme = materialColors,
                content = content,
            )
        }
    }
}

private fun AppearancePaletteStyle.toMiuixPaletteStyle(): ThemePaletteStyle = when (this) {
    AppearancePaletteStyle.TONAL_SPOT -> ThemePaletteStyle.TonalSpot
    AppearancePaletteStyle.NEUTRAL -> ThemePaletteStyle.Neutral
    AppearancePaletteStyle.VIBRANT -> ThemePaletteStyle.Vibrant
    AppearancePaletteStyle.EXPRESSIVE -> ThemePaletteStyle.Expressive
    AppearancePaletteStyle.RAINBOW -> ThemePaletteStyle.Rainbow
    AppearancePaletteStyle.FRUIT_SALAD -> ThemePaletteStyle.FruitSalad
    AppearancePaletteStyle.MONOCHROME -> ThemePaletteStyle.Monochrome
    AppearancePaletteStyle.FIDELITY -> ThemePaletteStyle.Fidelity
    AppearancePaletteStyle.CONTENT -> ThemePaletteStyle.Content
}

private fun AppearanceAccentColor.seedColor(): Color = when (this) {
    AppearanceAccentColor.XUAN -> Color(0xFF5B4EC2)
    AppearanceAccentColor.SYSTEM, AppearanceAccentColor.BLUE -> Color(0xFF3482FF)
    AppearanceAccentColor.PURPLE -> Color(0xFF6750A4)
    AppearanceAccentColor.PINK -> Color(0xFFB0006D)
    AppearanceAccentColor.RED -> Color(0xFFBA1A1A)
    AppearanceAccentColor.ORANGE -> Color(0xFFB65D00)
    AppearanceAccentColor.YELLOW -> Color(0xFF7D5700)
    AppearanceAccentColor.GREEN -> Color(0xFF006D3B)
    AppearanceAccentColor.TEAL -> Color(0xFF006A6A)
}
