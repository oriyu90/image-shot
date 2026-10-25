package com.imageshot.feature.editor

import androidx.annotation.StringRes
import com.imageshot.core.model.EditNodeSpecs

/**
 * HSL バンド/パラメータの表示ラベル写像（PR-27 G1）。
 * - EditNodeSpecs.HSL_BANDS / HSL_PARAMS（core:model・純 Kotlin）はバンドID の正本のみを持ち、
 *   表示文言は本モジュール strings.xml（`editor_hsl_band_*` / `editor_hsl_param_*`）に限る。
 * - 未知IDは正本 :EditNodeSpecs.parseHslKey の検証で UI へ到達しない想定。
 *   フォールバックは res 由来の汎用表示のみ（コード側文言を生まない）。
 */
internal object HslBandLabels {
    @StringRes
    fun bandRes(bandId: String): Int = when (bandId) {
        EditNodeSpecs.HSL_BANDS[0] -> R.string.editor_hsl_band_red
        EditNodeSpecs.HSL_BANDS[1] -> R.string.editor_hsl_band_orange
        EditNodeSpecs.HSL_BANDS[2] -> R.string.editor_hsl_band_yellow
        EditNodeSpecs.HSL_BANDS[3] -> R.string.editor_hsl_band_green
        EditNodeSpecs.HSL_BANDS[4] -> R.string.editor_hsl_band_aqua
        EditNodeSpecs.HSL_BANDS[5] -> R.string.editor_hsl_band_blue
        EditNodeSpecs.HSL_BANDS[6] -> R.string.editor_hsl_band_purple
        EditNodeSpecs.HSL_BANDS[7] -> R.string.editor_hsl_band_magenta
        else -> R.string.editor_tab_hsl // 未知IDはレシピ分類としての「HSL」表示へ落とす（文言は res 由来）。
    }

    @StringRes
    fun paramRes(paramId: String): Int = when (paramId) {
        EditNodeSpecs.HSL_PARAMS[0] -> R.string.editor_hsl_param_hue
        EditNodeSpecs.HSL_PARAMS[1] -> R.string.editor_hsl_param_sat
        EditNodeSpecs.HSL_PARAMS[2] -> R.string.editor_hsl_param_lum
        else -> R.string.editor_tab_hsl
    }
}
