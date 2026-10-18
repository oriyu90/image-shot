package com.imageshot.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 独自ダークテーマ（01_製品仕様書.md §4.3）。
 * - 編集面は低反射の暗色背景 #1A1A1A 系。純黒は使わない（画像の色をUIが汚染しない）。
 * - 強調色は1箇所のみ。他社配色は複製しない（ADR-016）。
 * 本アプリのUI基調は常にダーク固定。isSystemInDarkTheme 参照は将来のライト対応を条件化しない。
 */
private val AppBackground = Color(0xFF141414) // 純黒ではなく低反射背景
private val EditorSurface = Color(0xFF1A1A1A) // 編集面・プレビュー面
private val SurfaceVariant = Color(0xFF242424)
private val OnSurfacePrimary = Color(0xFFEDEDED)
private val OnSurfaceMuted = Color(0xFF9AA0A6)
private val ActionAccent = Color(0xFF3E7C6F) // 操作対象1箇所に限る緑系アクセント（独自設計）

private val ImageShotColors = darkColorScheme(
    primary = ActionAccent,
    onPrimary = Color.White,
    background = AppBackground,
    onBackground = OnSurfacePrimary,
    surface = EditorSurface,
    onSurface = OnSurfacePrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceMuted,
    error = Color(0xFFB36A6A),
)

/** 本雛形では標準typographyを暗色に合わせて利用。字級は200%スケール検証は別テスト（§4.2）。 */
private val AppTypography = Typography()

@Composable
fun ImageShotTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val unusedDarkCheck = isSystemInDarkTheme() // 本アプリは常にダーク固定
    MaterialTheme(
        colorScheme = ImageShotColors,
        typography = AppTypography,
        content = content,
    )
}
