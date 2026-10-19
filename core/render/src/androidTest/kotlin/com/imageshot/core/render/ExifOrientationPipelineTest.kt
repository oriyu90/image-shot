package com.imageshot.core.render

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EXIF orientation 1..8 の共有デコード→手動正規化パイプラインの device テスト。
 *
 * 経路（レンダラー実装と同一）:
 *   合成 JPEG に対象 orientation タグを付与 → [SourceDecode.decode]（共有デコードパス・sample=1）
 *   → getPixels → [SourceOrientation.normalize] → 期待直立パターンとの照合。
 *
 * 【本テストが計測すること（緩和禁止・計測値の正本）】
 * - 正本は [SourceDecodePolicy.MANUAL_APPLY]（既定戦略）: デコード自体は orientation を
 *   適用せず、自前のちょうど1回の正規化で直立させる。
 * - もし実機の ImageDecoder が URI ソースで EXIF orientation を自動適用する場合、
 *   本テストは二重正規化（正規化2回相当）により失敗する。これは欠陥ではなく
 *   "実測された確認事項" であり、その場合は SourceDecodePolicy の flip と本テストの
 *   更新を orchestrator が行う。失敗メッセージは expected/actual の角四隅色・orientation・
 *   実寸を出す（PlatformExifBehaviorProbeTest の実測ログと突き合わせる）。
 * - 寸法契約: orientation 5..8 は正規化後 w/h が交換される（転置）。
 */
class ExifOrientationPipelineTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun exifOrientations1to8_manualNormalize_restoresUprightComponentOrder() {
        val failures = StringBuilder()
        for (orientation in 1..8) {
            val source = TestImageFactory.createAsymmetricBitmap()
            try {
                verifyOrientation(orientation, failures)
            } finally {
                source.recycle()
            }
        }
        assertTrue(
            "EXIF orientation 1..8 の手動正規化で直立一致しない（正本契約との不整合 / measurement 実測）:\n$failures",
            failures.isEmpty(),
        )
    }

    /** 単一 orientation の検証。不一致は [failures] に詳細を追記する（全 orientation を一度に実測）。 */
    private fun verifyOrientation(orientation: Int, failures: StringBuilder) {
        val jpegFile = TestImageFactory.writeWithExifOrientation(
            context,
            TestImageFactory.createAsymmetricBitmap(),
            orientation,
            "exif_o$orientation.jpg",
        )
        val decoded: SourceDecode.Result
        try {
            decoded = SourceDecode.decode(context.contentResolver, Uri.fromFile(jpegFile), 1)
                ?: run {
                    failures.append("o=$orientation: decode が null（共有デコードパス失敗）\n")
                    return
                }
        } finally {
            jpegFile.delete()
        }

        val raw = decoded.bitmap
        try {
            // 期待直立平面: 本テスト側で合成パターン → 同一の変換を適用した期待値
            // （「パターン生成 ⇔ 照合」の間で期待値を重複定義しない）。
            val expectedUpright = SourceOrientation.normalize(
                RecipeGeometry.PixPlane(
                    IntArray(TestImageFactory.WIDTH * TestImageFactory.HEIGHT) { i ->
                        TestImageFactory.expectedColor(i % TestImageFactory.WIDTH, i / TestImageFactory.WIDTH)
                    },
                    TestImageFactory.WIDTH,
                    TestImageFactory.HEIGHT,
                ),
                orientation,
            )
            // 対象は decoded 実画素（planeOf = getPixels 経路・レンダラーと同一方式）。
            val rawPlane = TestImageFactory.planeOf(raw)
            val actualUpright = SourceOrientation.normalize(rawPlane, orientation)

            var local = ""

            // ---- 寸法契約: 5..8 は転置で w/h 交換。----
            if (actualUpright.width != expectedUpright.width || actualUpright.height != expectedUpright.height) {
                local += "o=$orientation: 正規化後寸法不一致 expected=${expectedUpright.width}x${expectedUpright.height} " +
                    "actual=${actualUpright.width}x${actualUpright.height} " +
                    "(raw decoded=${raw.width}x${raw.height} usedFallback=${decoded.usedFallback})\n" +
                    "  → ImageDecoder の EXIF 自動適用の疑い（二重正規化は転置して寸法が元に戻る）。\n"
            }

            // ---- 内容契約（パターン内部サンプルの正典色一致・hex 併記）。----
            if (actualUpright.width == expectedUpright.width && actualUpright.height == expectedUpright.height) {
                val rows = StringBuilder()
                var mismatchCount = 0
                for ((x, y) in TestImageFactory.safeInteriorSamples(
                    expectedUpright.width,
                    expectedUpright.height,
                ) { px, py -> expectedUpright.pixels[py * expectedUpright.width + px] }) {
                    val expected = expectedUpright.pixels[y * expectedUpright.width + x]
                    val actual = actualUpright.pixels[y * actualUpright.width + x]
                    if (TestImageFactory.classify(expected) != TestImageFactory.classify(actual)) {
                        mismatchCount++
                        if (mismatchCount <= 6) {
                            rows.append(
                                "  sample(x=$x,y=$y) expected=${TestImageFactory.classifyLabel(expected)} " +
                                    "actual=${TestImageFactory.classifyLabel(actual)}\n",
                            )
                        }
                    }
                }
                if (mismatchCount > 0) {
                    local += "o=$orientation: 直立後の画素不一致 mismatchCount=$mismatchCount\n" +
                        "  正本戦略=${SourceDecodePolicy.exifStrategy} orientationAppliedByUs=${decoded.orientationAppliedByUs} " +
                        "sourceOrientation=${decoded.sourceOrientation} usedFallback=${decoded.usedFallback}\n" +
                        "  raw decoded=${raw.width}x${raw.height} rawTL=${TestImageFactory.hex(rawPlane.pixels[0])} " +
                        "rawTR=${TestImageFactory.hex(rawPlane.pixels[rawPlane.width - 1])}\n" +
                        "  正規化後 TL expected=${TestImageFactory.classifyLabel(expectedUpright.pixels[0])} " +
                        "actual=${TestImageFactory.classifyLabel(actualUpright.pixels[0])}\n" +
                        "  正規化後 TR expected=${TestImageFactory.classifyLabel(expectedUpright.pixels[expectedUpright.width - 1])} " +
                        "actual=${TestImageFactory.classifyLabel(actualUpright.pixels[actualUpright.width - 1])}\n" +
                        rows +
                        "  → ImageDecoder が EXIF orientation を自動適用している可能性（二重正規化）。" +
                        "SourceDecodePolicy の実機検証と PlatformExifBehaviorProbeTest のログ参照。\n"
                }
            }

            // ---- EXIF 読取契約（SourceDecode が対象値を読む・正規化フラグが MANUAL と整合）。----
            assertEquals(
                "orientation=$orientation: SourceDecode の EXIF 読取が不一致 sourceOrientation=${decoded.sourceOrientation}",
                orientation,
                decoded.sourceOrientation,
            )
            if (orientation >= 2) {
                assertTrue(
                    "orientation=$orientation: 手動正規化が実行されていない（orientationAppliedByUs=false）。" +
                        "正本戦略=${SourceDecodePolicy.exifStrategy}",
                    decoded.orientationAppliedByUs,
                )
            }
            failures.append(local)
        } finally {
            raw.recycle()
        }
    }
}
