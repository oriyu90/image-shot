package com.imageshot.core.render

import com.imageshot.core.model.EditRecipe

/**
 * develop（現像調整）適用の共有実装（02_実装定義書.md §3
 * 「プレビューと書き出しで同じノード実装とパラメータ」の唯一の適用点）。
 *
 * - プレビュー（SimpleBitmapPhotoRenderer.applyDevelop）と書き出し
 *   （ExportBitmapRenderer.applyTone）の両方がこの純関数へ委譲する。
 *   式の正本は [RecipeToneCurve]（8bit sRGB 暫定パイプライン・設計段階各方面）。
 * - 純Kotlin（android.* 不使用）。Bitmap への適用は上位層が getPixels/setPixels で
 *   [applyToPixels] に委譲する形で行う。JVM 単体テスト対象。
 */
object RecipeDevelopApplier {

    /**
     * 画素配列（ARGB_8888 パック形式）へ develop（トーン LUT + 彩度/自然な彩度）を適用する。
     * アルファは不変。非有限値は [RecipeToneCurve] 側で中立へ落とす（§7.1）。
     * パイプライン順（RecipeToneCurve KDoc 参照）:
     *   sRGB->線形 → 露出 → 温度利得 → 黒レベル → シャドウ → 白レベル →
     *   ハイライト → コントラスト → 線形->sRGB（LUT化）→ 彩度/自然な彩度（残差）。
     *
     * 【明示】8bit LUT 適用のため出力は入力に対し最大 ±1 の量子化差を持つ
     * （既定パラメータでも）。プレビューと書き出しは同値（同一関数）であることを意味する。
     */
    fun applyToPixels(pixels: IntArray, develop: EditRecipe.DevelopParams) {
        val lut = RecipeToneCurve.buildChannelLuts(develop)
        val mixer = RecipeToneCurve.ColorMixer(
            develop.color.vibrance,
            develop.color.saturation,
        )
        val mixBuf = FloatArray(3)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = lut[(p ushr 16) and 0xFF] / 255.0f
            val g = lut[256 + ((p ushr 8) and 0xFF)] / 255.0f
            val b = lut[512 + (p and 0xFF)] / 255.0f
            mixer.mix(r, g, b, mixBuf)
            val ir = (mixBuf[0] * 255.0f + 0.5f).toInt().coerceIn(0, 255)
            val ig = (mixBuf[1] * 255.0f + 0.5f).toInt().coerceIn(0, 255)
            val ib = (mixBuf[2] * 255.0f + 0.5f).toInt().coerceIn(0, 255)
            pixels[i] = ((p ushr 24 and 0xFF) shl 24) or (ir shl 16) or (ig shl 8) or ib
        }
    }

    /**
     * この段階で読まない develop/レシピノードのうち、既定値から変化しているものの警告
     * （ADR-014: 無通知に捨てない）。既定値のみのレシピには空を返す。
     *
     * 【適用済みのため警告対象外】tone（露出/コントラスト/ハイライト/シャドウ/白黒レベル）、
     * 温度、tint、彩度、自然な彩度（[applyToPixels] が処理する）。
     * 【警告対象】カーブ / HSL / ディテール / ジオメトリ・レンズ / 局所マスク。
     */
    fun unappliedNodeWarnings(recipe: EditRecipe): List<String> {
        val warnings = mutableListOf<String>()
        val d = recipe.develop
        if (d.curve.rgb != listOf(listOf(0.0, 0.0), listOf(1.0, 1.0)) || d.curve.r.isNotEmpty() ||
            d.curve.g.isNotEmpty() || d.curve.b.isNotEmpty()
        ) {
            warnings += "tone curve not applied (not implemented)"
        }
        if (d.color.hsl.isNotEmpty()) {
            warnings += "HSL not applied (not implemented)"
        }
        if (d.detail.sharpen != 0.0 || d.detail.lumaNoise != 0.0 || d.detail.chromaNoise != 0.0) {
            warnings += "detail nodes not applied (not implemented)"
        }
        val g = recipe.geometry
        if (g.cropNorm != listOf(0.0, 0.0, 1.0, 1.0) || g.angleDeg != 0.0 || g.mirrorX ||
            g.lensProfileId != null || g.perspective != null
        ) {
            warnings += "geometry/lens corrections not applied (not implemented)"
        }
        if (recipe.masks.isNotEmpty()) {
            warnings += "local masks not applied (not implemented)"
        }
        return warnings
    }
}
