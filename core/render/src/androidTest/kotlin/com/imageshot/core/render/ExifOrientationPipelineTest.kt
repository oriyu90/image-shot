package com.imageshot.core.render

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EXIF orientation 1..8 の共有デコード→正立確認の device テスト。
 *
 * 【実測確定（2026-10-19, TB710FU / API 36）】ImageDecoder は content URI ソースの
 * JPEG EXIF orientation を**自動適用する**（PlatformExifBehaviorProbeTest の probe 測定:
 * orientation 5..8 の raw decode が既に正立・寸法交換 48x64・corner 色が直立期待と±3以内）。
 * よって正本は [SourceDecodePolicy.PLATFORM_APPLIED] であり、本テストの契約は:
 *   合成 JPEG に対象 orientation タグを付与 → [SourceDecode.decode]（sample=1）
 *   → raw decode が「保存グリッドを [SourceOrientation.normalize] した直立パターン」と一致
 * （自前転置は PLATFORM 戦略では行わない。二重適用は orientationAppliedByUs=false の契約で担保）。
 *
 * 寸法契約: orientation 5..8 は decode 後 w/h が交換（転置）。1..4 は不変。
 * JPEG 圧縮劣化は [TestImageFactory.classify] による近傍色分類で許容する。
 */
class ExifOrientationPipelineTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun exifOrientations1to8_platformDecodeIsAlreadyUpright() {
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
            "EXIF orientation 1..8 の platform decode が直立一致しない（PLATFORM_APPLIED 契約との不整合）:\n$failures",
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
            // 期待直立平面: 保存グリッド → SourceOrientation.normalize した結果（正典）。
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
            // 対象は raw decode 実画素（platform が既に適用済みのため自前正規化はしない）。
            val rawPlane = TestImageFactory.planeOf(raw)

            var local = ""

            // ---- 寸法契約: 5..8 は転置で w/h 交換。1..4 は保存寸法のまま。----
            if (rawPlane.width != expectedUpright.width || rawPlane.height != expectedUpright.height) {
                local += "o=$orientation: decode 後寸法不一致 expected=${expectedUpright.width}x${expectedUpright.height} " +
                    "actual=${rawPlane.width}x${rawPlane.height} usedFallback=${decoded.usedFallback}\n" +
                    "  → platform decode の orientation 適用と正典が一致しない（ImageDecoder 挙動の変化の疑い）。\n"
            }

            // ---- 内容契約（パターン内部サンプルの正典色分類一致・hex 併記）。----
            if (rawPlane.width == expectedUpright.width && rawPlane.height == expectedUpright.height) {
                val rows = StringBuilder()
                var mismatchCount = 0
                for ((x, y) in TestImageFactory.safeInteriorSamples(
                    expectedUpright.width,
                    expectedUpright.height,
                ) { px, py -> expectedUpright.pixels[py * expectedUpright.width + px] }) {
                    val expected = expectedUpright.pixels[y * expectedUpright.width + x]
                    val actual = rawPlane.pixels[y * rawPlane.width + x]
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
                        "  戦略=${SourceDecodePolicy.exifStrategy} orientationAppliedByUs=${decoded.orientationAppliedByUs} " +
                        "sourceOrientation=${decoded.sourceOrientation} usedFallback=${decoded.usedFallback}\n" +
                        "  raw decoded=${raw.width}x${raw.height} rawTL=${TestImageFactory.hex(rawPlane.pixels[0])} " +
                        "rawTR=${TestImageFactory.hex(rawPlane.pixels[rawPlane.width - 1])}\n" +
                        "  expected TL=${TestImageFactory.classifyLabel(expectedUpright.pixels[0])} " +
                        "TR=${TestImageFactory.classifyLabel(expectedUpright.pixels[expectedUpright.width - 1])}\n" +
                        rows +
                        "  → ImageDecoder の EXIF 適用結果と正典の不一致（orientation 判定・適用方向の再検証）。\n"
                }
            }

            // ---- EXIF 読取契約（SourceDecode が対象値を読む）＋ 戦略整合契約。----
            assertEquals(
                "orientation=$orientation: SourceDecode の EXIF 読取が不一致 sourceOrientation=${decoded.sourceOrientation}",
                orientation,
                decoded.sourceOrientation,
            )
            if (orientation >= 2 && SourceDecodePolicy.exifStrategy == ExifStrategy.PLATFORM_APPLIED) {
                assertTrue(
                    "orientation=$orientation: PLATFORM_APPLIED 既定なのに自前正規化が実行された（orientationAppliedByUs=true・二重適用の疑い）",
                    !decoded.orientationAppliedByUs,
                )
            }
            failures.append(local)
        } finally {
            raw.recycle()
        }
    }
}
