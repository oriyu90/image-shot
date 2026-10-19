package com.imageshot.core.render

import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * androidTest 共用の合成テスト画像ファクトリ（デコード/EXIF/ICC device テストの供給源）。
 *
 * - 非対称パターン: 象限ごとに独立した色相（左上=赤 / 右上=緑 / 左下=青 / 右下=黄）+
 *   左縦8px・下横9px のマゼンタ「L」マーカー + 中心 10x10 のシアン点。
 *   90/180/270°回転と左右・上下反転がすべて色配置で区別できる（非対称性の担保）。
 * - 期待画素は [expectedColor] の純関数1点のみで定義し、生成（setPixels）と検証
 *   （getPixels の分類照合）の両方で同一関数を使う（期待値の重複定義を作らない）。
 * - JPEG 4:2:0 クロマの境界ぼかしを避けるため、検証サンプルはパターン境界から
 *   6px 以上離した内部点のみを使う（[safeInteriorSamples]）。
 * - 全テストデータは instrumentation コンテキスト所有の cacheDir のみを使う
 *   （ユーザー原本・アプリ所有の MediaStore には一切触らない）。
 */
internal object TestImageFactory {

    const val WIDTH = 64
    const val HEIGHT = 48
    const val JPEG_QUALITY = 92

    /** 正典色（相互にチャンネル差 ≥255 のため JPEG 圧縮誤差と重ならない）。 */
    val COLOR_RED: Int = 0xFFFF0000.toInt()
    val COLOR_GREEN: Int = 0xFF00FF00.toInt()
    val COLOR_BLUE: Int = 0xFF0000FF.toInt()
    val COLOR_YELLOW: Int = 0xFFFFFF00.toInt()
    val COLOR_MAGENTA: Int = 0xFFFF00FF.toInt()
    val COLOR_CYAN: Int = 0xFF00FFFF.toInt()
    val COLOR_WHITE: Int = 0xFFFFFFFF.toInt()

    /** 分類の受理上限（距離^2 最近傍。これ以上は「非正典」= 境界ぼかし等）。 */
    private const val CLASSIFY_MAX_D2 = 30_000

    /**
     * 合成パターンの期待画素（正本・純関数）。
     * x/y は ARGB_8888 の stored（エンコード順）座標。
     */
    fun expectedColor(x: Int, y: Int): Int {
        // 左縦マーカー（L の縦棒）。
        if (x < 8) return COLOR_MAGENTA
        // 下横マーカー（L の横棒）。
        if (y >= 39 && x <= 33) return COLOR_MAGENTA
        // 中心シアン点（10x10）。
        if (x in 27..36 && y in 19..28) return COLOR_CYAN
        // 象限（ユニーク色相 x4）。
        return when {
            x < 32 && y < 24 -> COLOR_RED
            x < 32 -> COLOR_BLUE
            y < 24 -> COLOR_GREEN
            else -> COLOR_YELLOW
        }
    }

    /** ARGB_8888 で合成パターンを生成（寸法固定: [WIDTH]x[HEIGHT]）。 */
    fun createAsymmetricBitmap(): Bitmap {
        val pixels = IntArray(WIDTH * HEIGHT) { i -> expectedColor(i % WIDTH, i / WIDTH) }
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        return bitmap
    }

    /** Bitmap → instrumentation cacheDir の JPEG ファイル（エンコード失敗は即座に失敗）。 */
    fun writeJpeg(context: Context, bitmap: Bitmap, name: String, quality: Int = JPEG_QUALITY): File {
        val file = File(context.cacheDir, name)
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG encode failed: $name" }
        }
        return file
    }

    /**
     * JPEG を cacheDir に書き出し、androidx [ExifInterface] で orientation タグ（1..8）を付与する。
     * 画素は回転しない（タグのみ）。書込後の読み戻し照合で自前の付けたタグが本当に写入したことを担保する。
     */
    fun writeWithExifOrientation(context: Context, bitmap: Bitmap, orientation: Int, name: String): File {
        require(orientation in 1..8) { "orientation must be 1..8: $orientation" }
        val file = writeJpeg(context, bitmap, name)
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientationConstant(orientation).toString())
        exif.saveAttributes()
        val readBack = ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        check(readBack == exifOrientationConstant(orientation)) {
            "EXIF orientation write/read-back mismatch: wrote=$orientation readBack=$readBack"
        }
        return file
    }

    /** 1..8（SourceDecode 語彙）→ androidx ExifInterface の ORIENTATION_* 定数。 */
    fun exifOrientationConstant(orientation: Int): Int = when (orientation) {
        1 -> ExifInterface.ORIENTATION_NORMAL
        2 -> ExifInterface.ORIENTATION_FLIP_HORIZONTAL
        3 -> ExifInterface.ORIENTATION_ROTATE_180
        4 -> ExifInterface.ORIENTATION_FLIP_VERTICAL
        5 -> ExifInterface.ORIENTATION_TRANSPOSE
        6 -> ExifInterface.ORIENTATION_ROTATE_90
        7 -> ExifInterface.ORIENTATION_TRANSVERSE
        8 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> error("orientation must be 1..8: $orientation")
    }

    /** Bitmap → ピクセル平面（レンダラー経路と同じ getPixels 経由）。 */
    fun planeOf(bitmap: Bitmap): RecipeGeometry.PixPlane {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return RecipeGeometry.PixPlane(pixels, bitmap.width, bitmap.height)
    }

    /**
     * 期待直立パターン上で「パターン内部（境界から6px以上離れた同色領域）」の座標一覧。
     * 条件: 期待色が正典色で、±6px（水平・垂直）の期待値がすべて同一。
     */
    fun safeInteriorSamples(width: Int, height: Int, colorAt: (Int, Int) -> Int): List<Pair<Int, Int>> {
        val samples = ArrayList<Pair<Int, Int>>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (isSafeInterior(x, y, width, height, colorAt)) samples += x to y
            }
        }
        return samples
    }

    private fun isSafeInterior(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        colorAt: (Int, Int) -> Int,
    ): Boolean {
        val base = colorAt(x, y)
        if (classify(base) == null) return false
        for (dx in -6..6) {
            val nx = x + dx
            if (nx in 0 until width && colorAt(nx, y) != base) return false
        }
        for (dy in -6..6) {
            val ny = y + dy
            if (ny in 0 until height && colorAt(x, ny) != base) return false
        }
        return true
    }

    /** 最近傍正典色への分類。距離が大きい（境界ぼかし等）は null を返す。 */
    fun classify(color: Int): Char? {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        var best: Char? = null
        var bestD2 = Int.MAX_VALUE
        fun consider(c: Int, label: Char) {
            val dr = r - ((c shr 16) and 0xFF)
            val dg = g - ((c shr 8) and 0xFF)
            val db = b - (c and 0xFF)
            val d2 = dr * dr + dg * dg + db * db
            if (d2 < bestD2) {
                bestD2 = d2
                best = label
            }
        }
        consider(COLOR_RED, 'R')
        consider(COLOR_GREEN, 'G')
        consider(COLOR_BLUE, 'B')
        consider(COLOR_YELLOW, 'Y')
        consider(COLOR_MAGENTA, 'M')
        consider(COLOR_CYAN, 'C')
        consider(COLOR_WHITE, 'W')
        return if (bestD2 <= CLASSIFY_MAX_D2) best else null
    }

    /** 人可読ラベル（正典色は代表値 hex 併記・非正典は実値 hex）。メッセージ整形専用。 */
    fun classifyLabel(color: Int): String =
        when (val label = classify(color)) {
            null -> "other(${hex(color)})"
            else -> "$label(${hex(color)})"
        }

    fun hex(color: Int): String = "0x%08X".format(color)
}
