package com.imageshot.core.model

/**
 * 編集レシピ（02_実装定義書.md §3.2 editing recipe）。
 * 不変（immutable）であり、UI/レンダラー/DBが同一の正規化構造を参照する。
 * スキーマ変更時は旧版読み出し+移行+再保存のテストを必須とする（§3.2）。
 */
@kotlinx.serialization.Serializable
data class EditRecipe(
    /** 正本スキーマバージョン。RecipeSchema.CURRENT を既定とする。 */
    val schema: Int = RecipeSchema.CURRENT,
    val documentId: String,
    /** source.fingerprint が原本の指紋（hash-or-version）。 */
    val source: RecipeSource,
    /** 作業色空間は内部計算空間。表示色空間と混同しないこと（ADR-005）。 */
    val workingColorSpace: String = WORKING_COLOR_SPACE_LINEAR_REC2020_D65,
    val develop: DevelopParams = DevelopParams(),
    val geometry: GeometryParams = GeometryParams(),
    val masks: List<MaskNode> = emptyList(),
    val displayIntent: String = "auto-sdr-hdr",
    /** 未知フィールドは破壊せず隔離保存する（§7.1 PresetNormalizer 相当の受入仕様）。 */
    val unknownExtensions: Map<String, String> = emptyMap(),
    val rendererVersion: String = "1",
) {
    @kotlinx.serialization.Serializable
    data class RecipeSource(
        val assetId: String,
        val fingerprint: String,
    )

    @kotlinx.serialization.Serializable
    data class DevelopParams(
        val processVersion: Int = 1,
        val whiteBalance: WhiteBalance = WhiteBalance(),
        val tone: Tone = Tone(),
        val curve: Curve = Curve(),
        val color: ColorParams = ColorParams(),
        val detail: Detail = Detail(),
    )

    @kotlinx.serialization.Serializable
    data class WhiteBalance(
        /** mode: as-shot / auto / custom */
        val mode: String = "as-shot",
        val tempKelvin: Double? = null,
        val tint: Double = 0.0,
    )

    @kotlinx.serialization.Serializable
    data class Tone(
        val exposureEv: Double = 0.0,
        val contrast: Double = 0.0,
        val highlights: Double = 0.0,
        val shadows: Double = 0.0,
        val whites: Double = 0.0,
        val blacks: Double = 0.0,
    )

    @kotlinx.serialization.Serializable
    data class Curve(
        val rgb: List<List<Double>> = listOf(listOf(0.0, 0.0), listOf(1.0, 1.0)),
        val r: List<List<Double>> = emptyList(),
        val g: List<List<Double>> = emptyList(),
        val b: List<List<Double>> = emptyList(),
    )

    @kotlinx.serialization.Serializable
    data class ColorParams(
        val vibrance: Double = 0.0,
        val saturation: Double = 0.0,
        /** 標準8系統×(hue/saturation/luminance)。キーは正規化済み命名のみ受理。 */
        val hsl: Map<String, Double> = emptyMap(),
    )

    @kotlinx.serialization.Serializable
    data class Detail(
        val sharpen: Double = 0.0,
        val lumaNoise: Double = 0.0,
        val chromaNoise: Double = 0.0,
    )

    @kotlinx.serialization.Serializable
    data class GeometryParams(
        /** cropNorm: [x0,y0,x1,y1] 正規化座標（0.0..1.0）。回転・反転後の正準向きで定義する。 */
        val cropNorm: List<Double> = listOf(0.0, 0.0, 1.0, 1.0),
        /**
         * 回転角（度・時計回り）。PR-28b で自由角度を実装。
         * 保存時は [EditNodeSpecs.normalizeGeometry] が [0,360) の正準域へ畳み込む
         * （90°の倍数は無劣化のピクセル再索引、それ以外は黒縁付き任意角リサンプル）。
         */
        val angleDeg: Double = 0.0,
        val mirrorX: Boolean = false,
        /** 垂直反転（M1 追加。旧データの復号では既定 false の追加フィールド）。 */
        val mirrorY: Boolean = false,
        val lensProfileId: String? = null,
        val perspective: Map<String, Double>? = null,
    )

    /**
     * 局所補正ノード（PR-29a）。正規化・検証の正本は [MaskSpecs.normalize]。
     *
     * - 座標系は正規化 [0,1]（crop ではなく常に全画面基準。geometry 適用後の画像座標を
     *   正規化した値として解釈する — 適用順は geometry → develop → masks → detail）。
     * - [parameters] / [adjustments] は正規化済みの値のみを保持する（キー順序も正準）。
     * - 追加フィールドは default 付きのため、PR-29a 以前の recipe/payload JSON はそのまま
     *   読める（serialization 後方互換・Room schema 変更なし）。
     */
    @kotlinx.serialization.Serializable
    data class MaskNode(
        /** マスク合成は再現可能なバージョン付きノード（§5.3）。 */
        val maskId: String,
        /** "linear" | "radial" | "brush"（[MaskSpecs.MASK_TYPES]）。 */
        val maskType: String,
        val version: Int = 1,
        /** パラメータは正規化・検証済みの値のみ保存する。 */
        val parameters: Map<String, Double> = emptyMap(),
        val adjustments: Map<String, Double> = emptyMap(),
        /** brush のストローク列（x/y/radius は正規化 [0,1]・radius は (0,0.5]・PR-29a）。 */
        val brushStrokes: List<BrushStroke> = emptyList(),
    )

    /**
     * ブラシの1ストローク（PR-29a）。
     * x/y/radius は正規化座標、[opacity] は 0..1。距離は画素距離を min(w,h) で正規化して評価する。
     */
    @kotlinx.serialization.Serializable
    data class BrushStroke(
        val x: Double,
        val y: Double,
        val radius: Double,
        val opacity: Double = 1.0,
    )

    companion object {
        const val WORKING_COLOR_SPACE_LINEAR_REC2020_D65 = "linear-rec2020-d65"
    }
}

/** レシピスキーマのバージョン正本。初回コミット時点では 1。 */
object RecipeSchema {
    const val CURRENT: Int = 1
}
