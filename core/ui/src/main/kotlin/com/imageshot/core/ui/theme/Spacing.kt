package com.imageshot.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 余白トークン（DESIGN_RULES.md §Spacing）。4dp 基準。
 * 画面側は `MaterialTheme.spacing.md` のように参照し、dp リテラルを書かない。
 */

data class Spacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val huge: Dp = 40.dp,
    val giant: Dp = 56.dp,
) {
    /** 画面外周の標準インセット。compact / expanded で呼び出し側が選ぶ。 */
    val screenGutter: Dp get() = lg

    /** 読み物・フォームの最大幅。広い画面で行長が伸びすぎるのを防ぐ。 */
    val readingMaxWidth: Dp get() = 720.dp

    /** 2 ペインの一覧側の標準幅。 */
    val paneMinWidth: Dp get() = 360.dp
}

internal val LocalSpacing = staticCompositionLocalOf { Spacing() }

val MaterialTheme.spacing: Spacing
    @Composable
    @ReadOnlyComposable
    get() = LocalSpacing.current
