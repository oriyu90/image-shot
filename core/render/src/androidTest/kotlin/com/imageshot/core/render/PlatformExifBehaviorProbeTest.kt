package com.imageshot.core.render

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

/**
 * 実機の ImageDecoder が URI ソースで EXIF orientation を自動適用するか否かの
 * 体系的な実測 Probe（**何も保証しない**・常に成功）。
 *
 *目的: SourceDecodePolicy.MANUAL_APPLY 既定の妥当性判断に必要な実測データを logcat に残す。
 * - 生デコード（[SourceDecode.decode] 直後、自前正規化前）の左上/右上画素
 * - 自前正規化（[SourceOrientation.normalize]）を適用した場合の左上/右上画素
 * - 「手動正規化を適用したときに直立側で期待される画素」（合成パターンの期待値）
 * の3組を orientation 6 / 8 の両値で出力する。ImageDecoder が自動適用するものなら
 * rawTopLeft が manualExpected と一致し（=1回の適用が済んでいる）、
 * 自動適用しないものなら生デコードがエンコード順の原画と一致する。
 *
 * 本テストは判定（assert）を行わない。正本判定は [ExifOrientationPipelineTest] が行う
 * （そちらの失敗時に本ログと突き合わせて原因を特定する）。
 */
class PlatformExifBehaviorProbeTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun probe_imageDecoderExifAuto_applyBehavior_logsEvidence() {
        for (orientation in intArrayOf(6, 8)) {
            val source = TestImageFactory.createAsymmetricBitmap()
            try {
                val jpegFile =
                    TestImageFactory.writeWithExifOrientation(context, source, orientation, "probe_o$orientation.jpg")
                val decoded = SourceDecode.decode(context.contentResolver, Uri.fromFile(jpegFile), 1)
                if (decoded == null) {
                    Log.i("ExifProbe", "orientation=$orientation decode=null (probe only, no assertion)")
                    continue
                }
                val raw = decoded.bitmap
                val rawPlane = TestImageFactory.planeOf(raw)
                val normalized: RecipeGeometry.PixPlane = runCatching {
                    SourceOrientation.normalize(rawPlane, orientation)
                }.getOrDefault(rawPlane)
                val manualExpectedTopLeft = uprightExpectedTopLeft(orientation)

                Log.i(
                    "ExifProbe",
                    "orientation=%d usedFallback=%b rawTopLeft=%08x normalizedTopLeft=%08x manualExpected=%08x".format(
                        orientation,
                        decoded.usedFallback,
                        rawPlane.pixels[0],
                        normalized.pixels[0],
                        manualExpectedTopLeft,
                    ),
                )
                Log.i(
                    "ExifProbe",
                    "orientation=%d rawTopRight=%08x normalizedTopRight=%08x raw.dimensions=%dx%d raw.colorSpace=%s".format(
                        orientation,
                        rawPlane.pixels[rawPlane.width - 1],
                        normalized.pixels[normalized.width - 1],
                        raw.width,
                        raw.height,
                        decoded.colorSpaceLabel,
                    ),
                )
                Log.i(
                    "ExifProbe",
                    "orientation=%d sourceOrientationRead=%d orientationAppliedByUs=%b strategy=%s".format(
                        orientation,
                        decoded.sourceOrientation,
                        decoded.orientationAppliedByUs,
                        SourceDecodePolicy.exifStrategy,
                    ),
                )
                jpegFile.delete()
            } finally {
                source.recycle()
            }
        }
    }

    /** 正規化（直立）側で左上にあるはずの合成パターン色（orientation 別の期待値）。 */
    private fun uprightExpectedTopLeft(orientation: Int): Int {
        val expected = SourceOrientation.normalize(
            RecipeGeometry.PixPlane(
                IntArray(TestImageFactory.WIDTH * TestImageFactory.HEIGHT) { i ->
                    TestImageFactory.expectedColor(i % TestImageFactory.WIDTH, i / TestImageFactory.WIDTH)
                },
                TestImageFactory.WIDTH,
                TestImageFactory.HEIGHT,
            ),
            orientation,
        )
        return expected.pixels[0]
    }
}
