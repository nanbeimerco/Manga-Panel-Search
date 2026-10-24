package com.mangapanel.search.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Material 3 共通ニュートラル階層 (Dark / Light)
 */
// Dark Neutral Surface Roles
val DarkBackground = Color(0xFF111318)
val DarkOnBackground = Color(0xFFE2E2E6)
val DarkSurface = Color(0xFF111318)
val DarkOnSurface = Color(0xFFE2E2E6)
val DarkSurfaceVariant = Color(0xFF44474E)
val DarkOnSurfaceVariant = Color(0xFFC4C7C5)
val DarkSurfaceContainerLowest = Color(0xFF0C0E12)
val DarkSurfaceContainerLow = Color(0xFF17191E)
val DarkSurfaceContainer = Color(0xFF1D2024)
val DarkSurfaceContainerHigh = Color(0xFF282A2F)
val DarkSurfaceContainerHighest = Color(0xFF33353A)
val DarkOutline = Color(0xFF8E918F)
val DarkOutlineVariant = Color(0xFF444746)

// Light Neutral Surface Roles
val LightBackground = Color(0xFFF9F9FF)
val LightOnBackground = Color(0xFF191C20)
val LightSurface = Color(0xFFF9F9FF)
val LightOnSurface = Color(0xFF191C20)
val LightSurfaceVariant = Color(0xFFE0E2EC)
val LightOnSurfaceVariant = Color(0xFF44474F)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF3F3FA)
val LightSurfaceContainer = Color(0xFFEDEDF4)
val LightSurfaceContainerHigh = Color(0xFFE7E8EE)
val LightSurfaceContainerHighest = Color(0xFFE1E2E8)
val LightOutline = Color(0xFF74777F)
val LightOutlineVariant = Color(0xFFC4C7D0)

/**
 * Material 3 カラープリセット仕様
 */
data class ColorPresetSpec(
    val id: String,
    val name: String,
    val description: String,
    val previewDot: Color,
    // Dark Theme Colors
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val darkPrimaryContainer: Color,
    val darkOnPrimaryContainer: Color,
    val darkSecondary: Color,
    val darkOnSecondary: Color,
    val darkSecondaryContainer: Color,
    val darkOnSecondaryContainer: Color,
    val darkTertiary: Color,
    val darkOnTertiary: Color,
    val darkTertiaryContainer: Color,
    val darkOnTertiaryContainer: Color,
    // Light Theme Colors
    val lightPrimary: Color,
    val lightOnPrimary: Color,
    val lightPrimaryContainer: Color,
    val lightOnPrimaryContainer: Color,
    val lightSecondary: Color,
    val lightOnSecondary: Color,
    val lightSecondaryContainer: Color,
    val lightOnSecondaryContainer: Color,
    val lightTertiary: Color,
    val lightOnTertiary: Color,
    val lightTertiaryContainer: Color,
    val lightOnTertiaryContainer: Color
)

object ColorPresets {
    val TITANIUM_SLATE = ColorPresetSpec(
        id = "titanium_slate",
        name = "Titanium Slate",
        description = "知的で洗練されたチタンスレート (標準)",
        previewDot = Color(0xFFA8C7FA),
        // Dark
        darkPrimary = Color(0xFFA8C7FA),
        darkOnPrimary = Color(0xFF003062),
        darkPrimaryContainer = Color(0xFF00468A),
        darkOnPrimaryContainer = Color(0xFFD6E3FF),
        darkSecondary = Color(0xFFBEC6DC),
        darkOnSecondary = Color(0xFF283141),
        darkSecondaryContainer = Color(0xFF3E4759),
        darkOnSecondaryContainer = Color(0xFFDAE2F9),
        darkTertiary = Color(0xFFEFB8C8),
        darkOnTertiary = Color(0xFF492532),
        darkTertiaryContainer = Color(0xFF633B48),
        darkOnTertiaryContainer = Color(0xFFFFD8E4),
        // Light
        lightPrimary = Color(0xFF005DA8),
        lightOnPrimary = Color(0xFFFFFFFF),
        lightPrimaryContainer = Color(0xFFD6E3FF),
        lightOnPrimaryContainer = Color(0xFF001B3E),
        lightSecondary = Color(0xFF555F71),
        lightOnSecondary = Color(0xFFFFFFFF),
        lightSecondaryContainer = Color(0xFFD9E3F9),
        lightOnSecondaryContainer = Color(0xFF121C2B),
        lightTertiary = Color(0xFF7C5260),
        lightOnTertiary = Color(0xFFFFFFFF),
        lightTertiaryContainer = Color(0xFFFFD8E4),
        lightOnTertiaryContainer = Color(0xFF31101D)
    )

    val MATERIAL_LAVENDER = ColorPresetSpec(
        id = "material_lavender",
        name = "Material Lavender",
        description = "Google Material 3 公式ベースラインラベンダー",
        previewDot = Color(0xFFD0BCFF),
        // Dark
        darkPrimary = Color(0xFFD0BCFF),
        darkOnPrimary = Color(0xFF381E72),
        darkPrimaryContainer = Color(0xFF4F378B),
        darkOnPrimaryContainer = Color(0xFFEADDFF),
        darkSecondary = Color(0xFFCCC2DC),
        darkOnSecondary = Color(0xFF332D41),
        darkSecondaryContainer = Color(0xFF4A4458),
        darkOnSecondaryContainer = Color(0xFFE8DEF8),
        darkTertiary = Color(0xFFEFB8C8),
        darkOnTertiary = Color(0xFF492532),
        darkTertiaryContainer = Color(0xFF633B48),
        darkOnTertiaryContainer = Color(0xFFFFD8E4),
        // Light
        lightPrimary = Color(0xFF6750A4),
        lightOnPrimary = Color(0xFFFFFFFF),
        lightPrimaryContainer = Color(0xFFEADDFF),
        lightOnPrimaryContainer = Color(0xFF21005D),
        lightSecondary = Color(0xFF625B71),
        lightOnSecondary = Color(0xFFFFFFFF),
        lightSecondaryContainer = Color(0xFFE8DEF8),
        lightOnSecondaryContainer = Color(0xFF1D192B),
        lightTertiary = Color(0xFF7D5260),
        lightOnTertiary = Color(0xFFFFFFFF),
        lightTertiaryContainer = Color(0xFFFFD8E4),
        lightOnTertiaryContainer = Color(0xFF31111D)
    )

    val NORDIC_EMERALD = ColorPresetSpec(
        id = "nordic_emerald",
        name = "Nordic Emerald",
        description = "落ち着きと品位のある北欧フォレストグリーン",
        previewDot = Color(0xFF82D9A7),
        // Dark
        darkPrimary = Color(0xFF82D9A7),
        darkOnPrimary = Color(0xFF003822),
        darkPrimaryContainer = Color(0xFF005234),
        darkOnPrimaryContainer = Color(0xFFA0F5C2),
        darkSecondary = Color(0xFFB4CCBE),
        darkOnSecondary = Color(0xFF20352A),
        darkSecondaryContainer = Color(0xFF364B3F),
        darkOnSecondaryContainer = Color(0xFFD0E8D9),
        darkTertiary = Color(0xFFD3C7A8),
        darkOnTertiary = Color(0xFF38301B),
        darkTertiaryContainer = Color(0xFF4F462F),
        darkOnTertiaryContainer = Color(0xFFF0E3C3),
        // Light
        lightPrimary = Color(0xFF006C47),
        lightOnPrimary = Color(0xFFFFFFFF),
        lightPrimaryContainer = Color(0xFFA0F5C2),
        lightOnPrimaryContainer = Color(0xFF002113),
        lightSecondary = Color(0xFF4E6355),
        lightOnSecondary = Color(0xFFFFFFFF),
        lightSecondaryContainer = Color(0xFFD1E8D7),
        lightOnSecondaryContainer = Color(0xFF0C1F15),
        lightTertiary = Color(0xFF675E40),
        lightOnTertiary = Color(0xFFFFFFFF),
        lightTertiaryContainer = Color(0xFFF0E3C3),
        lightOnTertiaryContainer = Color(0xFF211B04)
    )

    val AMBER_BRONZE = ColorPresetSpec(
        id = "amber_bronze",
        name = "Amber Bronze",
        description = "映画的で温かみのあるクラシックアンバー",
        previewDot = Color(0xFFF0C068),
        // Dark
        darkPrimary = Color(0xFFF0C068),
        darkOnPrimary = Color(0xFF422C00),
        darkPrimaryContainer = Color(0xFF5E4100),
        darkOnPrimaryContainer = Color(0xFFFFDEA4),
        darkSecondary = Color(0xFFD7C4A8),
        darkOnSecondary = Color(0xFF3B2F1B),
        darkSecondaryContainer = Color(0xFF52452F),
        darkOnSecondaryContainer = Color(0xFFF4E0C3),
        darkTertiary = Color(0xFFDEC2B7),
        darkOnTertiary = Color(0xFF402C26),
        darkTertiaryContainer = Color(0xFF58423B),
        darkOnTertiaryContainer = Color(0xFFFFDBCF),
        // Light
        lightPrimary = Color(0xFF7B5800),
        lightOnPrimary = Color(0xFFFFFFFF),
        lightPrimaryContainer = Color(0xFFFFDEA4),
        lightOnPrimaryContainer = Color(0xFF271900),
        lightSecondary = Color(0xFF6B5D40),
        lightOnSecondary = Color(0xFFFFFFFF),
        lightSecondaryContainer = Color(0xFFF5E1BB),
        lightOnSecondaryContainer = Color(0xFF241A04),
        lightTertiary = Color(0xFF70574D),
        lightOnTertiary = Color(0xFFFFFFFF),
        lightTertiaryContainer = Color(0xFFFFDBCF),
        lightOnTertiaryContainer = Color(0xFF28160E)
    )

    val SAKURA_ROSE = ColorPresetSpec(
        id = "sakura_rose",
        name = "Sakura Rose",
        description = "上品で華やかな和風サクラローズ",
        previewDot = Color(0xFFFFB1C8),
        // Dark
        darkPrimary = Color(0xFFFFB1C8),
        darkOnPrimary = Color(0xFF5E1133),
        darkPrimaryContainer = Color(0xFF7B2949),
        darkOnPrimaryContainer = Color(0xFFFFD9E2),
        darkSecondary = Color(0xFFE5BDC7),
        darkOnSecondary = Color(0xFF422932),
        darkSecondaryContainer = Color(0xFF5B3F48),
        darkOnSecondaryContainer = Color(0xFFFFD9E2),
        darkTertiary = Color(0xFFF0BC95),
        darkOnTertiary = Color(0xFF472911),
        darkTertiaryContainer = Color(0xFF613E25),
        darkOnTertiaryContainer = Color(0xFFFFDCC2),
        // Light
        lightPrimary = Color(0xFF984060),
        lightOnPrimary = Color(0xFFFFFFFF),
        lightPrimaryContainer = Color(0xFFFFD9E2),
        lightOnPrimaryContainer = Color(0xFF3E001D),
        lightSecondary = Color(0xFF74565F),
        lightOnSecondary = Color(0xFFFFFFFF),
        lightSecondaryContainer = Color(0xFFFFD9E2),
        lightOnSecondaryContainer = Color(0xFF2B151C),
        lightTertiary = Color(0xFF7A583B),
        lightOnTertiary = Color(0xFFFFFFFF),
        lightTertiaryContainer = Color(0xFFFFDCC2),
        lightOnTertiaryContainer = Color(0xFF2E1500)
    )

    val all = listOf(TITANIUM_SLATE, MATERIAL_LAVENDER, NORDIC_EMERALD, AMBER_BRONZE, SAKURA_ROSE)

    fun fromId(id: String): ColorPresetSpec {
        return all.find { it.id == id } ?: TITANIUM_SLATE
    }
}
