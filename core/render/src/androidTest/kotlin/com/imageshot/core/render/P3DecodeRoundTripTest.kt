package com.imageshot.core.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-5: Display P3 入力 → 共有デコードパス（[SourceDecode] = ImageDecoder setTargetColorSpace）
 * による sRGB 強制変換の**実行確認**の device テスト。
 *
 * 【明示・正直性】本テストは P3→sRGB 変換の実行確認であり、広色域色の数学的正確性の
 * 定量保証ではない（ΔE 定量は benchmark の golden ΔE ハーネスで行う）。
 * 検証は**実測確定（2026-10-19, TB710FU API36）**の契約で行う:
 *   契約1）[SourceDecode.Result.colorSpaceConverted]（bitmap.colorSpace が sRGB と一致した
 *         場合のみ true になる、SourceDecode の自前実測ゲート）。
 *   契約2）**経路一貫性**: ImageDecoder(setTargetColorSpace=sRGB) 復号と単純 BitmapFactory
 *         （入力 ICC に従う既定経路）の画素が ±[ROUNDING_TOLERANCE] 以内で一致する
 *         （実測 maxConvIdent=0: 両経路が同値で変換する＝setPixels に書いた int を
 *         bitmap 色空間のエンコード値として扱うため元整数との恒等契約は原理的に不成立。
 *         P3 エンコード値の広色域色は sRGB への実質変換を行う（実測 dConvOrig 最大 19））。
 */
class P3DecodeRoundTripTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun p3Jpeg_decodeConvergesToSrgb() {
        val original = createP3Pattern()
        val bitmap = Bitmap.createBitmap(
            PATTERN_W,
            PATTERN_H,
            Bitmap.Config.ARGB_8888,
            true,
            ColorSpace.get(ColorSpace.Named.DISPLAY_P3),
        )
        bitmap.setPixels(original, 0, PATTERN_W, 0, 0, PATTERN_W, PATTERN_H)
        val jpeg = try {
            TestImageFactory.writeJpeg(context, bitmap, "p3_source.jpg", quality = 95)
        } finally {
            bitmap.recycle()
        }

        val decoded = SourceDecode.decode(context.contentResolver, Uri.fromFile(jpeg), 1)
            ?: run {
                jpeg.delete()
                throw AssertionError("P3 JPEG の共有デコードパスが null（decode 失敗）")
            }
        val convertedBitmap = decoded.bitmap

        try {
            // ---- 実測1: sRGB 変換の実測検証（bitmap.colorSpace 照合のみで判定）----
            assertTrue(
                "P3→sRGB 変換が実測で検証できなかった: colorSpaceLabel=${decoded.colorSpaceLabel} " +
                    "usedFallback=${decoded.usedFallback} actualColorSpace=${convertedBitmap.colorSpace}",
                decoded.colorSpaceConverted,
            )
            assertEquals(
                "デコード後の色空間が sRGB でない: ${convertedBitmap.colorSpace}",
                ColorSpace.get(ColorSpace.Named.SRGB),
                convertedBitmap.colorSpace,
            )

            // ---- 実測2: 単純 BitmapFactory（identity 側）との画素差分 ----
            val identity = BitmapFactory.decodeFile(jpeg.absolutePath)
                ?: run {
                    throw AssertionError("BitmapFactory 単純復号が null（identity 側の比較基準が取得できない）")
                }
            val identityPlane = try {
                TestImageFactory.planeOf(identity)
            } finally {
                identity.recycle()
            }
            val convertedPlane = TestImageFactory.planeOf(convertedBitmap)
            if (convertedPlane.width != identityPlane.width || convertedPlane.height != identityPlane.height) {
                // 寸法側の実測差も無通知にしない（例外的経路の検出対象）。
                Log.i(
                    "P3Probe",
                    "dimensions differ: converted=%dx%d identity=%dx%d".format(
                        convertedPlane.width, convertedPlane.height, identityPlane.width, identityPlane.height,
                    ),
                )
            }
            check(convertedPlane.width == identityPlane.width && convertedPlane.height == identityPlane.height) {
                "サンプル座標が共通でないため差分計測不能: converted=${convertedPlane.width}x${convertedPlane.height} " +
                    "identity=${identityPlane.width}x${identityPlane.height}"
            }

            val probes = StringBuilder()
            var maxConvIdentity = 0
            var maxConvOriginal = 0
            for (index in SAMPLE_COORDS.indices) {
                val (cx, cy) = SAMPLE_COORDS[index]
                val i = cy * convertedPlane.width + cx
                val orig = PATTERN_COLORS[index]
                val conv = convertedPlane.pixels[i]
                val ident = identityPlane.pixels[i]
                val dConvOrig = maxChannelDelta(conv, orig)
                val dIdent = maxChannelDelta(ident, orig)
                val dConvIdent = maxChannelDelta(conv, ident)
                maxConvIdentity = maxOf(maxConvIdentity, dConvIdent)
                maxConvOriginal = maxOf(maxConvOriginal, dConvOrig)
                probes.append(
                    "patch=%s orig=%s conv=%s ident=%s dConvOrig=%d dIdent=%d dConvIdent=%d\n".format(
                        PATCH_NAMES[index],
                        TestImageFactory.classifyLabel(orig),
                        TestImageFactory.classifyLabel(conv),
                        TestImageFactory.classifyLabel(ident),
                        dConvOrig,
                        dIdent,
                        dConvIdent,
                    ),
                )
            }
            Log.i(
                "P3Probe",
                "P3→sRGB 実測: maxConvIdent=$maxConvIdentity maxConvOrig=$maxConvOriginal " +
                    "converted=${convertedPlane.width}x${convertedPlane.height} label=${decoded.colorSpaceLabel}\n$probes",
            )

            if (maxConvIdentity > NONTRIVIAL_DELTA) {
                Log.i(
                    "P3Probe",
                    "P3→sRGB 変換が画素を実質変更した実測あり（maxConvIdent=$maxConvIdentity > $NONTRIVIAL_DELTA）。" +
                        "数学的正確性の定量は golden ΔE ハーネスの対象。",
                )
            }
            // ---- 契約2（実測確定版）: 経路一貫性 ----
            // setPixels の int は bitmap 色空間（P3）のエンコード値として書かれるため、
            // 「元整数との恒等」は契約にしない。両デコード経路の同値性のみを要求する。
            assertTrue(
                "ImageDecoder(sRGBターゲット) と BitmapFactory(既定) の復号が不一致" +
                    "（pipeline 一貫性契約違反）: maxConvIdent=$maxConvIdentity > $ROUNDING_TOLERANCE\n$probes",
                maxConvIdentity <= ROUNDING_TOLERANCE,
            )
            if (maxConvOriginal > NONTRIVIAL_DELTA) {
                Log.i(
                    "P3Probe",
                    "広色域色での原値差非自明（maxConvOrig=$maxConvOriginal > $NONTRIVIAL_DELTA）＝" +
                        "P3→sRGB 変換が実効した実測。",
                )
            }
        } finally {
            convertedBitmap.recycle()
            jpeg.delete()
        }
    }

    /** チャンネル最大の整数差分（a/b は ARGB パック・アルファ差は無視）。 */
    private fun maxChannelDelta(a: Int, b: Int): Int = maxOf(
        Math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        Math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
        Math.abs((a and 0xFF) - (b and 0xFF)),
    )

    /**
     * P3 用の高彩度パッチパターン（3列x2行・各パッチ 16x18）。
     * 元整数は setPixels で Display P3 bitmap へ直接書き込む（意味の解釈は実測側で行う）。
     */
    private fun createP3Pattern(): IntArray {
        val colors = intArrayOf(
            TestImageFactory.COLOR_RED, TestImageFactory.COLOR_GREEN, TestImageFactory.COLOR_BLUE,
            TestImageFactory.COLOR_MAGENTA, TestImageFactory.COLOR_CYAN, TestImageFactory.COLOR_WHITE,
        )
        return IntArray(PATTERN_W * PATTERN_H) { i ->
            val x = i % PATTERN_W
            val y = i / PATTERN_W
            colors[(y * 2 / PATTERN_H) * 3 + (x * 3 / PATTERN_W)]
        }
    }

    private companion object {
        private const val PATTERN_W = 48
        private const val PATTERN_H = 36

        /** 非自明差分の閾値（チャンネル最大・これ以上は「変換が実効した証拠」）。 */
        private const val NONTRIVIAL_DELTA = 8

        /** 恒等丸め契約の許容差（JPEG 95 + 変換恒等の丸め相当・要求値）。 */
        private const val ROUNDING_TOLERANCE = 2

        private val PATCH_NAMES = listOf("R", "G", "B", "M", "C", "W")

        /** 各パッチの内部サンプル座標（パッチは 16x18・中心は境界から ≥8px）。 */
        private val SAMPLE_COORDS = listOf(
            (8 to 9), (24 to 9), (40 to 9),
            (8 to 27), (24 to 27), (40 to 27),
        )

        /** パッチ順序の元整数（実測比較に使う・パターン生成と同一の色集合）。 */
        private val PATTERN_COLORS = listOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
            0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt(),
        )
    }
}
