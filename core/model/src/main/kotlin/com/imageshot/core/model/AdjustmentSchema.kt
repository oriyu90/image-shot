package com.imageshot.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * 調整スライダーの単一正本スキーマ（01_製品仕様書.md §5.1
 * 「個別スライダーは物理単位・正規化範囲・既定値をコードの単一スキーマで定義」）。
 *
 * - UI（ラベル・範囲・既定値・刻み）と、レシピ適用（[apply]）と、履歴イベントの
 *   payload 変換がすべてこのオブジェクトから導出される。UI と処理系での定義重複を禁止する。
 * - レシピへの書込み単位: tone/color 系は -1.0..1.0 に正規化、[EXPOSURE] は EV（-5..+5）、
 *   [TEMPERATURE] は絶対 K（2000..12000, スライダー0 = as-shot = null）。
 *   [TINT] は近似単位（-1..1）として保存する（§5.1 の K/相対混在禁止を踏まえ
 *   スライダー値=レシピ値の対応をこの1点でのみ定義する）。
 * - 未知パス・非有限値は受理しない（§7.2 INVALID）。値域超過はクランプ（§7.1 正規化）。
 */
enum class AdjustmentId(
    /** EditRecipe 内の正本パス。履歴イベント payload の "path" も同一の値を使う。 */
    val recipePath: String,
    val label: String,
    val min: Double,
    val max: Double,
    val default: Double,
    val step: Double,
    val unit: Unit,
    /** このスライダーが属するイベント種別（ADR-022 正本列挙）。 */
    val kind: EditEventKind,
) {
    EXPOSURE("develop.tone.exposureEv", "露出", -5.0, 5.0, 0.0, 0.05, Unit.EV, EditEventKind.TONE),
    CONTRAST("develop.tone.contrast", "コントラスト", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.TONE),
    HIGHLIGHTS("develop.tone.highlights", "ハイライト", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.TONE),
    SHADOWS("develop.tone.shadows", "シャドウ", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.TONE),
    WHITES("develop.tone.whites", "白レベル", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.TONE),
    BLACKS("develop.tone.blacks", "黒レベル", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.TONE),
    VIBRANCE("develop.color.vibrance", "自然な彩度", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.COLOR),
    SATURATION("develop.color.saturation", "彩度", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.COLOR),
    TEMPERATURE("develop.whiteBalance.tempKelvin", "温度", -100.0, 100.0, 0.0, 1.0, Unit.TEMP_RELATIVE, EditEventKind.WHITE_BALANCE),
    TINT("develop.whiteBalance.tint", "色合い", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.WHITE_BALANCE),
    HSL_RED_HUE("develop.color.hsl.red.hue", "赤 色相", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_RED_SAT("develop.color.hsl.red.sat", "赤 彩度", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_RED_LUM("develop.color.hsl.red.lum", "赤 明るさ", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_ORANGE_HUE("develop.color.hsl.orange.hue", "オレンジ 色相", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_ORANGE_SAT("develop.color.hsl.orange.sat", "オレンジ 彩度", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_ORANGE_LUM("develop.color.hsl.orange.lum", "オレンジ 明るさ", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_YELLOW_HUE("develop.color.hsl.yellow.hue", "黄 色相", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_YELLOW_SAT("develop.color.hsl.yellow.sat", "黄 彩度", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_YELLOW_LUM("develop.color.hsl.yellow.lum", "黄 明るさ", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_GREEN_HUE("develop.color.hsl.green.hue", "緑 色相", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_GREEN_SAT("develop.color.hsl.green.sat", "緑 彩度", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_GREEN_LUM("develop.color.hsl.green.lum", "緑 明るさ", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_AQUA_HUE("develop.color.hsl.aqua.hue", "水 色相", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_AQUA_SAT("develop.color.hsl.aqua.sat", "水 彩度", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_AQUA_LUM("develop.color.hsl.aqua.lum", "水 明るさ", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_BLUE_HUE("develop.color.hsl.blue.hue", "青 色相", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_BLUE_SAT("develop.color.hsl.blue.sat", "青 彩度", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_BLUE_LUM("develop.color.hsl.blue.lum", "青 明るさ", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_PURPLE_HUE("develop.color.hsl.purple.hue", "紫 色相", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_PURPLE_SAT("develop.color.hsl.purple.sat", "紫 彩度", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_PURPLE_LUM("develop.color.hsl.purple.lum", "紫 明るさ", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_MAGENTA_HUE("develop.color.hsl.magenta.hue", "マゼンタ 色相", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_MAGENTA_SAT("develop.color.hsl.magenta.sat", "マゼンタ 彩度", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    HSL_MAGENTA_LUM("develop.color.hsl.magenta.lum", "マゼンタ 明るさ", -100.0, 100.0, 0.0, 1.0, Unit.POINT, EditEventKind.HSL),
    ;

    enum class Unit(val display: String) {
        EV("EV"),
        POINT(""),
        TEMP_RELATIVE(""),
    }

    /**
     * スライダー値をレシピへ適用する（正規化・クランプ済みの値のみ書込む）。
     * 非有限値は適用せずレシピをそのまま返す（失敗を無通知にしない上位層の検証 [isValid] と併用）。
     */
    fun apply(recipe: EditRecipe, sliderValue: Double): EditRecipe {
        val value = validClamped(sliderValue) ?: return recipe
        return when (this) {
            EXPOSURE -> recipe.copy(
                develop = recipe.develop.copy(tone = recipe.develop.tone.copy(exposureEv = value)),
            )
            CONTRAST -> recipe.copy(
                develop = recipe.develop.copy(tone = recipe.develop.tone.copy(contrast = value / 100.0)),
            )
            HIGHLIGHTS -> recipe.copy(
                develop = recipe.develop.copy(tone = recipe.develop.tone.copy(highlights = value / 100.0)),
            )
            SHADOWS -> recipe.copy(
                develop = recipe.develop.copy(tone = recipe.develop.tone.copy(shadows = value / 100.0)),
            )
            WHITES -> recipe.copy(
                develop = recipe.develop.copy(tone = recipe.develop.tone.copy(whites = value / 100.0)),
            )
            BLACKS -> recipe.copy(
                develop = recipe.develop.copy(tone = recipe.develop.tone.copy(blacks = value / 100.0)),
            )
            VIBRANCE -> recipe.copy(
                develop = recipe.develop.copy(color = recipe.develop.color.copy(vibrance = value / 100.0)),
            )
            SATURATION -> recipe.copy(
                develop = recipe.develop.copy(color = recipe.develop.color.copy(saturation = value / 100.0)),
            )
            TEMPERATURE -> recipe.copy(
                develop = recipe.develop.copy(
                    whiteBalance = recipe.develop.whiteBalance.copy(tempKelvin = tempKelvinFromSlider(value)),
                ),
            )
            TINT -> recipe.copy(
                develop = recipe.develop.copy(
                    whiteBalance = recipe.develop.whiteBalance.copy(tint = value / 100.0),
                ),
            )
            else -> {
                // HSL 8系統（kind = HSL）: 正規化済み key のみ受理（EditNodeSpecs が正本）。
                val key = recipePath.removePrefix(HSL_PATH_PREFIX)
                if (EditNodeSpecs.parseHslKey(key) == null) return recipe
                recipe.copy(
                    develop = recipe.develop.copy(
                        color = recipe.develop.color.copy(hsl = recipe.develop.color.hsl + (key to value / 100.0)),
                    ),
                )
            }
        }
    }

    /** 現在のレシピからスライダー表示値を復元する（適用の逆写像）。未定義温度は 0（as-shot）。 */
    fun sliderValueOf(recipe: EditRecipe): Double = when (this) {
        EXPOSURE -> sanitize(recipe.develop.tone.exposureEv).coerceIn(min, max)
        CONTRAST -> (sanitize(recipe.develop.tone.contrast) * 100.0).coerceIn(min, max)
        HIGHLIGHTS -> (sanitize(recipe.develop.tone.highlights) * 100.0).coerceIn(min, max)
        SHADOWS -> (sanitize(recipe.develop.tone.shadows) * 100.0).coerceIn(min, max)
        WHITES -> (sanitize(recipe.develop.tone.whites) * 100.0).coerceIn(min, max)
        BLACKS -> (sanitize(recipe.develop.tone.blacks) * 100.0).coerceIn(min, max)
        VIBRANCE -> (sanitize(recipe.develop.color.vibrance) * 100.0).coerceIn(min, max)
        SATURATION -> (sanitize(recipe.develop.color.saturation) * 100.0).coerceIn(min, max)
        TEMPERATURE -> sliderFromKelvin(recipe.develop.whiteBalance.tempKelvin)
        TINT -> (sanitize(recipe.develop.whiteBalance.tint) * 100.0).coerceIn(min, max)
        else -> {
            val mapValue = recipe.develop.color.hsl[recipePath.removePrefix(HSL_PATH_PREFIX)]
            (sanitize(mapValue ?: 0.0) * 100.0).coerceIn(min, max)
        }
    }

    /** スライダー値の表示文字列（UI 用の唯一の整形源）。 */
    fun formatValue(sliderValue: Double): String = when (unit) {
        Unit.EV -> "%+.2f".format(validClamped(sliderValue) ?: 0.0)
        else -> {
            val n = ((validClamped(sliderValue) ?: 0.0) / step).toInt()
            if (n == 0) "0" else "%+d".format(n)
        }
    }

    /** 非有限は無効。有効なら値域にクランプした値を返す。 */
    fun validClamped(raw: Double): Double? =
        if (raw.isFinite()) raw.coerceIn(min, max) else null

    companion object {
        /** 正本パスからの逆引き。未知パスは null（履歴再生時はスキップ・保存時は拒否）。 */
        fun byPath(path: String): AdjustmentId? = entries.firstOrNull { it.recipePath == path }

        // ---- 温度スライダー ⇔ Kelvin の唯一の写像定義（近似・単一の場所で管理）----
        // mired = 1e6/K。6500K を中立とし、スライダー ±100 を mired ±200 へ変換、
        // 端は 2000–12000K にクランプ（+側は片側のみ全域、-側は途中でクランプされる近似）。
        const val TEMP_NEUTRAL_KELVIN = 6500.0
        const val TEMP_MIRED_PER_UNIT = 2.0

        /** HSL SliderId の正本 path 接頭辞（レシピの color.hsl map key はこの後続）。 */
        const val HSL_PATH_PREFIX = "develop.color.hsl."
        private const val MIN_MIRED = 1_000_000.0 / 12000.0
        private const val MAX_MIRED = 1_000_000.0 / 2000.0

        /** スライダー値（-100..100）→ 絶対 K。0 は as-shot を意味し null を返す。 */
        fun tempKelvinFromSlider(sliderValue: Double): Double? {
            if (sliderValue == 0.0 || !sliderValue.isFinite()) return null
            val mired = (1_000_000.0 / TEMP_NEUTRAL_KELVIN + sliderValue * TEMP_MIRED_PER_UNIT)
                .coerceIn(MIN_MIRED, MAX_MIRED)
            return 1_000_000.0 / mired
        }

        /** 絶対 K → スライダー表示位置。null（as-shot）は 0。
         *  注意: 端でクランプされるため、スライダー値→K→スライダー値の往復は
         *  ±100 の端のみ元の K を保存しない（クランプ側）非可逆近似。 */
        fun sliderFromKelvin(tempKelvin: Double?): Double {
            if (tempKelvin == null || !tempKelvin.isFinite() ||
                tempKelvin <= 0.0 || tempKelvin > 300_000.0
            ) {
                return 0.0
            }
            val mired = 1_000_000.0 / tempKelvin
            val slider = (mired - 1_000_000.0 / TEMP_NEUTRAL_KELVIN) / TEMP_MIRED_PER_UNIT
            return slider.coerceIn(-100.0, 100.0)
        }

        private fun sanitize(v: Double): Double = if (v.isFinite()) v else 0.0
    }
}

/**
 * 確定操作1件（§3.3: onDragEnd で正規化した1件のみ確定）。
 * 値は各 AdjustmentId のスライダー正規化範囲で運ぶ。payload は
 * `{"path": ..., "value": ..., "op": "apply"|"undo"|"redo"}`。
 * Undo/Redo も新規イベントとして追記される（既存イベントの削除・書換なし、ADR-007）。
 */
data class RecipeParameterEdit(
    val path: String,
    val value: Double,
) {
    /** payload 生成もスキーマ側で一元化し、DB 層との契約を揃える。 */
    fun toPayload(): JsonObject = buildJsonObject {
        put("path", path)
        put("value", AdjustmentId.byPath(path)?.validClamped(value) ?: 0.0)
        put("op", OP_APPLY)
    }

    companion object {
        const val OP_APPLY = "apply"
        const val OP_UNDO = "undo"
        const val OP_REDO = "redo"

        /** 非有限・未知パスは FAIL として扱う（§7.2 INVALID スキップ条件）。 */
        fun validate(path: String, value: Double): Result<RecipeParameterEdit> = runCatching {
            val id = AdjustmentId.byPath(path) ?: error("unknown adjustment path: $path")
            val clamped = id.validClamped(value) ?: error("non-finite value for $path")
            RecipeParameterEdit(path, clamped)
        }

        /** abs(value) が既定値から変化しているか（計測用の補助）。 */
        fun isNeutral(edit: RecipeParameterEdit): Boolean {
            val id = AdjustmentId.byPath(edit.path) ?: return true
            val v = id.validClamped(edit.value) ?: return true
            return abs(v - id.default) < 1e-12
        }
    }
}
