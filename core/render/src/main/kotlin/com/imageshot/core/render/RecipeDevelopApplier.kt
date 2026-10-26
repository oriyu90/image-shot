package com.imageshot.core.render

import com.imageshot.core.model.EditNodeSpecs
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.MaskSpecs

/**
 * develop（現像調整）適用の共有実装（02_実装定義書.md §3
 * 「プレビューと書き出しで同じノード実装とパラメータ」の唯一の適用点）。
 *
 * - プレビュー（SimpleBitmapPhotoRenderer.applyDevelop）と書き出し
 *   （ExportBitmapRenderer.applyDevelop）の両方がこの純関数へ委譲する。
 *   式の正本は [RecipeToneCurve]（8bit sRGB 暫定パイプライン）+ [RecipeCurve]（master カーブ）+
 *   [RecipeHsl]（HSL 8系統）。
 * - 純Kotlin（android.* 不使用）。Bitmap への適用は上位層が getPixels/setPixels で
 *   [applyToPixels] に委譲する形で行う（ジオメトリは [RecipeGeometry] 経由で適用前段）。
 *   JVM 単体テスト対象。
 *
 * パイプライン順（正本・固定。将来変更時は preview/export 同時に変えること）:
 *   1) スライダー LUT（sRGB→線形 → 露出 → 温度利得 → 黒レベル → シャドウ → 白レベル →
 *      ハイライト → コントラスト → 線形→sRGB）
 *   2) master カーブ（sRGB 表示値ドメイン・R/G/B 等量・LUT 内に合成）
 *      → R/G/B チャンネルカーブ（sRGB 表示値ドメイン・各チャンネル独立・LUT 内に合成・PR-28a）
 *   3) 彩度 / 自然な彩度（画素残差）
 *   4) HSL 8系統（バンド重み行列、明るさのみ線形光ドメイン）
 *
 * 【明示】8bit LUT 適用のため出力は入力に対し最大 ±1 の量子化差を持つ
 * （既定パラメータでも）。プレビューと書き出しは同値（同一関数）であることを意味する。
 */
object RecipeDevelopApplier {

    /**
     * 画素配列（ARGB_8888 パック形式）へ develop を適用する。
     * アルファは不変。非有限値は各ノード側で中立へ落とす（§7.1）。
     */
    fun applyToPixels(pixels: IntArray, develop: EditRecipe.DevelopParams) {
        val stage = DevelopStage(develop)
        for (i in pixels.indices) {
            pixels[i] = stage.apply(pixels[i])
        }
    }

    /**
     * develop 1段（LUT + 彩度/自然な彩度 + HSL）の画素適用器。
     *
     * [applyToPixels] と局所マスク合成（[RecipeMaskApplier]）が同一の演算順序・同一式を共有する
     * ための抽出（02 §3.3「プレビューと書き出しで同じノード実装」・PR-29b）。
     * 式・演算順序は抽出前と完全に同一（既存 golden ケースの bit 一致を維持する）。
     * インスタンスは 1 スレッド内で使うこと（[mixBuf] を再利用する）。
     */
    internal class DevelopStage(develop: EditRecipe.DevelopParams) {
        private val lut: IntArray = RecipeToneCurve.buildChannelLuts(develop)
        private val mixer: RecipeToneCurve.ColorMixer =
            RecipeToneCurve.ColorMixer(develop.color.vibrance, develop.color.saturation)
        private val hslMixer: RecipeHsl.HslMixer? =
            if (!RecipeHsl.isNeutral(develop.color.hsl)) RecipeHsl.HslMixer(develop.color.hsl) else null
        private val mixBuf = FloatArray(3)

        /** 1画素を変換した ARGB を返す（アルファは入力のまま）。 */
        fun apply(p: Int): Int {
            var r = lut[(p ushr 16) and 0xFF] / 255.0f
            var g = lut[256 + ((p ushr 8) and 0xFF)] / 255.0f
            var b = lut[512 + (p and 0xFF)] / 255.0f
            mixer.mix(r, g, b, mixBuf)
            r = mixBuf[0]
            g = mixBuf[1]
            b = mixBuf[2]
            if (hslMixer != null) {
                hslMixer.apply(r, g, b, mixBuf)
                r = mixBuf[0]
                g = mixBuf[1]
                b = mixBuf[2]
            }
            val ir = (r * 255.0f + 0.5f).toInt().coerceIn(0, 255)
            val ig = (g * 255.0f + 0.5f).toInt().coerceIn(0, 255)
            val ib = (b * 255.0f + 0.5f).toInt().coerceIn(0, 255)
            return ((p ushr 24 and 0xFF) shl 24) or (ir shl 16) or (ig shl 8) or ib
        }
    }

    /**
     * この段階で読まない develop/レシピノードのうち、既定値から変化しているものの警告
     * （ADR-014: 無通知に捨てない）。既定値のみのレシピには空を返す。
     *
     * 【適用済みのため警告対象外】tone（露出/コントラスト/ハイライト/シャドウ/白黒レベル）、
     * 温度、tint、彩度、自然な彩度、master カーブ、R/G/B チャンネルカーブ
     * （PR-28a: master 直後・sRGB 表示値ドメインで各チャンネルに独立適用）、
     * HSL 8系統（正規化済み key）。
     *   温度: チャンネル利得 = RecipeToneCurve.tempGains（mired 相対・strength 0.4、R+/B−）。
     *   tint : チャンネル利得 = RecipeToneCurve.tintGains（設計段近似・未検証:
     *          t>0 で緑寄り — G *= (1+0.30t)、R *= B *= (1−0.15t)。t<0 はマゼンタ寄りの逆方向。
     *          係数は温度利得と同格の暫定値。RecipeToneCurve.tintGains の KDoc 参照）。
     *          両利得は buildChannelLuts 内で要素積として合成され、LUT で1回だけ掛かる
     *          （旧実装では tint は暗黙に無視されていた。今回から実際に効く。トーンパイプライン
     *          順序自体は変更していない）。
     * 【警告対象】ディテール（PR-28c で適用開始）の未適用要素 / ジオメトリの未対応要素
     * （光学補正・透視）/ 正規化できない局所マスク。
     * 【適用済みのため警告対象外（PR-29b）】局所マスク（linear/radial/brush・feather・
     * マスク毎 adjustments）は geometry → develop → masks → detail の正本順序で適用される
     * （[RecipeMaskApplier]）。
     * 自由角度回転は PR-28b で適用開始したため警告対象外（[RecipeGeometry.rotateArbitrary]）。
     */
    fun unappliedNodeWarnings(recipe: EditRecipe): List<String> {
        val warnings = mutableListOf<String>()
        val d = recipe.develop
        if (d.color.hsl.any { EditNodeSpecs.parseHslKey(it.key) == null || !it.value.isFinite() }) {
            warnings += "unknown HSL keys/values preserved only (not applied)"
        }
        // PR-28c: シャープ/輝度NR は適用済み。chromaNR のみ未適用（モデル互換のため残る）。
        if (d.detail.chromaNoise != 0.0) {
            warnings += "chroma noise reduction not applied (not implemented)"
        }
        val g = recipe.geometry
        if (g.lensProfileId != null || g.perspective != null) {
            warnings += "lens/perspective corrections not applied (not implemented)"
        }
        // PR-29a/29b: 局所マスクは適用済み（[RecipeMaskApplier]）。ただし正規化できないノード
        // （未知種別・未知パス・範囲外・maskId 重複・上限超過）は1件も適用できないため、
        // その場合だけ警告する（正当なレシピに偽警告を出さない）。
        if (recipe.masks.isNotEmpty() && MaskSpecs.normalizeAll(recipe.masks) == null) {
            warnings += "local masks invalid or out of range (preserved only, not applied)"
        }
        return warnings
    }
}
