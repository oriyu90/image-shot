package com.imageshot.core.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.ImageOpResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * ADR-026 stage-1 policy（PR-23 実装）の実機契約テスト。
 *
 * 検証する契約（spike 実測〔GainmapProbeDeviceTest〕を前提とする）:
 * 1. 乗算型 tone（露出 EV）のみの編集 → 源 gainmap を**未修正のまま pass-through**:
 *   出力の再 decode で hasGainmap=true・XMP/hdrgm/ISO 21496/MPF マーカ存続。
 * 2. 非乗算編集（コントラスト）→ SDR-only: 出力に gainmap なし・全マーカ消失。
 * 3. EXIF whitelist pass-through: Make/Model/DateTimeOriginal/FNumber が写る・
 *   orientation は常に 1 に強制・**GPS/location は写らない**。
 * 4. 出力は必ず APP2 ICC を持つ（Skia 自動埋め込みか ExifInterface.saveAttributes() 後の再挿入か
 *   は不問〔端末挙動の実測区分〕）。
 * 5. 警告文言（ADR-026 確定文言）が出力成果物に付く。
 *
 * 【本テストで検証しない（実測区分の未取得項）】
 * - 源 UltraHDR に EXIF orientation >= 2 がある場合（decode の正立化が基底画素を転置する /
 *   platform が gainmap も同時転置するか未実測）→ policy はこの場合 pass-through 不成立にする
 *   （GainmapExportPolicy.classify の orientationTransformed 引数・JVM テストで分岐を検証）。
 *   実機での fixture 追加は ExifInterface.saveAttributes() が源 UltraHDR マーカを保全するかの測定と
 *   交絡するため本 PR の実機契約テストには含めない（stage-2 で課題化）。
 * - GPS 付き UltraHDR・第三者/カメラ由来源（ADR-026 制限①）。
 *
 * fixture は PR-22 spike と同一構成の自作合成（512x384 SDR+64x64 gainmap・ratio 1→3）。
 * 第三者/カメラ由来 UltraHDR は未入手（ADR-026 制限①のまま）。
 */
@RunWith(AndroidJUnit4::class)
class GainmapExportPolicyDeviceTest {

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    // -------------------------------------------------------------- fixture helpers

    /** PR-22 spike と同一構成の数学合成 SDR bitmap（決定的）。 */
    private fun synthBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val r = x * 255 / (w - 1)
            val g = y * 255 / (h - 1)
            val b = (x + y) * 255 / (w + h - 2)
            px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    /** 源 UltraHDR JPEG（EXIF なし・XMP/hdrgm/ISO/MPF マーカ付き）を cacheDir に生成。 */
    private fun makeUltraHdrSource(name: String): File {
        val context = context()
        val content = synthBitmap(64, 64)
        val cfg = Gainmap(content).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(3f, 3f, 3f)
            setGamma(1f, 1f, 1f)
        }
        val sdr = synthBitmap(512, 384).apply { setGainmap(cfg) }
        val f = File(context.cacheDir, name)
        f.outputStream().use { sdr.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return f
    }

    /** 源 SDR JPEG（EXIF whitelist 値+GPS 付与済み・orientation 6）を cacheDir に生成。 */
    private fun makeSdrSourceWithExif(name: String): File {
        val context = context()
        val f = File(context.cacheDir, name)
        f.outputStream().use { synthBitmap(320, 240).compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(f).apply {
            setAttribute(ExifInterface.TAG_MAKE, "Acme Cameras")
            setAttribute(ExifInterface.TAG_MODEL, "Model-7")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:25 10:00:00")
            // RATIONAL タグは EXIF 正形式（分子/分母）で書く（カメラ由来と同一形式）。
            setAttribute(ExifInterface.TAG_F_NUMBER, "28/10")
            setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, "200")
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "24/1")
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1/125")
            setAttribute(ExifInterface.TAG_ORIENTATION, "6")
            setLatLong(35.6812, 139.7671) // GPS: 出力には絶対に写らぬこと（policy）
            saveAttributes()
        }
        return f
    }

    private fun renderExport(
        source: File,
        recipe: EditRecipe,
        outName: String,
        maxEdgePx: Int? = null,
    ): ImageOpResult<ExportRenderOutput> {
        val out = File(context().cacheDir, "out_$outName.jpg")
        out.delete()
        val outcome = runBlocking {
            ExportBitmapRenderer(context()).render(
                Uri.fromFile(source),
                recipe,
                ExportRenderSpec(format = "JPEG", quality = 95, maxEdgePx = maxEdgePx),
                FileOutputStream(out).buffered(),
                shouldCancel = { false },
                onStage = { _, _ -> },
            )
        }
        return outcome
    }

    /** 失敗を無音にしない（ADR-014）。失敗時は code/detail を添えて fail する。 */
    private fun requireSuccess(outcome: ImageOpResult<ExportRenderOutput>): ImageOpResult.Success<ExportRenderOutput> =
        when (outcome) {
            is ImageOpResult.Success -> outcome
            is ImageOpResult.Degraded ->
                throw AssertionError("render degraded: code=${outcome.code} warnings=${outcome.warnings}")
            is ImageOpResult.Failure ->
                throw AssertionError("render failed: code=${outcome.code} detail=${outcome.detail}")
        }

    private fun recipeOf(develop: EditRecipe.DevelopParams): EditRecipe = EditRecipe(
        documentId = "policy-device-test",
        source = EditRecipe.RecipeSource(assetId = "a", fingerprint = "f"),
        develop = develop,
    )

    private fun headerMarkers(file: File): List<String> {
        val bytes = file.readBytes()
        return listOf("http://ns.adobe.com/xap/1.0/", "hdrgm", "urn:iso:std:iso:ts:21496", "MPF")
            .filter { indexOfAscii(bytes, it) >= 0 }
    }

    /** US-ASCII 部分列の先頭出現位置（見つからない場合は -1。ByteArray.indexOf は単一 Byte 用のため自前走査）。 */
    private fun indexOfAscii(bytes: ByteArray, s: String): Int {
        val pat = s.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..bytes.size - pat.size) {
            for (j in pat.indices) {
                if (bytes[i + j] != pat[j]) continue@outer
            }
            return i
        }
        return -1
    }

    // -------------------------------------------------------------- tests

    @Test
    fun multiplicativeExportPassesGainmapThrough() {
        val source = makeUltraHdrSource("p23_pass_source.jpg")
        val sourceRead = BitmapFactory.decodeFile(source.absolutePath)!!
        assertTrue("fixture 生成は hasGainmap を保証", sourceRead.hasGainmap())

        val recipe = recipeOf(EditRecipe.DevelopParams(tone = EditRecipe.Tone(exposureEv = 0.35)))
        val outcome = requireSuccess(renderExport(source, recipe, "pass"))
        val warnings = outcome.value.warnings.joinToString("\n")
        assertTrue(warnings.contains(GainmapExportPolicy.WARNING_PASS_THROUGH))

        val outFile = File(context().cacheDir, "out_pass.jpg")
        val markers = headerMarkers(outFile)
        // XMP APP1 / hdrgm / ISO 21496 / MPF が EXIF 書込み後も生存（ExifInterface.saveAttributes() の
        // マーカ影響はここで実測する）。
        assertEquals(4, markers.size)
        val decoded = BitmapFactory.decodeFile(outFile.absolutePath)!!
        assertTrue("pass-through 出力は hasGainmap=true", decoded.hasGainmap())
        assertNotNull(decoded.gainmap)
        assertTrue(JpegIcc.hasApp2IccProfile(outFile.readBytes()))
        // orientation は正立(1)に強制され EXIF APP1 が書かれる（源に EXIF なしでも書く設計）。
        val exif = ExifInterface(outFile)
        assertEquals("1", exif.getAttribute(ExifInterface.TAG_ORIENTATION))
    }

    @Test
    fun nonMultiplicativeExportStripsToSdr() {
        val source = makeUltraHdrSource("p23_strip_source.jpg")
        val recipe = recipeOf(EditRecipe.DevelopParams(tone = EditRecipe.Tone(contrast = 0.4)))
        val outcome = requireSuccess(renderExport(source, recipe, "strip"))
        val warnings = outcome.value.warnings.joinToString("\n")
        assertTrue(warnings.contains(GainmapExportPolicy.WARNING_STRIP_TO_SDR))

        val outFile = File(context().cacheDir, "out_strip.jpg")
        assertEquals("SDR-only output keeps no HDR markers", emptyList<String>(), headerMarkers(outFile))
        val decoded = BitmapFactory.decodeFile(outFile.absolutePath)!!
        assertFalse(decoded.hasGainmap())
        assertTrue(JpegIcc.hasApp2IccProfile(outFile.readBytes()))
    }

    @Test
    fun exifWhitelistCopiedAndGpsExcluded() {
        val source = makeSdrSourceWithExif("p23_exif_source.jpg")
        val recipe = recipeOf(EditRecipe.DevelopParams(tone = EditRecipe.Tone(exposureEv = 0.2)))
        val outcome = requireSuccess(renderExport(source, recipe, "exif"))
        val warnings = outcome.value.warnings.joinToString("\n")
        assertTrue("EXIF 警告行がある", warnings.contains("EXIF whitelist copied"))
        assertTrue("GPS 不コピーは警告に明示", warnings.contains("GPS/location not copied"))

        val outFile = File(context().cacheDir, "out_exif.jpg")
        val exif = ExifInterface(outFile)
        assertEquals("Acme Cameras", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("Model-7", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("2026:10:25 10:00:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        // RATIONAL/SHORT タグは androidx が読取時に正規化表現へ変える（実測: "28/10"→"2.8"）。
        // バイト列の分数表現を問わず値の意味等価（double）で検証する。
        assertEquals(2.8, exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0), 1.0e-4)
        assertEquals(24.0, exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0), 1.0e-4)
        assertEquals(0.008, exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0), 1.0e-6)
        assertEquals("200", exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS))
        // GPS/location は whitelist 外 → 出力に現れない。
        assertNullGps(exif)
        // orientation は源(6)でも出力は 1 に強制。
        assertEquals("1", exif.getAttribute(ExifInterface.TAG_ORIENTATION))
        assertTrue(JpegIcc.hasApp2IccProfile(outFile.readBytes()))
        assertTrue("源 gainmap なしの出力には HDR 警告を出さない", !warnings.contains("HDR"))
    }

    @Test
    fun resizedMultiplicativeExportKeepsGainmap() {
        // 露出（乗算）+ リサイズ（最長辺 256px）: gainmap 引き継ぎは resize を含む乗算経路でも同様
        // （fractional gainmap は基底縮尺に非依存。実測区分として本 PR で確認する）。
        val source = makeUltraHdrSource("p23_resize_source.jpg")
        val recipe = recipeOf(EditRecipe.DevelopParams(tone = EditRecipe.Tone(exposureEv = 0.35)))
        val outcome = requireSuccess(renderExport(source, recipe, "resize", maxEdgePx = 256))
        val outFile = File(context().cacheDir, "out_resize.jpg")
        val decoded = BitmapFactory.decodeFile(outFile.absolutePath)!!
        assertTrue(decoded.hasGainmap())
        assertEquals(4, headerMarkers(outFile).size)
    }

    private fun assertNullGps(exif: ExifInterface) {
        for (tag in listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
        )) {
            assertEquals("GPS タグは出力に書かない: $tag", null, exif.getAttribute(tag))
        }
    }
}
