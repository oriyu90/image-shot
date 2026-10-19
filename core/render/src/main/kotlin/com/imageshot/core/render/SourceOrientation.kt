package com.imageshot.core.render

/**
 * EXIF orientation（1..8）→ ピクセル平面の正規化変換（純Kotlin・JVMテスト対象）。
 *
 * 正本は [RecipeGeometry] の既存ピクセル再索引プリミティブ（rotateCw90 / flipHorizontal /
 * flipVertical）をそのまま再利用する（同値契約: 同じピクセル索引実装を2重化しない）。
 *
 * 変換の導出（androidx.exifinterface の TAG_ORIENTATION 値1..8 の標準語彙
 * 「0th row / 0th column が正しく表示したときのどの辺になるか」から機械的に導いた最小構成。
 * 回転→反転 の順（RecipeGeometry.apply の回転→反転と同一順序・クロップなし）:
 *   1: 恒等。
 *   2: 水平反転（0th row=top, 0th col=right）。
 *   3: 180°（0th row=bottom, 0th col=right）。
 *   4: 垂直反転（0th row=bottom, 0th col=left）。
 *   5: transpose（0th row=visual 左, 0th col=visual top）。
 *      (x_d,y_d) = (ys, xs)。回転90°CW: (x1,y1) = (h−1−ys, xs) → 水平反転（幅 h）:
 *      x = h−1−(h−1−ys) = ys。よって (回転1, mirrorX, mirrorY=false)。
 *   6: 90°CW（0th row=visual 右, 0th col=visual top）: (x_d,y_d) = (h−1−ys, xs)
 *      = 回転90°CW のみ。（row0: ys=0 → x_d=h−1=右端 / col0: xs=0 → y_d=0=上端。
 *      導出より 6 が「90°CW」であることをメモリでなく式で確認した。）
 *   7: transverse（0th row=visual 右, 0th col=visual bottom）。
 *      (x_d,y_d) = (h−1−ys, w−1−xs)。回転90°CW → 垂直反転（高さ w）:
 *      y = w−1−xs。よって (回転1, mirrorX=false, mirrorY=true)。
 *   8: 270°CW = 90°CCW（0th row=visual 左, 0th col=visual bottom）:
 *      (x_d,y_d) = (ys, w−1−xs) = 回転3回（=(−1)回と同値）。
 *
 * 寸法: quarterTurnsCw ∈ {1,3} のとき dst は (h, w) に反転（rotateCw90 が寸法を交換）。
 * mirrorX/mirrorY は寸法を交換しない。
 * 不明（0・範囲外・読取失敗）は恒等に落とす（失敗しない・正規化もしない — 不明は不明のまま。
 * 呼出し側が警告文字列で明示する）。
 */
object SourceOrientation {

    /**
     * 平面変換の内表現。適用順（正本・固定）: 90°CW 回転 × [quarterTurnsCw] → 水平反転（mirrorX）
     * → 垂直反転（mirrorY）。EXIF 1..8 の全値がこの3引数でちょうど表現できる
     * （上の導出参照。transposed フラグは不要 — 回転が寸法交換を担う）。
     */
    data class PlaneTransform(
        /** 0..3 の 90°CW 刻み。 */
        val quarterTurnsCw: Int,
        val mirrorX: Boolean,
        val mirrorY: Boolean,
    ) {
        /** quarterTurnsCw が奇数なら寸法が w/h 交換される（実装上の便宜。変換自体には寄与しない）。 */
        val transposed: Boolean get() = quarterTurnsCw % 2 == 1
    }

    /** EXIF orientation 値（1..8）→ 平面変換。不明（0・範囲外）は恒等。 */
    fun toPlaneTransform(orientation: Int): PlaneTransform {
        if (orientation < 1 || orientation > 8) return PlaneTransform(0, false, false)
        return when (orientation) {
            2 -> PLANE_FLIP_HORIZONTAL
            3 -> PLANE_180
            4 -> PLANE_FLIP_VERTICAL
            5 -> TRANSPOSE
            6 -> ROTATE_90_CW
            7 -> TRANSVERSE
            8 -> ROTATE_270_CW
            else -> PLANE_NORMAL // 1
        }
    }

    /**
     * EXIF orientation に基づく正規化（ユーザー自身のジオメトリ適用前に呼ぶこと:
     * 正規化 → ユーザージオメトリの合成順を固定し、決定的にする）。
     * orientation が不明（0）でも失敗せず恒等を返す。
     */
    fun normalize(plane: RecipeGeometry.PixPlane, orientation: Int): RecipeGeometry.PixPlane {
        val t = toPlaneTransform(orientation)
        var out = plane
        if (t.quarterTurnsCw != 0) out = RecipeGeometry.rotateCw90(out, t.quarterTurnsCw)
        if (t.mirrorX) out = RecipeGeometry.flipHorizontal(out)
        if (t.mirrorY) out = RecipeGeometry.flipVertical(out)
        return out
    }

    private val PLANE_NORMAL = PlaneTransform(0, false, false)
    private val PLANE_FLIP_HORIZONTAL = PlaneTransform(0, true, false)
    private val PLANE_180 = PlaneTransform(0, true, true)
    private val PLANE_FLIP_VERTICAL = PlaneTransform(0, false, true)
    private val TRANSPOSE = PlaneTransform(1, true, false)
    private val ROTATE_90_CW = PlaneTransform(1, false, false)
    private val TRANSVERSE = PlaneTransform(1, false, true)
    private val ROTATE_270_CW = PlaneTransform(3, false, false)
}
