package com.imageshot.core.render

import com.imageshot.core.model.EditRecipe

/**
 * ディテール（シャープ / 輝度ノイズ低減）の共有実装（PR-28c・02 §5.2 D2）。
 *
 * 位置契約: **develop 適用後・Bitmap 生成/リサイズ前**（preview/export で同一）。
 *   preview: SimpleBitmapPhotoRenderer.applyDevelop → [applyToPixels] → Bitmap 生成
 *   export : ExportRenderer.applyDevelop      → [applyToPixels] → resize
 * この順序は「色変換の後に空間フィルタ」という正本順序であり、両経路で同じ関数を通す。
 *
 * 処理内容（8bit sRGB の輝度平面のみ・色差は保存）:
 * - シャープ: unsharp mask。`L' = L + a·(L − gaussian3x3(L))`、a = sharpen × [SHARPEN_GAIN]。
 * - 輝度 NR: `L' = L + b·(gaussian3x3(L) − L)`、b = lumaNoise × [NR_GAIN]。
 *   両者は同一の差分項に合成される（`a−b`）。chromaNR は本世代の適用対象外
 *   （[RecipeDevelopApplier.unappliedNodeWarnings] が警告する）。
 * - 色差保存: `RGB' = RGB × (L'/L)`（L が 1 以下の暗部は増幅を避けて不変）。
 * - アルファは不変。
 *
 * 自動降格（性能契約・07 §12 の失敗時契約）:
 * - 画素数が [DEGRADE_PIXEL_COUNT] を超える場合は軽量経路（4近傍ラプラシアン・NR なし）へ
 *   自動降格し、[DEGRADED_WARNING] を warnings に載せる（ADR-014: 無通知の画質変更禁止）。
 * - 閾値は画素数による決定論的判定であり、時間計測には依存しない。同じ寸法の入力は常に
 *   同じ経路を通るため、preview=export の同値契約（同一寸法時）を壊さない。
 *   閾値は NFR-04 の実測（[nfr_catalog] 行）に基づき、余裕をみて 12MP とする
 *   （preview は 1600px 級 = 数 MP で通常経路、48MP 級の書き出しのみ軽量経路）。
 *
 * 実装上のメモリ制約: 輝度平面は全画素分を確保せず、[STRIP_ROWS] 行 + 上下 1 行の帯で
 * 逐次処理する（48MP でも数 MB オーダー）。帯の境界は上下 1 行のハローを再計算するため、
 * 全画素一括処理と同一の結果になる（行ごとの演算は近傍 3x3 のみに依存）。
 */
object RecipeDetailApplier {

    /**
     * 自動降格の警告文言（ADR-014 型・warnings / 書き出し request の issueNotes に載る）。
     * UI はこの定数との一致で「自動降格しました」を表示する（EditorScreen）。
     */
    const val DEGRADED_WARNING: String =
        "detail processing auto-degraded to light path (sharpen only, luma NR skipped): pixel count exceeds budget"

    /** 軽量経路へ切り替える画素数（決定論的・12MP）。 */
    const val DEGRADE_PIXEL_COUNT: Long = 12_000_000L

    /** 帯処理の行数（ハロー 1 行を上下に持つ）。 */
    private const val STRIP_ROWS = 256

    /** シャープの最大ゲイン（sharpen=1.0 で 2.0 倍の差分）。 */
    private const val SHARPEN_GAIN = 2.0f

    /** 輝度 NR の最大ブレンド率（lumaNoise=1.0 で 0.8）。 */
    private const val NR_GAIN = 0.8f

    /** 軽量経路のシャープゲイン（NR なし）。 */
    private const val LIGHT_SHARPEN_GAIN = 1.5f

    /** 適用結果（[applied]=false は全パラメータ中立・[degraded]=true は軽量経路）。 */
    data class Outcome(val applied: Boolean, val degraded: Boolean) {
        companion object {
            val Neutral = Outcome(applied = false, degraded = false)
        }
    }

    /**
     * 画素配列（ARGB_8888 パック形式・行優先）へディテールを適用する。
     * 非有限値は中立（0）へ落とす（§7.1。呼出し側の正規化を前提としない防御）。
     */
    /** 画素数が自動降格の閾値を超えるか（純関数・テスト可能な判定点）。 */
    fun usesLightPath(width: Int, height: Int): Boolean =
        width.toLong() * height.toLong() > DEGRADE_PIXEL_COUNT

    fun applyToPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        detail: EditRecipe.Detail,
    ): Outcome {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return Outcome.Neutral
        val sharpen = detail.sharpen.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        val lumaNoise = detail.lumaNoise.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        if (sharpen == 0.0 && lumaNoise == 0.0) return Outcome.Neutral

        val degraded = usesLightPath(width, height)
        if (degraded) {
            applyLight(pixels, width, height, sharpen)
        } else {
            applyFull(pixels, width, height, sharpen, lumaNoise)
        }
        return Outcome(applied = true, degraded = degraded)
    }

    /** 通常経路: 3x3 ガウス（[1,2,1]⊗[1,2,1]/16）+ unsharp / 輝度 NR。 */
    private fun applyFull(
        pixels: IntArray,
        width: Int,
        height: Int,
        sharpen: Double,
        lumaNoise: Double,
    ) {
        val amount = sharpen.toFloat() * SHARPEN_GAIN - lumaNoise.toFloat() * NR_GAIN
        if (amount == 0f) return
        processStrips(pixels, width, height) { lum, rows, x, ly ->
            val center = lum[ly * width + x]
            val blur = gaussian3x3(lum, width, rows, x, ly)
            center + amount * (center - blur)
        }
    }

    /** 軽量経路（自動降格）: 4近傍ラプラシアンのみ・NR なし。 */
    private fun applyLight(
        pixels: IntArray,
        width: Int,
        height: Int,
        sharpen: Double,
    ) {
        val amount = sharpen.toFloat() * LIGHT_SHARPEN_GAIN
        if (amount == 0f) return
        processStrips(pixels, width, height) { lum, rows, x, ly ->
            val center = lum[ly * width + x]
            val up = lum[(ly - 1).coerceAtLeast(0) * width + x]
            val down = lum[(ly + 1).coerceAtMost(rows - 1) * width + x]
            val left = lum[ly * width + (x - 1).coerceAtLeast(0)]
            val right = lum[ly * width + (x + 1).coerceAtMost(width - 1)]
            val blur = (up + down + left + right) * 0.25f
            center + amount * (center - blur)
        }
    }

    /**
     * 帯処理の共通骨格。[newLuma] は帯内の局所座標 (lum, rows, x, ly) で新しい輝度を返す。
     * 帯バッファは元画素から先に構築するため、RGB の書換えが後続画素の輝度計算へ影響しない。
     */
    private inline fun processStrips(
        pixels: IntArray,
        width: Int,
        height: Int,
        newLuma: (lum: FloatArray, rows: Int, x: Int, ly: Int) -> Float,
    ) {
        // 帯の上側ハロー行は「前の帯を書き換える前の元輝度」を引き継ぐ。
        // pixels は in-place で書き換わるため、前帯の最終行をそのまま読み直すと
        // シャープ済みの値を参照してしまい、帯境界に段差が出る（実測で検出済み）。
        var carryOriginalTopRow: FloatArray? = null
        var y0 = 0
        while (y0 < height) {
            val y1 = (y0 + STRIP_ROWS).coerceAtMost(height)
            val haloTop = (y0 - 1).coerceAtLeast(0)
            val haloBottom = y1.coerceAtMost(height - 1)
            val rows = haloBottom - haloTop + 1
            val lum = FloatArray(width * rows)
            for (y in haloTop..haloBottom) {
                val dstBase = (y - haloTop) * width
                if (y == y0 - 1 && carryOriginalTopRow != null) {
                    System.arraycopy(carryOriginalTopRow, 0, lum, dstBase, width)
                    continue
                }
                val srcBase = y * width
                for (x in 0 until width) {
                    lum[dstBase + x] = luminance(pixels[srcBase + x])
                }
            }
            // 次帯の上ハローになる「この帯の最終行」の元輝度を、書き換え前に退避する。
            val nextCarry = if (y1 < height) {
                FloatArray(width).also { dst ->
                    System.arraycopy(lum, (y1 - 1 - haloTop) * width, dst, 0, width)
                }
            } else {
                null
            }
            for (y in y0 until y1) {
                val ly = y - haloTop
                val base = y * width
                for (x in 0 until width) {
                    val center = lum[ly * width + x]
                    val value = newLuma(lum, rows, x, ly)
                    val scale = if (center > 1f) value / center else 1f
                    if (scale != 1f) pixels[base + x] = scaleRgb(pixels[base + x], scale)
                }
            }
            carryOriginalTopRow = nextCarry
            y0 = y1
        }
    }

    /** 3x3 ガウス（端はクランプ・重み [1,2,1]⊗[1,2,1]/16）。 */
    private fun gaussian3x3(lum: FloatArray, width: Int, rows: Int, x: Int, y: Int): Float {
        val xm = (x - 1).coerceAtLeast(0)
        val xp = (x + 1).coerceAtMost(width - 1)
        val ym = (y - 1).coerceAtLeast(0)
        val yp = (y + 1).coerceAtMost(rows - 1)
        val rowM = ym * width
        val row0 = y * width
        val rowP = yp * width
        val sum =
            lum[rowM + xm] + 2f * lum[rowM + x] + lum[rowM + xp] +
                2f * lum[row0 + xm] + 4f * lum[row0 + x] + 2f * lum[row0 + xp] +
                lum[rowP + xm] + 2f * lum[rowP + x] + lum[rowP + xp]
        return sum * 0.0625f
    }

    /** 8bit sRGB の輝度（Rec.601 係数・0..255）。 */
    private fun luminance(p: Int): Float {
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /** 色差を保ったまま RGB を [scale] 倍する（アルファ不変・0..255 クランプ）。 */
    private fun scaleRgb(p: Int, scale: Float): Int {
        val a = (p ushr 24) and 0xFF
        val r = ((p ushr 16 and 0xFF) * scale + 0.5f).toInt().coerceIn(0, 255)
        val g = ((p ushr 8 and 0xFF) * scale + 0.5f).toInt().coerceIn(0, 255)
        val b = ((p and 0xFF) * scale + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
