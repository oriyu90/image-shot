package com.imageshot.core.render

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExifPassThrough] の純ロジック検証（JVM・ExifInterface インスタンス非使用）。
 *
 * 契約:
 * 1. TAGS 正本 = 07 計画書の 9タグ。GPS/location に該当する名前を絶対に含まない。
 * 2. readValues: whitelist タグだけを問い合わせ・blank は落とす（getter 注入で検証。
 *    構造上「非 whitelist タグに問い合わせしない」= GPS を含めないの構造的根拠）。
 * 3. attributesToWrite: whitelist 値 + 常に Orientation="1"（正立の明示・07 PR-23）。
 *
 * 【明示】temp-file 経由の書き込み（applyToJpegBytes）と既存 APP2/XMP への影響は
 * androidTest（実機）の担当（androidx save はファイル I O を必ず伴うため JVM では分割不能）。
 */
class ExifWhitelistTest {

    @Test
    fun `whitelist tags match the canonical plan list`() {
        val expected = listOf(
            "Make", "Model", "LensModel", "DateTime", "DateTimeOriginal",
            "FNumber", "ISOSpeedRatings", "FocalLength", "ExposureTime",
        )
        assertEquals(expected, ExifPassThrough.TAGS)
        val banned = ExifPassThrough.TAGS.filter {
            it.contains("GPS", ignoreCase = true) ||
                it.contains("Lat", ignoreCase = true) ||
                it.contains("Long", ignoreCase = true)
        }
        assertTrue("GPS/location タグは whitelist に含めない（policy）: $banned", banned.isEmpty())
    }

    @Test
    fun `readValues extracts only whitelisted non-blank values via getter injection`() {
        // 非 whitelist（GPS/Software 等）は読み出されない構造を引数記録関数で検証する。
        val queried = mutableListOf<String>()
        val values = ExifPassThrough.readValues { tag ->
            queried.add(tag)
            when (tag) {
                ExifInterface.TAG_MAKE -> "TestMake"
                ExifInterface.TAG_MODEL -> "TestModel"
                ExifInterface.TAG_F_NUMBER -> "f/2.8"
                ExifInterface.TAG_ISO_SPEED_RATINGS -> "200"
                ExifInterface.TAG_DATETIME -> "" // blank は落とす
                else -> null
            }
        }
        assertEquals(
            mapOf(
                ExifInterface.TAG_MAKE to "TestMake",
                ExifInterface.TAG_MODEL to "TestModel",
                ExifInterface.TAG_F_NUMBER to "f/2.8",
                ExifInterface.TAG_ISO_SPEED_RATINGS to "200",
            ),
            values,
        )
        // 問い合わせは whitelist のみを反復（GPS タグは絶対に読まない=絶対に書かない）。
        assertEquals(ExifPassThrough.TAGS, queried)
    }

    @Test
    fun `attributesToWrite always forces orientation 1`() {
        val result = ExifPassThrough.attributesToWrite(
            mapOf(ExifInterface.TAG_MAKE to "TestMake"),
        )
        assertEquals(ExifPassThrough.FORCED_ORIENTATION, result[ExifInterface.TAG_ORIENTATION])
        assertEquals("TestMake", result[ExifInterface.TAG_MAKE])
    }

    @Test
    fun `attributesToWrite overwrites a non-normal orientation`() {
        // 源が orientation 6 でも出力 orientation は正立(1)に強制（decode 済み正立の明示）。
        val result = ExifPassThrough.attributesToWrite(
            mapOf(ExifInterface.TAG_ORIENTATION to "6", ExifInterface.TAG_MAKE to "M"),
        )
        assertEquals(ExifPassThrough.FORCED_ORIENTATION, result[ExifInterface.TAG_ORIENTATION])
        assertEquals("M", result[ExifInterface.TAG_MAKE])
    }
}
