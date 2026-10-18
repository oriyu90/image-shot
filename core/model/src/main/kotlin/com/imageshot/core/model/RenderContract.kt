package com.imageshot.core.model

/**
 * CPU/GPU の同値契約で使う描画要求/結果（02_実装定義書.md §4.2）。
 * 今回は Bitmap 基盤の暫定実装（core:render）が対象。NDK境界は別モジュールで行う。
 */
@kotlinx.serialization.Serializable
data class RenderRequest(
    val documentId: String,
    val revision: Long,
    /** 表示対象矩形（正規化座標）。0.0..1.0。 */
    val viewRect: Rect,
    /** 要求出力ピクセル幅（ピラミッド選択1つ）。 */
    val requestedPx: Int,
    /** 出力色空間識別子（例: srgb-d65, display-p3）。 */
    val targetSpace: String,
    /** draft / preview / full。プレビューと書き出しは同一ノード実装で品質のみ差分。 */
    val quality: Quality,
    /** フレームごとに最新generationId のみ適用する（ADR-011）。 */
    val generationId: Long,
    /** 取消トークン。null は取り消し不可を意味しない（明示的な CancelSignal は次PR）。 */
    val cancelToken: String? = null,
) {
    @kotlinx.serialization.Serializable
    data class Rect(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    enum class Quality { DRAFT, PREVIEW, FULL }
}

@kotlinx.serialization.Serializable
data class RenderResult(
    /** Bitamp ハンドル相当。現行実装ではそのまま androidx側に渡す識別子。 */
    val bitmapHandle: String,
    val actualSpace: String,
    val transfer: String,
    val gamut: String,
    /** HDR: 実表示に成功したときだけ有効化（ADR-006）。null は SDR。 */
    val peakNits: Double?,
    val histogram: List<Int> = emptyList(),
    val revision: Long,
    val generationId: Long,
    /** 画質劣化・降格（CPUフォールバック等）を無通知にしない（ADR-014）。 */
    val warnings: List<String> = emptyList(),
)
