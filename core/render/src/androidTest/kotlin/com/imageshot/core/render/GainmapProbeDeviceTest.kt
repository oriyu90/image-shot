package com.imageshot.core.render

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat.JPEG
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Gainmap
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * S-03 HDR spike（07 計画書 PR-22）: gainmap 観察 probe（記録専用）。
 * ADR-026（A3 stage-1 policy）判断のための実測収集のみを行い、policy の成否 assert はしない。
 *
 * 測定点（07 計画書 spike ①〜④に対応・報告 JSON の case 番号は実測ステップと一致）:
 *  1. fixture 生成: 数学合成 SDR bitmap（TestImageFactory 同系・外部依存なし）に同系の
 *     数学合成 gainmap content（64x64・ratio [1,1,1]→[3,3,3]）を付与（`.setGainmap()`・
 *     android-36 は `Gainmap(Bitmap)` コンストラクタ系＝ISO 21496-1 世代 API）→
 *     プラットフォームの compress 経路で UltraHDR JPEG を端末上で生成（出所=本テストで明記・
 *     第三者/カメラ由来の decode 互換性は本 fixture では検証しない）。
 *  2. decode 検出: 生成 UltraHDR の `BitmapFactory` / `ImageDecoder` 双方での `hasGainmap()`
 *     検出と gainmap 諸元（寸法 / ratio / gamma / displayRatio 系）の観察。
 *  3. 乗算編集+compress: 露出相当の全画素乗算を底 bitmap に適用（gainmap 自体は未修正で
 *     `compress(JPEG)`）→ 再 decode での hasGainmap / gainmap 諸元 / ファイルヘッダ保全を観察
 *     （保全可否は compress 実装の実測で・理論上の妥当性評価はしない）。
 *  4. SDR-only: 明示的に gainmap が無い bitmap の compress → 出力に hdrgm/ISO 21496 マーカが
 *     無いことを観察。
 *  5. 非乗算（コントラスト S カーブ）編集を gainmap 付きのまま圧縮した場合の出力も記録
 *     （理論上の不整合の観察 = 個別の assert はしない）。
 *
 * 付随: 端末カメラで撮影済みの /sdcard/DCIM 配下 JPEG に対する hasGainmap probe
 * （アクセス不可ならその旨を記録する。権限は追加しない）。
 *
 * 例外吸収: 各観察はテスト側で吸収し レポート JSON に `error` / `stackTop` フィールドで記録する。
 * レポート: `<targetContext>.filesDir/gainmap_probe_report.json`（run-as で取得）。
 *
 * ステータス: 【spike 完了・2026-10-25 TB710FU 実測記録済み（ADR-026 Accepted の根拠）】。
 * 品質契約（assert）は持たない記録専用。契約テスト（gainmap 保持/剝離の assert 系）は
 * PR-23 の stage-1 実装で別途整備する。
 */
@RunWith(AndroidJUnit4::class)
class GainmapProbeDeviceTest {

    private val report = JSONObject()
    private val steps = JSONArray()

    // ------------------------------------------------------------------ fixture helpers

    /** TestImageFactory 風の数学合成 SDR bitmap（executor 依存なし・決定的）。 */
    private fun synthSdrBitmap(w: Int, h: Int): Bitmap {
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

    /** 全画素を 2^(ev/0.5) 倍で乗算する（露出++ 相当の乗算編集）。 */
    private fun multiplyExposure(src: Bitmap, factor: Float): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width; val h = out.height; val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            fun ch(v: Int) = ((v * factor).coerceIn(0f, 255f)).toInt()
            px[i] = (c and 0xFF000000.toInt()) or
                (ch((c shr 16) and 0xFF) shl 16) or
                (ch((c shr 8) and 0xFF) shl 8) or
                ch(c and 0xFF)
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** コントラスト S カーブ（非乗算・枢 128 固定）。 */
    private fun contrastCurve(src: Bitmap, slope: Float): Bitmap {
        val lut = IntArray(256) { i -> (((i / 255f - 0.5f) * slope + 0.5f) * 255f).coerceIn(0f, 255f).toInt() }
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width; val h = out.height; val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (c and 0xFF000000.toInt()) or
                (lut[(c shr 16) and 0xFF] shl 16) or
                (lut[(c shr 8) and 0xFF] shl 8) or
                lut[c and 0xFF]
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    // ------------------------------------------------------------------ observation helpers

    /** ヘッダマーカの観察（UltraHDR 表示用文字列の出来/不出来のみ・符号量は記録しない）。 */
    private fun headerMarkers(file: File): JSONObject {
        val o = JSONObject()
        if (!file.isFile) { o.put("fileMissing", true); return o }
        val bytes = file.readBytes()
        o.put("size", bytes.size)
        o.put("hasXmpApp1", containsAscii(bytes, "http://ns.adobe.com/xap/1.0/"))
        o.put("hasHdrgmMarker", containsAscii(bytes, "hdrgm"))
        o.put("hasIso21496Marker", containsAscii(bytes, "urn:iso:std:iso:ts:21496"))
        o.put("hasMpfMarker", containsAscii(bytes, "MPF"))
        return o
    }

    private fun containsAscii(bytes: ByteArray, s: String): Boolean {
        val needle = s.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    /** bitmap の gainmap 諸元を観察（android-36: float[] 系 API・ISO 21496-1 世界）。 */
    private fun gainmapSpec(bmp: Bitmap): JSONObject {
        val o = JSONObject()
        o.put("hasGainmap", bmp.hasGainmap())
        val g = bmp.gainmap ?: return o
        o.put("gainmapW", g.gainmapContents.width)
        o.put("gainmapH", g.gainmapContents.height)
        o.put("contentsConfig", g.gainmapContents.config?.name)
        o.put("ratioMin", listOf(g.ratioMin[0], g.ratioMin[1], g.ratioMin[2]))
        o.put("ratioMax", listOf(g.ratioMax[0], g.ratioMax[1], g.ratioMax[2]))
        o.put("gamma", listOf(g.gamma[0], g.gamma[1], g.gamma[2]))
        o.put("minDisplayRatioForHdrTransition", g.minDisplayRatioForHdrTransition)
        o.put("displayRatioForFullHdr", g.displayRatioForFullHdr)
        o.put("gainmapDirection", g.gainmapDirection)
        o.put("alternativeImagePrimaries", g.alternativeImagePrimaries?.toString())
        return o
    }

    private fun encodeReport() {
        report.put("cases", steps)
        report.put("device", JSONObject()
            .put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT))
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.filesDir.parentFile?.takeIf { it.canWrite() }?.let {
            File(it, "files/gainmap_probe_report.json").writeText(report.toString(1))
        }
        Log.i(TAG, "gainmap probe report: ${report.toString(1).length} bytes")
    }

    private fun step(name: String): JSONObject =
        JSONObject().put("case", name).also { steps.put(it) }

    private fun runStep(name: String, block: () -> JSONObject) {
        val s = step(name)
        try { s.put("result", block()) }
        catch (t: Throwable) {
            val frames = t.stackTrace.take(6).joinToString(" | ") { it.toString() }
            s.put("error", "${t.javaClass.simpleName}: ${t.message}")
            s.put("stackTop", frames)
            Log.e(TAG, "step failed: $name", t)
        }
    }

    // ------------------------------------------------------------------ main probe

    @Test
    fun gainmapProbe() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir: File = ctx.cacheDir
        dir.mkdirs()

        runStep("1.synthesize-ultrahdr") {
            val content = synthSdrBitmap(64, 64)
            val cfg = Gainmap(content).apply {
                setRatioMin(1f, 1f, 1f)
                setRatioMax(3f, 3f, 3f)
                setGamma(1f, 1f, 1f)
                setMinDisplayRatioForHdrTransition(1f)
                setDisplayRatioForFullHdr(3f)
            }
            val sdr = synthSdrBitmap(512, 384)
            sdr.setGainmap(cfg)
            val src = File(dir, "probe_synth_ultrahdr.jpg")
            src.outputStream().use { sdr.compress(JPEG, 95, it) }
            JSONObject().put("wroteBytes", src.length()).put("synthApi", "Bitmap.setGainmap+compress(JPEG,95)")
        }

        runStep("2.decode-detect") {
            val src = File(dir, "probe_synth_ultrahdr.jpg")
            val o = JSONObject().put("header", headerMarkers(src))
            // BitmapFactory
            val bf = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options())
            o.put("bitmapFactory", gainmapSpec(bf))
            // ImageDecoder (SOFTWARE + sRGB)
            val dec = ImageDecoder.createSource(src)
            val idb = ImageDecoder.decodeBitmap(dec) { dec_, info, _source ->
                dec_.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                dec_.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
            o.put("imageDecoder", gainmapSpec(idb))
            o
        }

        runStep("3.multiply-edit-and-compress") {
            val src = File(dir, "probe_synth_ultrahdr.jpg")
            val bf = BitmapFactory.decodeFile(src.absolutePath)
            val gainmap = bf.gainmap
            val edited = multiplyExposure(bf, 1.27f)
            if (gainmap != null) edited.setGainmap(gainmap)
            val out = File(dir, "probe_multiply_out.jpg")
            out.outputStream().use { edited.compress(JPEG, 95, it) }
            val dec = BitmapFactory.decodeFile(out.absolutePath)
            JSONObject()
                .put("header", headerMarkers(out))
                .put("output", gainmapSpec(dec))
        }

        runStep("4.sdr-only-compress") {
            val src = File(dir, "probe_synth_ultrahdr.jpg")
            val bf = BitmapFactory.decodeFile(src.absolutePath)
            bf.setGainmap(null) // 明示剥離（SDR-only 出力の経路・第 3 までの観察とは別現象）
            val out = File(dir, "probe_sdr_only.jpg")
            out.outputStream().use { bf.compress(JPEG, 95, it) }
            val dec = BitmapFactory.decodeFile(out.absolutePath)
            JSONObject()
                .put("header", headerMarkers(out))
                .put("output", gainmapSpec(dec))
        }

        runStep("5.non-multiplicative-edit-observation") {
            val src = File(dir, "probe_synth_ultrahdr.jpg")
            val bf = BitmapFactory.decodeFile(src.absolutePath)
            val gainmap = bf.gainmap
            val edited = contrastCurve(bf, 1.35f)
            if (gainmap != null) edited.setGainmap(gainmap)
            val out = File(dir, "probe_contrast_out.jpg")
            out.outputStream().use { edited.compress(JPEG, 95, it) }
            val dec = BitmapFactory.decodeFile(out.absolutePath)
            JSONObject()
                .put("header", headerMarkers(out))
                .put("output", gainmapSpec(dec))
                .put("note", "gainmap 未修正のまま非乗算編集を pass-through した場合の観察（理論整合の評価はしない）")
        }

        runStep("6.dcim-camera-gainmap-probe") {
            val o = JSONObject()
            val dcim = File("/sdcard/DCIM")
            val candidates = dcim.walkTopDown().maxDepth(3)
                .filter { it.isFile && it.extension.equals("jpg", true) }
                .take(6).toList()
            if (candidates.isEmpty()) {
                o.put("note", "no jpg under /sdcard/DCIM (depth<=3) or directory unreadable")
            } else {
                val arr = JSONArray()
                for (f in candidates.take(3)) {
                    val e = JSONObject().put("path", f.path.substringAfter("/sdcard/"))
                    try {
                        val b = BitmapFactory.decodeFile(f.absolutePath) ?: throw IllegalStateException("decode null")
                        e.put("hasGainmap", b.hasGainmap())
                        e.put("w", b.width).put("h", b.height)
                    } catch (t: Throwable) {
                        e.put("error", "${t.javaClass.simpleName}: ${t.message}")
                    }
                    arr.put(e)
                }
                o.put("probe", arr)
            }
            o
        }

        encodeReport()
    }

    companion object { private const val TAG = "GainmapProbe" }
}
