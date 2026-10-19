package com.imageshot.core.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * PR-5: JPEG sRGB ICC 付与（Skia 既定 + [MinimalSrgbIcc]）の device 往復テスト。
 *
 * 【実測確定（2026-10-19, TB710FU API36）】Skia の JPEG エンコーダ（Bitmap.compress）は
 * Bitmap の色空間に応じ**既定で APP2 ICC を埋め込む**（旧前提「素JPEGは無タグ」は不成立）。
 * よって本テストの契約:
 * 1) 素Bitmap → JPEG エンコード → APP2 ICC 有り（Skia 既定付与の実測）。
 * 2) その復号は非 null・sRGB（platform 既定経路における ICC 採用の実測照合）。
 * 3) 冪等性: 付与済み JPEG への [JpegIcc.insertApp2IccProfile] は null（二重付与禁止）。
 * 4) 既存 ICC を除去した JPEG に [MinimalSrgbIcc.profileBytes] を挿入 → 復号非 null・sRGB
 *    （自前生成プロファイルが Skia/BitmapFactory で棄却されず解析可能な実測）。
 *
 * 【明示・正直性】4) の「colorSpace == sRGB」は ICC 不在でも sRGB 既定で復号されるため
 * 単独では自前 ICC の採用を証明しない。4) の証明対象は「構造が解析可能（decode 成功）・
 * ICC セグメントが残存・カラースペース照合」までであり、採用の定量は golden ΔE に委ねる。
 */
class JpegIccRoundTripDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun jpegIcc_platformEmbedding_andMinimalProfileRoundTrip() {
        val source = TestImageFactory.createAsymmetricBitmap()
        val untaggedFile: File
        try {
            // ---- 1) Skia 既定の ICC 付与実測 ----
            untaggedFile = TestImageFactory.writeJpeg(context, source, "icc_skia.jpg", quality = 95)
        } finally {
            source.recycle()
        }
        val cleanBytes = untaggedFile.readBytes()
        assertTrue("JPEG が空/微小（前提不一致）", cleanBytes.size > 2)
        assertTrue(
            "素 Bitmap からの JPEG エンコードに APP2 ICC が存在しない（Skia 既定付与の実測と不一致）",
            JpegIcc.hasApp2IccProfile(cleanBytes),
        )
        Log.i(
            "JpegIccProbe",
            "skiaJPEG: hasIcc=true bytes=%d (platform default embedding confirmed)".format(cleanBytes.size),
        )

        // ---- 2) platform 既定での復号・sRGB 照合 ----
        val decodedBitmap = BitmapFactory.decodeFile(untaggedFile.absolutePath)
        assertNotNull("ICC 付き JPEG の復号が null（構造破綻）", decodedBitmap)
        decodedBitmap ?: return
        try {
            assertEquals(
                "ICC 付き復号のカラースペースが sRGB でない: ${decodedBitmap.colorSpace}",
                ColorSpace.get(ColorSpace.Named.SRGB),
                decodedBitmap.colorSpace,
            )
        } finally {
            decodedBitmap.recycle()
        }

        // ---- 3) 冪等性（二重付与禁止）----
        assertNull(
            "APP2 ICC 存在 JPEG への挿入が null でない（重複付与禁止契約）",
            JpegIcc.insertApp2IccProfile(cleanBytes, MinimalSrgbIcc.profileBytes()),
        )

        // ---- 4) 自前最小 sRGB ICC の往復（既存 ICC を除去してから挿入）----
        val stripped = stripIccApp2(cleanBytes)
        assertNotNull("APP2 ICC の除去が失敗（Skia 付与 ICC が見つからない）", stripped)
        stripped ?: return
        assertTrue(
            "除去後も ICC が残存（stripIccApp2 が不完全）",
            !JpegIcc.hasApp2IccProfile(stripped),
        )
        val profile = MinimalSrgbIcc.profileBytes()
        val tagged = JpegIcc.insertApp2IccProfile(stripped, profile)
        assertNotNull("自前 ICC プロファイルの挿入に失敗", tagged)
        tagged ?: return
        assertTrue("挿入後 byte が増えていない", tagged.size > stripped.size)

        val taggedFile = File(context.cacheDir, "icc_minimal_tagged.jpg")
        FileOutputStream(taggedFile).use { it.write(tagged) }
        try {
            val ourDecoded = BitmapFactory.decodeFile(taggedFile.absolutePath)
            assertNotNull("自前 ICC 付き JPEG の復号が null（プロファイル棄却/構造破綻の可能性）", ourDecoded)
            ourDecoded ?: return
            try {
                assertEquals(
                    "自前 ICC 付き復号のカラースペースが sRGB でない: ${ourDecoded.colorSpace}",
                    ColorSpace.get(ColorSpace.Named.SRGB),
                    ourDecoded.colorSpace,
                )
            } finally {
                ourDecoded.recycle()
            }
            Log.i(
                "JpegIccProbe",
                "minimalIcc roundtrip: OK profileBytes=%d taggedBytes=%d decode=non-null/sRGB".format(
                    profile.size,
                    tagged.size,
                ),
            )
        } finally {
            taggedFile.delete()
        }
        untaggedFile.delete()
    }

    /**
     * APP2 "ICC_PROFILE\0" セグメント列を除去した JPEG を返す（見つからない/構造破綻は null）。
     * 既存 byte は ICC セグメント部分を除き無変換で保持する。
     */
    private fun stripIccApp2(jpeg: ByteArray): ByteArray? {
        if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        data class Seg(val start: Int, val end: Int, val isIcc: Boolean)
        val segments = ArrayList<Seg>()
        var i = 2
        var anyIcc = false
        while (i < jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) return null
            val markerStart = i
            while (i < jpeg.size && jpeg[i] == 0xFF.toByte()) i++
            if (i >= jpeg.size) return null
            val marker = jpeg[i].toInt() and 0xFF
            if (marker == 0x00 || marker == 0xFF) return null
            if (marker == 0xDA || marker == 0xD9) {
                segments.add(Seg(markerStart, jpeg.size, false))
                break
            }
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                i += 1
                continue
            }
            if (i + 3 > jpeg.size) return null
            val len = ((jpeg[i + 1].toInt() and 0xFF) shl 8) or (jpeg[i + 2].toInt() and 0xFF)
            if (len < 2) return null
            val segEnd = i + 1 + len
            if (segEnd > jpeg.size) return null
            val isIcc = marker in 0xE0..0xEF && startsWithIccHeader(jpeg, i + 3)
            if (isIcc) anyIcc = true else segments.add(Seg(markerStart, segEnd, false))
            i = segEnd
        }
        if (!anyIcc) return null
        val head = jpeg.copyOfRange(0, 2)
        val keep = segments.filter { !it.isIcc }
        val out = ByteArray(
            head.size + keep.sumOf { it.end - it.start },
        )
        System.arraycopy(head, 0, out, 0, head.size)
        var w = head.size
        for (seg in keep) {
            System.arraycopy(jpeg, seg.start, out, w, seg.end - seg.start)
            w += seg.end - seg.start
        }
        return out
    }

    private fun startsWithIccHeader(jpeg: ByteArray, at: Int): Boolean {
        val header = "ICC_PROFILE".toByteArray(Charsets.US_ASCII)
        if (at + header.size > jpeg.size) return false
        for (k in header.indices) {
            if (jpeg[at + k] != header[k]) return false
        }
        return true
    }
}
