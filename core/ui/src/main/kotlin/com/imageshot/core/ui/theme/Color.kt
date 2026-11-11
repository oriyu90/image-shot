package com.imageshot.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Image Shot の配色トークン（DESIGN_RULES.md §Tokens）。
 *
 * - 常時ダーク固定。写真の色を UI が汚染しないよう、面は無彩色寄りに寄せる（ADR-016）。
 * - アクセントは primary 1 系統のみ。tertiary は第2のアクセントにしない。
 * - すべて Material 3 のロール名で定義し、画面側はロールだけを参照する。
 */
object Palette {
    // 面（暗い順）
    val Scrim = Color(0xFF000000)
    val SurfaceContainerLowest = Color(0xFF0C0E0D)
    val Background = Color(0xFF101312)
    val SurfaceDim = Color(0xFF101312)
    val SurfaceContainerLow = Color(0xFF151817)
    val Surface = Color(0xFF181B1A)
    val SurfaceContainer = Color(0xFF1C1F1E)
    val SurfaceContainerHigh = Color(0xFF232726)
    val SurfaceContainerHighest = Color(0xFF2A2F2D)
    val SurfaceBright = Color(0xFF333937)
    val SurfaceVariant = Color(0xFF232826)

    // 文字
    val OnSurface = Color(0xFFE4E7E5)
    val OnSurfaceVariant = Color(0xFFBFC8C4)
    val OnSurfaceFaint = Color(0xFF8B9591)
    val InverseSurface = Color(0xFFE4E7E5)
    val InverseOnSurface = Color(0xFF2C302E)

    // ブランド（seed #3E7C6F 由来の暗背景向けトーン）
    val Primary = Color(0xFF7FD8C4)
    val OnPrimary = Color(0xFF00382F)
    val PrimaryContainer = Color(0xFF005045)
    val OnPrimaryContainer = Color(0xFF9CF5E1)
    val InversePrimary = Color(0xFF00695A)

    // 補助（アクセントにしない）
    val Secondary = Color(0xFFB0CCC4)
    val OnSecondary = Color(0xFF1B3530)
    val SecondaryContainer = Color(0xFF324B45)
    val OnSecondaryContainer = Color(0xFFCCE8DF)
    val Tertiary = Color(0xFFC5C7BF)
    val OnTertiary = Color(0xFF2E312B)
    val TertiaryContainer = Color(0xFF44473F)
    val OnTertiaryContainer = Color(0xFFE2E3DB)

    // 状態
    val Error = Color(0xFFFFB4AB)
    val OnError = Color(0xFF690005)
    val ErrorContainer = Color(0xFF93000A)
    val OnErrorContainer = Color(0xFFFFDAD6)

    // 線
    val Outline = Color(0xFF899390)
    val OutlineVariant = Color(0xFF3F4946)
    val Divider = Color(0xFF262C2A)
}

internal val ImageShotDarkColors = darkColorScheme(
    primary = Palette.Primary,
    onPrimary = Palette.OnPrimary,
    primaryContainer = Palette.PrimaryContainer,
    onPrimaryContainer = Palette.OnPrimaryContainer,
    inversePrimary = Palette.InversePrimary,
    secondary = Palette.Secondary,
    onSecondary = Palette.OnSecondary,
    secondaryContainer = Palette.SecondaryContainer,
    onSecondaryContainer = Palette.OnSecondaryContainer,
    tertiary = Palette.Tertiary,
    onTertiary = Palette.OnTertiary,
    tertiaryContainer = Palette.TertiaryContainer,
    onTertiaryContainer = Palette.OnTertiaryContainer,
    background = Palette.Background,
    onBackground = Palette.OnSurface,
    surface = Palette.Surface,
    onSurface = Palette.OnSurface,
    surfaceVariant = Palette.SurfaceVariant,
    onSurfaceVariant = Palette.OnSurfaceVariant,
    surfaceTint = Palette.Primary,
    surfaceBright = Palette.SurfaceBright,
    surfaceDim = Palette.SurfaceDim,
    surfaceContainer = Palette.SurfaceContainer,
    surfaceContainerHigh = Palette.SurfaceContainerHigh,
    surfaceContainerHighest = Palette.SurfaceContainerHighest,
    surfaceContainerLow = Palette.SurfaceContainerLow,
    surfaceContainerLowest = Palette.SurfaceContainerLowest,
    inverseSurface = Palette.InverseSurface,
    inverseOnSurface = Palette.InverseOnSurface,
    error = Palette.Error,
    onError = Palette.OnError,
    errorContainer = Palette.ErrorContainer,
    onErrorContainer = Palette.OnErrorContainer,
    outline = Palette.Outline,
    outlineVariant = Palette.OutlineVariant,
    scrim = Palette.Scrim,
)
