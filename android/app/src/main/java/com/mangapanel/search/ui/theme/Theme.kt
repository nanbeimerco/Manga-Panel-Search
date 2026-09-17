package com.mangapanel.search.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

fun createMangaColorScheme(preset: ColorPresetSpec): ColorScheme {
    return darkColorScheme(
        primary = preset.primary,
        onPrimary = preset.onPrimary,
        primaryContainer = preset.primaryContainer,
        onPrimaryContainer = preset.onPrimaryContainer,
        secondary = preset.secondary,
        onSecondary = preset.onSecondary,
        secondaryContainer = preset.secondaryContainer,
        onSecondaryContainer = preset.onSecondaryContainer,
        tertiary = preset.tertiary,
        onTertiary = preset.onTertiary,
        tertiaryContainer = preset.tertiaryContainer,
        onTertiaryContainer = preset.onTertiaryContainer,
        background = DarkBackground,
        onBackground = DarkOnBackground,
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,
        surfaceContainer = DarkSurfaceContainer,
        surfaceContainerHigh = DarkSurfaceContainerHigh,
        surfaceContainerHighest = DarkSurfaceContainerHighest,
        outline = DarkOutline,
        outlineVariant = DarkOutlineVariant,
    )
}

@Composable
fun MangaPanelSearchTheme(
    preset: ColorPresetSpec = ColorPresets.TITANIUM_SLATE,
    content: @Composable () -> Unit
) {
    val colorScheme = createMangaColorScheme(preset)
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
