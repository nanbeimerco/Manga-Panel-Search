package com.mangapanel.search.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

enum class AppThemeMode(val id: String, val displayName: String) {
    SYSTEM("system", "システム連動 (自動)"),
    DARK("dark", "ダークテーマ"),
    LIGHT("light", "ライトテーマ");

    companion object {
        fun fromId(id: String): AppThemeMode = entries.find { it.id == id } ?: SYSTEM
    }
}

fun createMangaColorScheme(preset: ColorPresetSpec, isDark: Boolean): ColorScheme {
    return if (isDark) {
        darkColorScheme(
            primary = preset.darkPrimary,
            onPrimary = preset.darkOnPrimary,
            primaryContainer = preset.darkPrimaryContainer,
            onPrimaryContainer = preset.darkOnPrimaryContainer,
            secondary = preset.darkSecondary,
            onSecondary = preset.darkOnSecondary,
            secondaryContainer = preset.darkSecondaryContainer,
            onSecondaryContainer = preset.darkOnSecondaryContainer,
            tertiary = preset.darkTertiary,
            onTertiary = preset.darkOnTertiary,
            tertiaryContainer = preset.darkTertiaryContainer,
            onTertiaryContainer = preset.darkOnTertiaryContainer,
            background = DarkBackground,
            onBackground = DarkOnBackground,
            surface = DarkSurface,
            onSurface = DarkOnSurface,
            surfaceVariant = DarkSurfaceVariant,
            onSurfaceVariant = DarkOnSurfaceVariant,
            surfaceContainerLowest = DarkSurfaceContainerLowest,
            surfaceContainerLow = DarkSurfaceContainerLow,
            surfaceContainer = DarkSurfaceContainer,
            surfaceContainerHigh = DarkSurfaceContainerHigh,
            surfaceContainerHighest = DarkSurfaceContainerHighest,
            outline = DarkOutline,
            outlineVariant = DarkOutlineVariant,
        )
    } else {
        lightColorScheme(
            primary = preset.lightPrimary,
            onPrimary = preset.lightOnPrimary,
            primaryContainer = preset.lightPrimaryContainer,
            onPrimaryContainer = preset.lightOnPrimaryContainer,
            secondary = preset.lightSecondary,
            onSecondary = preset.lightOnSecondary,
            secondaryContainer = preset.lightSecondaryContainer,
            onSecondaryContainer = preset.lightOnSecondaryContainer,
            tertiary = preset.lightTertiary,
            onTertiary = preset.lightOnTertiary,
            tertiaryContainer = preset.lightTertiaryContainer,
            onTertiaryContainer = preset.lightOnTertiaryContainer,
            background = LightBackground,
            onBackground = LightOnBackground,
            surface = LightSurface,
            onSurface = LightOnSurface,
            surfaceVariant = LightSurfaceVariant,
            onSurfaceVariant = LightOnSurfaceVariant,
            surfaceContainerLowest = LightSurfaceContainerLowest,
            surfaceContainerLow = LightSurfaceContainerLow,
            surfaceContainer = LightSurfaceContainer,
            surfaceContainerHigh = LightSurfaceContainerHigh,
            surfaceContainerHighest = LightSurfaceContainerHighest,
            outline = LightOutline,
            outlineVariant = LightOutlineVariant,
        )
    }
}

@Composable
fun MangaPanelSearchTheme(
    preset: ColorPresetSpec = ColorPresets.TITANIUM_SLATE,
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> systemInDark
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> createMangaColorScheme(preset, isDark)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
