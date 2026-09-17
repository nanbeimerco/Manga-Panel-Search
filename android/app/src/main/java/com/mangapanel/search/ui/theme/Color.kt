package com.mangapanel.search.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Material 3 共通ニュートラル階層 (CreditDB 仕様完全準拠)
 */
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

/**
 * Material 3 カラープリセット仕様
 */
data class ColorPresetSpec(
    val id: String,
    val name: String,
    val description: String,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val previewDot: Color
)

object ColorPresets {
    val TITANIUM_SLATE = ColorPresetSpec(
        id = "titanium_slate",
        name = "Titanium Slate",
        description = "知的でプロフェッショナルなチタンスレート (標準)",
        primary = Color(0xFFA8C7FA),
        onPrimary = Color(0xFF003062),
        primaryContainer = Color(0xFF00468A),
        onPrimaryContainer = Color(0xFFD6E3FF),
        secondary = Color(0xFFBEC6DC),
        onSecondary = Color(0xFF283141),
        secondaryContainer = Color(0xFF3E4759),
        onSecondaryContainer = Color(0xFFDAE2F9),
        tertiary = Color(0xFFEFB8C8),
        onTertiary = Color(0xFF492532),
        tertiaryContainer = Color(0xFF633B48),
        onTertiaryContainer = Color(0xFFFFD8E4),
        previewDot = Color(0xFFA8C7FA)
    )

    val MATERIAL_LAVENDER = ColorPresetSpec(
        id = "material_lavender",
        name = "Material Lavender",
        description = "Google Material 3 公式ベースラインラベンダー",
        primary = Color(0xFFD0BCFF),
        onPrimary = Color(0xFF381E72),
        primaryContainer = Color(0xFF4F378B),
        onPrimaryContainer = Color(0xFFEADDFF),
        secondary = Color(0xFFCCC2DC),
        onSecondary = Color(0xFF332D41),
        secondaryContainer = Color(0xFF4A4458),
        onSecondaryContainer = Color(0xFFE8DEF8),
        tertiary = Color(0xFFEFB8C8),
        onTertiary = Color(0xFF492532),
        tertiaryContainer = Color(0xFF633B48),
        onTertiaryContainer = Color(0xFFFFD8E4),
        previewDot = Color(0xFFD0BCFF)
    )

    val NORDIC_EMERALD = ColorPresetSpec(
        id = "nordic_emerald",
        name = "Nordic Emerald",
        description = "落ち着きと品位のある北欧フォレストグリーン",
        primary = Color(0xFF82D9A7),
        onPrimary = Color(0xFF003822),
        primaryContainer = Color(0xFF005234),
        onPrimaryContainer = Color(0xFFA0F5C2),
        secondary = Color(0xFFB4CCBE),
        onSecondary = Color(0xFF20352A),
        secondaryContainer = Color(0xFF364B3F),
        onSecondaryContainer = Color(0xFFD0E8D9),
        tertiary = Color(0xFFD3C7A8),
        onTertiary = Color(0xFF38301B),
        tertiaryContainer = Color(0xFF4F462F),
        onTertiaryContainer = Color(0xFFF0E3C3),
        previewDot = Color(0xFF82D9A7)
    )

    val AMBER_BRONZE = ColorPresetSpec(
        id = "amber_bronze",
        name = "Amber Bronze",
        description = "映画的で温かみのあるクラシックアンバー",
        primary = Color(0xFFF0C068),
        onPrimary = Color(0xFF422C00),
        primaryContainer = Color(0xFF5E4100),
        onPrimaryContainer = Color(0xFFFFDEA4),
        secondary = Color(0xFFD7C4A8),
        onSecondary = Color(0xFF3B2F1B),
        secondaryContainer = Color(0xFF52452F),
        onSecondaryContainer = Color(0xFFF4E0C3),
        tertiary = Color(0xFFDEC2B7),
        onTertiary = Color(0xFF402C26),
        tertiaryContainer = Color(0xFF58423B),
        onTertiaryContainer = Color(0xFFFFDBCF),
        previewDot = Color(0xFFF0C068)
    )

    val SAKURA_ROSE = ColorPresetSpec(
        id = "sakura_rose",
        name = "Sakura Rose",
        description = "上品で華やかな和風サクラローズ",
        primary = Color(0xFFFFB1C8),
        onPrimary = Color(0xFF5E1133),
        primaryContainer = Color(0xFF7B2949),
        onPrimaryContainer = Color(0xFFFFD9E2),
        secondary = Color(0xFFE5BDC7),
        onSecondary = Color(0xFF422932),
        secondaryContainer = Color(0xFF5B3F48),
        onSecondaryContainer = Color(0xFFFFD9E2),
        tertiary = Color(0xFFF0BC95),
        onTertiary = Color(0xFF472911),
        tertiaryContainer = Color(0xFF613E25),
        onTertiaryContainer = Color(0xFFFFDCC2),
        previewDot = Color(0xFFFFB1C8)
    )

    val all = listOf(TITANIUM_SLATE, MATERIAL_LAVENDER, NORDIC_EMERALD, AMBER_BRONZE, SAKURA_ROSE)

    fun fromId(id: String): ColorPresetSpec {
        return all.find { it.id == id } ?: TITANIUM_SLATE
    }
}
