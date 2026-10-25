package com.imageshot.feature.editor

import androidx.annotation.StringRes
import com.imageshot.core.model.AdjustmentId

/**
 * スライダー表示ラベルの正本写像（PR-27 G1 文字列外部化）。
 *
 * - AdjustmentId は core:model（純 Kotlin・Android res 不可能）にあり、文言は持たない。
 *   表示文言の正本は本モジュール strings.xml の `editor_adjust_*` に限る
 *   （AdjustmentSchema KDoc に移設経緯を記録）。
 * - PR-27 の受入条件「コードに文言ハードコードが残らない」に対し、
 *   本写像は res ID の列挙のみを含む（文言リテラルは含まない）。
 * - 追加の AdjustmentId には必ず対応する res 行と本写像の分岐を同時に足す
 *   （欠落は [labelRes] の when 網羅性で即座にコンパイルエラーになる）。
 */
internal object AdjustmentLabels {
    @StringRes
    fun labelRes(id: AdjustmentId): Int = when (id) {
        AdjustmentId.EXPOSURE -> R.string.editor_adjust_exposure
        AdjustmentId.CONTRAST -> R.string.editor_adjust_contrast
        AdjustmentId.HIGHLIGHTS -> R.string.editor_adjust_highlights
        AdjustmentId.SHADOWS -> R.string.editor_adjust_shadows
        AdjustmentId.WHITES -> R.string.editor_adjust_whites
        AdjustmentId.BLACKS -> R.string.editor_adjust_blacks
        AdjustmentId.VIBRANCE -> R.string.editor_adjust_vibrance
        AdjustmentId.SATURATION -> R.string.editor_adjust_saturation
        AdjustmentId.TEMPERATURE -> R.string.editor_adjust_temperature
        AdjustmentId.TINT -> R.string.editor_adjust_tint
        AdjustmentId.HSL_RED_HUE -> R.string.editor_adjust_hsl_red_hue
        AdjustmentId.HSL_RED_SAT -> R.string.editor_adjust_hsl_red_sat
        AdjustmentId.HSL_RED_LUM -> R.string.editor_adjust_hsl_red_lum
        AdjustmentId.HSL_ORANGE_HUE -> R.string.editor_adjust_hsl_orange_hue
        AdjustmentId.HSL_ORANGE_SAT -> R.string.editor_adjust_hsl_orange_sat
        AdjustmentId.HSL_ORANGE_LUM -> R.string.editor_adjust_hsl_orange_lum
        AdjustmentId.HSL_YELLOW_HUE -> R.string.editor_adjust_hsl_yellow_hue
        AdjustmentId.HSL_YELLOW_SAT -> R.string.editor_adjust_hsl_yellow_sat
        AdjustmentId.HSL_YELLOW_LUM -> R.string.editor_adjust_hsl_yellow_lum
        AdjustmentId.HSL_GREEN_HUE -> R.string.editor_adjust_hsl_green_hue
        AdjustmentId.HSL_GREEN_SAT -> R.string.editor_adjust_hsl_green_sat
        AdjustmentId.HSL_GREEN_LUM -> R.string.editor_adjust_hsl_green_lum
        AdjustmentId.HSL_AQUA_HUE -> R.string.editor_adjust_hsl_aqua_hue
        AdjustmentId.HSL_AQUA_SAT -> R.string.editor_adjust_hsl_aqua_sat
        AdjustmentId.HSL_AQUA_LUM -> R.string.editor_adjust_hsl_aqua_lum
        AdjustmentId.HSL_BLUE_HUE -> R.string.editor_adjust_hsl_blue_hue
        AdjustmentId.HSL_BLUE_SAT -> R.string.editor_adjust_hsl_blue_sat
        AdjustmentId.HSL_BLUE_LUM -> R.string.editor_adjust_hsl_blue_lum
        AdjustmentId.HSL_PURPLE_HUE -> R.string.editor_adjust_hsl_purple_hue
        AdjustmentId.HSL_PURPLE_SAT -> R.string.editor_adjust_hsl_purple_sat
        AdjustmentId.HSL_PURPLE_LUM -> R.string.editor_adjust_hsl_purple_lum
        AdjustmentId.HSL_MAGENTA_HUE -> R.string.editor_adjust_hsl_magenta_hue
        AdjustmentId.HSL_MAGENTA_SAT -> R.string.editor_adjust_hsl_magenta_sat
        AdjustmentId.HSL_MAGENTA_LUM -> R.string.editor_adjust_hsl_magenta_lum
    }
}
