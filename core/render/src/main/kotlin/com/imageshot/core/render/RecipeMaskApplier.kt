package com.imageshot.core.render

import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.MaskSpecs

/**
 * 局所マスク（D3）の合成 stage（PR-29b・純 Kotlin / JVM テスト対象）。
 *
 * 正本順序: **geometry → develop → masks → detail → resize**
 * （プレビュー [SimpleBitmapPhotoRenderer] と書き出し [ExportBitmapRenderer] の両方が
 * develop 直後・detail 直前に本 stage を挿入する。02 §4「local masks & masks' adjustments」）。
 *
 * 合成規則:
 * - レシピの `masks` の**順に逐次合成**する（前のマスクの出力が次のマスクの base になる）。
 * - 各マスクの `adjustments` から [EditRecipe.DevelopParams]（tone/WB/color のみ）を構築し、
 *   develop と同一の共有画素適用器（[RecipeDevelopApplier.DevelopStage]）で per-pixel 適用する。
 * - ブレンド: `out = base + (adj − base) * w`（8bit 各チャネル・四捨五入・0..255 クランプ・
 *   アルファは base のまま）。`w <= 0` は画素を変更せず、`w >= 1` は adj をそのまま採用する。
 * - 正規化できないマスクは適用せずスキップし、[Outcome.maskCount] と [Outcome.applied] の差で
 *   明示する（無通知に捨てない = ADR-014。警告文言は
 *   [RecipeDevelopApplier.unappliedNodeWarnings] が担当）。
 *
 * 重みのキャッシュ（[MaskWeightCache]）:
 * - 画素数 <= 4MP は全面の重み配列を LRU（上限 24MB）へキャッシュする（preview 尺の再描画向け）。
 * - 画素数 > 4MP はキャッシュせず、256 行の帯ごとに重みを計算してメモリを抑える
 *   （48MP 級の書き出しで 4byte/px × 画素数の追加確保をしない）。
 * - いずれも結果は同一（per-pixel 独立）。
 */
object RecipeMaskApplier {

    /**
     * 適用結果。
     * @param maskCount 入力のマスク数
     * @param applied 正規化に成功して合成処理を行ったマスク数（adjustments が空の no-op も含む）
     */
    data class Outcome(
        val applied: Int,
        val maskCount: Int,
    ) {
        val unapplied: Int get() = maskCount - applied
        val isFullyApplied: Boolean get() = unapplied == 0
    }

    /**
     * 画素配列（ARGB_8888 パック）へ masks を順に合成する。
     * [masks] が空なら何もしない（既存レシピの出力は完全に不変）。
     */
    fun applyToPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        masks: List<EditRecipe.MaskNode>,
    ): Outcome = applyToPixelsInternal(
        pixels = pixels,
        width = width,
        height = height,
        masks = masks,
        forceBanded = false,
        bandRows = MaskWeightCache.BAND_ROWS,
    )

    /**
     * [applyToPixels] の実体（テストが帯処理経路を強制できるよう分離）。
     * [forceBanded]=true または画素数 > [MaskWeightCache.MAX_CACHED_PIXELS] で帯処理を使う。
     */
    internal fun applyToPixelsInternal(
        pixels: IntArray,
        width: Int,
        height: Int,
        masks: List<EditRecipe.MaskNode>,
        forceBanded: Boolean,
        bandRows: Int,
    ): Outcome {
        if (masks.isEmpty()) return Outcome(applied = 0, maskCount = 0)
        if (width <= 0 || height <= 0) return Outcome(applied = 0, maskCount = masks.size)
        val total = width.toLong() * height.toLong()
        if (total > pixels.size.toLong()) return Outcome(applied = 0, maskCount = masks.size)

        val cacheable = !forceBanded && total <= MaskWeightCache.MAX_CACHED_PIXELS
        var applied = 0
        for (node in masks) {
            val normalized = MaskSpecs.normalize(node) ?: continue
            if (normalized.adjustments.isEmpty()) {
                // 調整値が無いマスクは恒等（重みを計算しても out == base）。
                applied++
                continue
            }
            val stage = RecipeDevelopApplier.DevelopStage(developOf(normalized.adjustments))
            if (cacheable) {
                val weights = MaskWeightCache.weights(normalized, width, height)
                val n = width * height
                for (i in 0 until n) {
                    pixels[i] = blend(pixels[i], weights[i], stage)
                }
            } else {
                applyBanded(pixels, width, height, normalized, stage, bandRows)
            }
            applied++
        }
        return Outcome(applied = applied, maskCount = masks.size)
    }

    /**
     * マスクの adjustments（レシピドメイン値）→ develop パラメータ。
     * 触れるのは tone / whiteBalance / color のみ（curve/detail は対象外）。
     * 値域は [MaskSpecs.normalize] が検証済みだが、防御的にクランプする。
     */
    fun developOf(adjustments: Map<String, Double>): EditRecipe.DevelopParams {
        fun value(path: String): Double {
            val raw = adjustments[path] ?: return 0.0
            if (!raw.isFinite()) return 0.0
            val range = MaskSpecs.adjustmentRange(path) ?: return 0.0
            return raw.coerceIn(range.start, range.endInclusive)
        }

        val tempRaw = adjustments["develop.whiteBalance.tempKelvin"]
        val temp = if (tempRaw != null && tempRaw.isFinite()) value("develop.whiteBalance.tempKelvin") else null
        return EditRecipe.DevelopParams(
            tone = EditRecipe.Tone(
                exposureEv = value("develop.tone.exposureEv"),
                contrast = value("develop.tone.contrast"),
                highlights = value("develop.tone.highlights"),
                shadows = value("develop.tone.shadows"),
                whites = value("develop.tone.whites"),
                blacks = value("develop.tone.blacks"),
            ),
            whiteBalance = EditRecipe.WhiteBalance(
                mode = if (temp != null) "custom" else "as-shot",
                tempKelvin = temp,
                tint = value("develop.whiteBalance.tint"),
            ),
            color = EditRecipe.ColorParams(
                vibrance = value("develop.color.vibrance"),
                saturation = value("develop.color.saturation"),
            ),
        )
    }

    // ---------------------------------------------------------------- 内部実装

    /** 1 画素の合成（`w<=0` は不変・`w>=1` は adj・中間は 8bit 四捨五入ブレンド）。 */
    private fun blend(base: Int, weight: Float, stage: RecipeDevelopApplier.DevelopStage): Int {
        if (!(weight > 0f)) return base
        val adj = stage.apply(base)
        if (weight >= 1f) return adj
        val alpha = (base ushr 24) and 0xFF
        val br = (base ushr 16) and 0xFF
        val bg = (base ushr 8) and 0xFF
        val bb = base and 0xFF
        val ar = (adj ushr 16) and 0xFF
        val ag = (adj ushr 8) and 0xFF
        val ab = adj and 0xFF
        val r = (br + (ar - br) * weight + 0.5f).toInt().coerceIn(0, 255)
        val g = (bg + (ag - bg) * weight + 0.5f).toInt().coerceIn(0, 255)
        val b = (bb + (ab - bb) * weight + 0.5f).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 大画像（> 4MP）の帯処理（256 行ずつ重みを計算し、その場で合成する）。 */
    private fun applyBanded(
        pixels: IntArray,
        width: Int,
        height: Int,
        node: EditRecipe.MaskNode,
        stage: RecipeDevelopApplier.DevelopStage,
        bandRows: Int,
    ) {
        val rowsPerBand = if (bandRows > 0) bandRows else height
        val band = FloatArray(minOf(rowsPerBand, height) * width)
        var y0 = 0
        while (y0 < height) {
            val rows = minOf(rowsPerBand, height - y0)
            for (dy in 0 until rows) {
                // 帯バッファの該当行へ直接書く（行ごとに確保しない）。
                MaskWeight.weightRow(node, y0 + dy, width, height, band, dy * width)
            }
            for (dy in 0 until rows) {
                val pixelOffset = (y0 + dy) * width
                val weightOffset = dy * width
                for (x in 0 until width) {
                    val i = pixelOffset + x
                    pixels[i] = blend(pixels[i], band[weightOffset + x], stage)
                }
            }
            y0 += rows
        }
    }
}

/**
 * マスク重み配列の LRU キャッシュ（PR-29b）。
 *
 * - キー: `maskId|version|paramsHash|w|h`。`paramsHash` は**重みに影響する内容**
 *   （maskType・parameters・brushStrokes）の安定ハッシュ。adjustments は重みに影響しないため
 *   意図的に含めない（調整値だけの変更でキャッシュを捨てない）。
 * - 上限: 24MB（FloatArray は 4byte/px）。超過時は最も古い参照のエントリから破棄する。
 * - スレッド安全（全操作を synchronized）。返す配列は**読み取り専用**として扱うこと
 *   （呼出し側で書き換えない。書換えが必要なら [clear] して再取得する）。
 */
object MaskWeightCache {

    /** キャッシュ総量の上限（バイト）。 */
    const val MAX_BYTES: Long = 24L * 1024L * 1024L

    /** 全面キャッシュを行う画素数の上限（これ超は帯処理へ回す）。 */
    const val MAX_CACHED_PIXELS: Long = 4_000_000L

    /** 帯処理の行数。 */
    const val BAND_ROWS: Int = 256

    /** エントリ数上限（小さい画像の細かいキャッシュで溢れないための防御）。 */
    private const val MAX_ENTRIES = 64

    private val lock = Any()
    private var bytes = 0L

    /** accessOrder=true の LinkedHashMap で LRU を表現する（先頭が最も古い参照）。 */
    private val entries = LinkedHashMap<String, FloatArray>(16, 0.75f, true)

    /**
     * 正規化済みノード [node] の重み配列（長さ `width*height`・行優先）を返す。
     * 画素数が [MAX_CACHED_PIXELS] を超える場合はキャッシュせず都度計算する（帯処理側の責務）。
     */
    fun weights(node: EditRecipe.MaskNode, width: Int, height: Int): FloatArray = synchronized(lock) {
        val key = keyOf(node, width, height)
        entries[key]?.let { return it }
        val computed = computeWeights(node, width, height)
        bytes += computed.size.toLong() * 4L
        entries[key] = computed
        trim()
        return computed
    }

    /** キャッシュを空にする（テスト・documentId 切替時の解放用）。 */
    fun clear() = synchronized(lock) {
        entries.clear()
        bytes = 0L
    }

    /** 現在のキャッシュ使用量（テスト・診断用）。 */
    fun sizeBytes(): Long = synchronized(lock) { bytes }

    /** 現在のエントリ数（テスト・診断用）。 */
    fun entryCount(): Int = synchronized(lock) { entries.size }

    /** キャッシュキー（テスト・診断用に公開）。 */
    fun keyOf(node: EditRecipe.MaskNode, width: Int, height: Int): String =
        "${node.maskId}|${node.version}|${paramsHash(node)}|$width|$height"

    // ---------------------------------------------------------------- 内部実装

    private fun computeWeights(node: EditRecipe.MaskNode, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * height)
        val row = FloatArray(width)
        for (y in 0 until height) {
            MaskWeight.weightRow(node, y, width, height, row)
            System.arraycopy(row, 0, out, y * width, width)
        }
        return out
    }

    private fun trim() {
        while ((entries.size > MAX_ENTRIES || bytes > MAX_BYTES) && entries.size > 1) {
            val eldestKey = entries.keys.first()
            val removed = entries.remove(eldestKey) ?: break
            bytes -= removed.size.toLong() * 4L
        }
        if (bytes < 0L) bytes = 0L
    }

    /** 重みに影響する内容（種別・parameters・brushStrokes）の安定ハッシュ（FNV-1a 64bit）。 */
    private fun paramsHash(node: EditRecipe.MaskNode): String {
        var hash = -0x340d631b7bdddcdbL // FNV offset basis
        fun mix(text: String) {
            for (ch in text) {
                hash = hash xor ch.code.toLong()
                hash *= 0x100000001b3L // FNV prime
            }
        }
        mix(node.maskType)
        // parameters は正規化済みならキー順も正準（未正規化でも決定的になるようソートする）。
        for (key in node.parameters.keys.sorted()) {
            mix("|")
            mix(key)
            mix("=")
            mix(node.parameters.getValue(key).toRawBits().toString())
        }
        for (stroke in node.brushStrokes) {
            mix("|s:")
            mix(stroke.x.toRawBits().toString())
            mix(",")
            mix(stroke.y.toRawBits().toString())
            mix(",")
            mix(stroke.radius.toRawBits().toString())
            mix(",")
            mix(stroke.opacity.toRawBits().toString())
        }
        return java.lang.Long.toHexString(hash)
    }
}
