package com.imageshot.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Image Shot のテーマ入口（DESIGN_RULES.md §Theme）。
 *
 * 常時ダーク固定。端末のライト/ダーク設定には追従しない（01_製品仕様書 §4.3・ADR-016:
 * 写真の色を UI が汚染しないため、面は低反射の暗色に固定する）。
 */
@Composable
fun ImageShotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ImageShotDarkColors,
        typography = ImageShotTypography,
        shapes = ImageShotShapes,
    ) {
        CompositionLocalProvider(LocalSpacing provides Spacing()) {
            content()
        }
    }
}
