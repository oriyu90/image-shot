package com.imageshot.core.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
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
 * PR-5: JPEG への sRGB ICC APP2 埋め込み（[JpegIcc] + [MinimalSrgbIcc]）の device 往復テスト。
 *
 * 1) 素Bitmap → 無タグ JPEG エンコード → [JpegIcc.insertApp2IccProfile] で
 *    [MinimalSrgbIcc.profileBytes] を挿入（既存付与なしに対して成功する＝挿入正本の検査）。
 * 2) 付与後デコードは非 null・sRGB（BitmapFactory 既定経路での実測カラースペース照合）。
 * 3) 冪等性: 既に付与済みの JPEG への2回目の挿入は null（重複付与禁止）。
 *
 * 【明示・正直性】BitmapFactory は ICC 不在でも既定 sRGB で復号するため、
 * 「colorSpace == sRGB」だけではカスタム ICCの採用を証明できない。そのため付与後の実測を
 * logcat に残す（ImageDecoder probe 復号の解析結果・canonical sRGB と同一視できるかほか）。
 * パーサ側で ICC を棄却された場合も BitmapFactory が sRGB に落とすと色差としては
 * 差分が小さく出るため、採用実績の定量 判定は後段 golden ΔE に委ねる。
 * 本テストが確実に検出するのは構造破綻（decode 失敗・insert 失敗・非 sRGB や二重付与）。
 */
class JpegIccRoundTripDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun srgbProfileEmbedding_roundTripDecodesAsSrgb() {
        val source = TestImageFactory.createAsymmetricBitmap()
        try {
            val clean = TestImageFactory.writeJpeg(context, source, "icc_untagged.jpg", quality = 95)
            val cleanBytes = clean.readBytes()
            assertTrue("素JPEGが空/微小（前提不一致）", cleanBytes.size > 2)
            assertTrue(
                "素JPEGに既に APP2 ICC がある（前提不一致・Skia 付与）",
                !hasIccApp2(cleanBytes),
            )

            val tagged = JpegIcc.insertApp2IccProfile(cleanBytes, MinimalSrgbIcc.profileBytes())
            assertNotNull(
                "APP2 ICC 挿入が null（既に付与済み or 構造破綻）: hasIccApp2=${hasIccApp2(cleanBytes)}",
                tagged,
            )
            tagged ?: return
            assertTrue("付与後が小さい（追加が反映されていない）", tagged.size > cleanBytes.size)

            val taggedFile = File(context.cacheDir, "icc_tagged.jpg")
            FileOutputStream(taggedFile).use { it.write(tagged) }
            try {
                // ---- 往復デコード（BitmapFactory 既定経路・実測カラースペース照合のみ）----
                val decodedBitmap = BitmapFactory.decodeFile(taggedFile.absolutePath)
                assertNotNull("ICC 付き JPEG の復号が null（構造破綻の可能性）", decodedBitmap)
                decodedBitmap ?: return
                try {
                    assertEquals(
                        "ICC 付き復号のカラースペースが sRGB でない: ${decodedBitmap.colorSpace}",
                        ColorSpace.get(ColorSpace.Named.SRGB),
                        decodedBitmap.colorSpace,
                    )
                    Log.i(
                        "JpegIccProbe",
                        "BitmapFactory decode: colorSpace=%s profileBytes=%d taggedBytes=%d".format(
                            decodedBitmap.colorSpace?.name,
                            MinimalSrgbIcc.profileBytes().size,
                            tagged.size,
                        ),
                    )
                } finally {
                    decodedBitmap.recycle()
                }

                // ---- ImageDecoder 側の実測 probe（target 指定なし・ICC 不改変での解析結果）----
                val probeBitmap = runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(taggedFile))
                }.getOrNull()
                if (probeBitmap != null) {
                    try {
                        val space = probeBitmap.colorSpace
                        val isCanonicalSrgb = space != null &&
                            runCatching { ColorSpace.get(ColorSpace.Named.SRGB) == space }.getOrDefault(false)
                        Log.i(
                            "JpegIccProbe",
                            "ImageDecoder probe: colorSpace=%s canonicalSrgbEquals=%b bitmapDims=%dx%d".format(
                                space?.name ?: "null", isCanonicalSrgb, probeBitmap.width, probeBitmap.height,
                            ),
                        )
                    } finally {
                        probeBitmap.recycle()
                    }
                } else {
                    Log.i("JpegIccProbe", "ImageDecoder probe decode 失敗（probe only・判定対象外）")
                }

                // ---- 冪等性: 2回目の挿入は null（重複付与禁止）----
                assertNull(
                    "既に APP2 ICC がある JPEG への2回目挿入が null でない（重複付与禁止契約）",
                    JpegIcc.insertApp2IccProfile(tagged, MinimalSrgbIcc.profileBytes()),
                )
            } finally {
                taggedFile.delete()
            }
            clean.delete()
        } finally {
            source.recycle()
        }
    }

    /** 単純走査: APP2 "ICC_PROFILE\0" ヘッダの存在（テスト側前提検査用の最小実装）。 */
    private fun hasIccApp2(jpeg: ByteArray): Boolean {
        if (jpeg.size < 4) return false
        if (jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return false
        var i = 2
        while (i + 4 <= jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) return false
            while (i < jpeg.size && jpeg[i] == 0xFF.toByte()) i++
            if (i >= jpeg.size) return false
            val marker = jpeg[i].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) return false
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                i += 1
                continue
            }
            if (i + 2 >= jpeg.size) return false
            val len = ((jpeg[i + 1].toInt() and 0xFF) shl 8) or (jpeg[i + 2].toInt() and 0xFF)
            if (len < 2) return false
            val dataStart = i + 3
            if (marker in 0xE0..0xEF && startsWithIccHeader(jpeg, dataStart)) return true
            i = i + len + 1
        }
        return false
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
