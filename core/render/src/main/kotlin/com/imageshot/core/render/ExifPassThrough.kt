package com.imageshot.core.render

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

/**
 * 書き出し JPEG への EXIF whitelist pass-through（B2・07 計画書 PR-23）。
 *
 * 【whitelist 正本（07 計画書どおり・追加/削減は台帳更新が必要）】
 * Make / Model / LensModel / DateTime / DateTimeOriginal / FNumber / ISO /
 * FocalLength / ExposureTime。
 * - orientation は decode で正立済みのため出力へ**常に 1** を書き込む（正立の明示）。
 * - GPS/location タグは**意図的に含めない**（常に不コピー・policy 既定 OFF、台帳に記録）。
 *   ソース側に GPS があっても出力には現れない（実機テストで確認する）。
 * - XMP（hdrgm 等）・ICC APP2 は本モジュールでは扱わない（ICC は JpegIcc/MinimalSrgbIcc、
 *   XMP は platform encode 側の保守に委ねる）。
 *
 * 【失敗時契約（07 PR-23・ADR-014）】EXIF 写しの書込失敗は書き出し自体を失敗させない
 * （撮影情報欠落は品質劣化でなく情報劣化）。renderer は警告文字列へ落とし、ジョブは続行する
 * （FAILED にしない）。読取不能な源も欠落側に倒して続行（警告に明示）。
 *
 * 【実装経路】encode 済 JPEG バイト列を cacheDir の一時ファイルへ書いて
 * androidx ExifInterface（既存依存）で setAttribute+save しバイト列を取り直す。
 * ExifInterface.saveAttributes() が既存 APP2（Skia 自動埋め込み ICC・UltraHDR 関連マーカ）へ
 * 与える影響は端末実測区分の事項（GainmapExportPolicyDeviceTest）であり、renderer は
 * 書き戻し後に JpegIcc.hasApp2IccProfile で ICC を再確認する（欠落なら再挿入）。
 */
object ExifPassThrough {

    /** whitelist タグ正本（androidx ExifInterface 定数と同文字列）。GPS/location を含まない。 */
    val TAGS: List<String> = listOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_EXPOSURE_TIME,
    )

    /** 出力 orientation の強制値（正立済み・EXIF ORIENTATION_NORMAL = "1"）。 */
    const val FORCED_ORIENTATION: String = "1"

    /**
     * whitelist 値の抽出（getter 関数注入）。JVM テストからも検証可能にするため
     * ExifInterface インスタンスを要求しない（androidx クラスは JVM で Android 依存を持つ）。
     * **whitelist 外のタグ（GPS/location・Software 等）は問い合わせすら行わない**
     * （「絶対に含めない」の構造的根拠・呼び出し側)= whitelist のみ反復する）。
     */
    fun readValues(getAttribute: (String) -> String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (tag in TAGS) {
            getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { out[tag] = it }
        }
        return out
    }

    /** ExifInterface からの抽出（回路は上位の readValues(getter)）。 */
    fun readValues(exif: ExifInterface): Map<String, String> = readValues { tag -> exif.getAttribute(tag) }

    /** 源 URI から whitelist 値を読む。読取不能（stream 開放不可/解析例外）は null。 */
    fun readSource(resolver: ContentResolver, uri: Uri): Map<String, String>? = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            readValues(ExifInterface(stream))
        } ?: emptyMap()
    }.getOrNull()

    /** 出力へ書く属性集合: whitelist 値 + 常に orientation=1。 */
    fun attributesToWrite(sourceValues: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>(sourceValues)
        out[ExifInterface.TAG_ORIENTATION] = FORCED_ORIENTATION
        return out
    }

    /**
     * encode 済 JPEG へ EXIF 属性を一時ファイル経由で書き込み、最終バイト列を返す。
     * 一時ファイルは必ず消去する（finally）。
     * 成功 = 書込後 bytes + 書いたタグ名一覧 / 失敗 = **書込前 bytes と短い理由文字列**
     * （呼出し側は書き出しを encode 前の状態で継続して警告に落とす契約）。
     */
    fun applyToJpegBytes(
        tempDirectory: File,
        jpegBytes: ByteArray,
        attributes: Map<String, String>,
    ): Outcome {
        val tempFile = try {
            File.createTempFile("imageshot_exif", ".jpg", tempDirectory)
        } catch (e: IOException) {
            return Outcome.Failure(jpegBytes, "temp file creation failed: ${e.message}")
        }
        try {
            tempFile.writeBytes(jpegBytes)
            val parsed = try {
                ExifInterface(tempFile)
            } catch (e: Exception) {
                return Outcome.Failure(jpegBytes, "EXIF parse failed: ${e.message}")
            }
            for ((tag, value) in attributes) parsed.setAttribute(tag, value)
            try {
                parsed.saveAttributes()
            } catch (e: Exception) {
                return Outcome.Failure(jpegBytes, "EXIF save failed: ${e.message}")
            }
            val finalBytes = try {
                tempFile.readBytes()
            } catch (e: IOException) {
                return Outcome.Failure(jpegBytes, "temp re-read failed: ${e.message}")
            }
            return Outcome.Success(finalBytes, attributes.keys.toList())
        } finally {
            tempFile.delete()
        }
    }

    /** EXIF 書込の結果（ADR-014: 失敗を無音にしない）。 */
    sealed class Outcome {
        data class Success(val bytes: ByteArray, val writtenTags: List<String>) : Outcome()
        data class Failure(val fallbackBytes: ByteArray, val reason: String) : Outcome()
    }
}
