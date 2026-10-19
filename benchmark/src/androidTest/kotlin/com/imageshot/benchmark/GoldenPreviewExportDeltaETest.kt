package com.imageshot.benchmark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.RenderRequest
import com.imageshot.core.model.RenderResult
import com.imageshot.core.render.ExportBitmapRenderer
import com.imageshot.core.render.ExportRenderOutput
import com.imageshot.core.render.ExportRenderSpec
import com.imageshot.core.render.RenderBitmapStore
import com.imageshot.core.render.SimpleBitmapPhotoRenderer
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * PR-5: golden ΔE 計測ハーネス（プレビュー/書き出しの同値契約の device 実測）。
 *
 * 経路:
 *   合成ソース JPEG（2048x1365・グラデーション+象限アクセント）
 *   → プレビュー: [SimpleBitmapPhotoRenderer]（request・共有デコード経路・requestedPx=1080）
 *   → 書き出し: [ExportBitmapRenderer]（フル解像度・JPEG quality=95・maxEdgePx=null）
 *   → CIE ΔE76（sRGB int → 線形化 → XYZ D65 → Lab → 欧式距離）の全画素計量。
 *
 * 【暫定マイルストーン（first-run-baseline・nfr_catalog への確定は別PR）】
 * 非中立ケースの閾値 mean ΔE76 < 3.0 / p95 < 6.0 は**暫定**であり、検証済みの品質目標
 * ではない。初回実行の基線取り用。違反時は全指標を failure message に出す（計測用信号）。
 * 中立（全パラメータ既定）ケースの mean < 1.0 は実契約検査:
 * プレビューと書き出しは同一ノード実装（RecipeDevelopApplier 共有）であり、隠れた
 * 分岐（片側だけの追加劣化/色差）があればここで発覚する。こちらは緩和しない。
 *
 * 測定成果物: targetContext（= com.imageshot.benchmark）の filesDir へ
 * /data/data/com.imageshot.benchmark/files/golden_dE_report.json（全ケースの実測値+パラメータ）。
 * 本クラスは JUnit4 プレーンクラス（benchmark の androidTest を MacrobenchmarkRunnerStub で
 * 実行する手動配線前提・`-e class` 指定で 1 クラスのみ計測できる）。
 */
class GoldenPreviewExportDeltaETest {

    // ------------------------------------------------------------------ 計測ケース

    @Test
    fun goldenDeltaE_nonNeutralRecipe_provisionalThresholds() {
        // 暫定: exposure +0.35EV / contrast +0.2 / 5600K（微温）/ vibrance +0.15 / master 曲線中立。
        runGoldenCase(
            case = "non-neutral(0.35EV,contrast0.2,5600K,vibrance0.15)",
            recipe = goldenRecipe(),
            meanMax = PROVISIONAL_MEAN_MAX,
            p95Max = PROVISIONAL_P95_MAX,
            provisional = true,
        )
    }

    @Test
    fun goldenDeltaE_downsampledPreview_provisionalThresholds() {
        // 実プレビューワークロード（ピラミッド sample=2・1024x683 → 書き出し寸法へ resample）。
        runGoldenCase(
            case = "downsampled-preview(540px-sample2)-non-neutral",
            recipe = goldenRecipe(),
            requestedPx = 540,
            meanMax = PROVISIONAL_MEAN_MAX,
            p95Max = PROVISIONAL_P95_MAX,
            provisional = true,
        )
    }

    @Test
    fun goldenDeltaE_neutralRecipe_identityContract() {
        // 実契約: 全パラメータ中立 → プレビューと書き出しは恒等（JPEG 再圧縮の丸め差のみ残す）。
        runGoldenCase(
            case = "neutral-defaults(identity-contract)",
            recipe = neutralRecipe(),
            meanMax = NEUTRAL_MEAN_MAX,
            p95Max = null,
            provisional = false,
        )
    }

    // ------------------------------------------------------------------ 計測本体

    private fun runGoldenCase(
        case: String,
        recipe: EditRecipe,
        requestedPx: Int = 1080,
        meanMax: Double,
        p95Max: Double?,
        provisional: Boolean,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceFile = requireNotNull(goldenSourceFile) { "合成ソース JPEG が未生成（@BeforeClass 失敗）" }

        // ---- プレビュー（SimpleBitmapPhotoRenderer・共有デコード経路）----
        val renderer =
            SimpleBitmapPhotoRenderer(context, { _ -> Uri.fromFile(sourceFile) }, RenderBitmapStore())
        val request = RenderRequest(
            documentId = "golden-deltae",
            revision = 1L,
            viewRect = RenderRequest.Rect(0.0, 0.0, 1.0, 1.0),
            requestedPx = requestedPx,
            targetSpace = "srgb-d65",
            quality = RenderRequest.Quality.PREVIEW,
            generationId = 1L,
        )
        val previewOutcome = runBlocking { renderer.render(request, recipe) }
        val previewBitmap: Bitmap = when (previewOutcome) {
            is ImageOpResult.Success<RenderResult> -> {
                val handle = previewOutcome.value.bitmapHandle
                requireNotNull(renderer.renderedBitmaps.get(handle)) {
                    "render 成功でもストアに bitmap が無い: handle=$handle"
                }
            }
            is ImageOpResult.Degraded<RenderResult> ->
                throw AssertionError("preview render degraded: code=${previewOutcome.code} warnings=${previewOutcome.warnings}")
            is ImageOpResult.Failure ->
                throw AssertionError("preview render failed: code=${previewOutcome.code} detail=${previewOutcome.detail}")
        }
        try {
            // ---- 書き出し（ExportBitmapRenderer・フル解像度・同一ノード実装）----
            val exportFile = File(context.cacheDir, "golden_export_${case.hashCode()}.jpg")
            val exportFileCreated = FileOutputStream(exportFile).use { out ->
                runBlocking {
                    ExportBitmapRenderer(context).render(
                        Uri.fromFile(sourceFile),
                        recipe,
                        ExportRenderSpec(format = "JPEG", quality = 95, maxEdgePx = null),
                        out,
                        shouldCancel = { false },
                        onStage = { _, _ -> },
                    )
                }
            }
            val exported: Bitmap = when (exportFileCreated) {
                is ImageOpResult.Success<ExportRenderOutput> ->
                    BitmapFactory.decodeFile(exportFile.absolutePath)
                        ?: throw AssertionError(
                            "export は Success でも成果物 JPEG の復号が null: ${exportFileCreated.value.warnings}",
                        )
                is ImageOpResult.Degraded<ExportRenderOutput> ->
                    throw AssertionError("export degraded: code=${exportFileCreated.code} warnings=${exportFileCreated.warnings}")
                is ImageOpResult.Failure ->
                    throw AssertionError("export failed: code=${exportFileCreated.code} detail=${exportFileCreated.detail}")
            }
            try {
                // プレビューを書き出し寸法へ resample（未一致ならスケール丸め差を実測に含める契約）。
                val previewResampled =
                    if (exported.width == previewBitmap.width && exported.height == previewBitmap.height) {
                        previewBitmap
                    } else {
                        Bitmap.createScaledBitmap(previewBitmap, exported.width, exported.height, true)
                    }
                val metrics = computeDeltaE76(previewResampled, exported)

                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "case=%s mean=%.4f p95=%.4f p99=%.4f max=%.4f pxCount=%d preview=%dx%d export=%dx%d " +
                            "exportColorSpace=%s provisional=%b thresholds(mean<%.2f, p95<%s)",
                        case,
                        metrics.mean,
                        metrics.p95,
                        metrics.p99,
                        metrics.max,
                        metrics.pxCount,
                        previewBitmap.width,
                        previewBitmap.height,
                        exported.width,
                        exported.height,
                        exported.colorSpace?.name ?: "unknown",
                        provisional,
                        meanMax,
                        p95Max?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a",
                    ),
                )

                // ---- 成果物 JSON（実測値+パラメータの honest artifact）----
                persistReport(
                    context,
                    caseJson(case, recipe, metrics, previewBitmap, exported, meanMax, p95Max, provisional),
                )

                // ---- 判定（違反時は全指標を failure message に出す・計測を緩和しない）----
                assertTrue(
                    String.format(
                        Locale.US,
                        "ΔE76 mean 逸脱 [case=%s provisional=%b] mean=%.4f threshold<%.4f p95=%.4f p99=%.4f max=%.4f pxCount=%d " +
                            "preview=%dx%d export=%dx%d（詳細は logcat GoldenDeltaE と golden_dE_report.json）",
                        case, provisional, metrics.mean, meanMax, metrics.p95, metrics.p99, metrics.max,
                        metrics.pxCount, previewBitmap.width, previewBitmap.height, exported.width, exported.height,
                    ),
                    metrics.mean < meanMax,
                )
                if (p95Max != null) {
                    assertTrue(
                        String.format(
                            Locale.US,
                            "ΔE76 p95 逸脱 [case=%s provisional=%b] p95=%.4f threshold<%.4f mean=%.4f p99=%.4f max=%.4f pxCount=%d",
                            case, provisional, metrics.p95, p95Max, metrics.mean, metrics.p99, metrics.max, metrics.pxCount,
                        ),
                        metrics.p95 < p95Max,
                    )
                }
            } finally {
                exported.recycle()
            }
            exportFile.delete()
        } finally {
            runBlocking { renderer.close("golden-deltae") }
        }
    }

    // ------------------------------------------------------------------ ΔE76 計量（純テストコード）

    /** 全画素の CIE ΔE76 指標（mean / p95 / p99 / max、nearest-rank 百分位）。 */
    private fun computeDeltaE76(preview: Bitmap, exported: Bitmap): DeltaEMetrics {
        check(preview.width == exported.width && preview.height == exported.height) {
            "ΔE76 比較の寸法が不一致: preview=${preview.width}x${preview.height} export=${exported.width}x${exported.height}"
        }
        val n = preview.width * preview.height
        val planeA = IntArray(n)
        val planeB = IntArray(n)
        preview.getPixels(planeA, 0, preview.width, 0, 0, preview.width, preview.height)
        exported.getPixels(planeB, 0, exported.width, 0, 0, exported.width, exported.height)

        val deltas = FloatArray(n)
        var sum = 0.0
        var max = 0.0f
        val lab1 = FloatArray(3)
        val lab2 = FloatArray(3)
        for (i in 0 until n) {
            labOf(planeA[i], lab1)
            labOf(planeB[i], lab2)
            val dl = lab1[0] - lab2[0]
            val da = lab1[1] - lab2[1]
            val db = lab1[2] - lab2[2]
            val d = Math.sqrt((dl * dl + da * da + db * db).toDouble()).toFloat()
            deltas[i] = d
            sum += d
            if (d > max) max = d
        }
        deltas.sort()
        val mean = sum / n
        val p95 = deltas[((n * 95 + 99) / 100).coerceIn(1, n) - 1]
        val p99 = deltas[((n * 99 + 99) / 100).coerceIn(1, n) - 1]
        return DeltaEMetrics(mean, p95, p99, max, n)
    }

    private class DeltaEMetrics(
        val mean: Double,
        val p95: Float,
        val p99: Float,
        val max: Float,
        val pxCount: Int,
    )

    /**
     * sRGB int → CIE Lab L*a*b*（D65）。
     * sRGB（IEC 61966-2-1）分段線形化 → 公称 sRGB→XYZ 行列 → D65 白点正規化 → Lab 変換。
     * ΔE76（欧式距離）の選択理由: ΔE2000 より式が簡潔であり、計測結果を文書と突き合わせる
     * 場合の表記が明確であるため（golden 定義の正本は PR-5 計測資料側に置く）。
     */
    private fun labOf(color: Int, out: FloatArray) {
        val r = LINEAR[(color shr 16) and 0xFF]
        val g = LINEAR[(color shr 8) and 0xFF]
        val b = LINEAR[color and 0xFF]
        val x = labF((0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / WHITE_D65_X)
        val y = labF(0.2126729 * r + 0.7151522 * g + 0.0721750 * b)
        val z = labF((0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / WHITE_D65_Z)
        out[0] = (116.0 * y - 16.0).toFloat()
        out[1] = (500.0 * (x - y)).toFloat()
        out[2] = (200.0 * (y - z)).toFloat()
    }

    /** CIE Lab 変換の f 関数（低域は線形セグメント・CIE 15 の分段定義）。 */
    private fun labF(t: Double): Double =
        if (t > LAB_EPS) Math.cbrt(t) else (LAB_KAPPA * t + 16.0) / 116.0

    // ------------------------------------------------------------------ recipe / ソース構成

    /** 非中立（golden）recipe: exposure +0.35EV / contrast +0.2 / 5600K（微温）/ vibrance +0.15。 */
    private fun goldenRecipe(): EditRecipe = EditRecipe(
        documentId = "golden-deltae",
        source = EditRecipe.RecipeSource(assetId = "asset-1", fingerprint = "fp-golden"),
        develop = EditRecipe.DevelopParams(
            tone = EditRecipe.Tone(exposureEv = 0.35, contrast = 0.2),
            whiteBalance = EditRecipe.WhiteBalance(mode = "custom", tempKelvin = 5600.0, tint = 0.0),
            color = EditRecipe.ColorParams(vibrance = 0.15),
        ),
        // master 曲線は既定（中立）のまま。ジオメトリも既定（無変換）。
    )

    /** 中立 recipe: EditRecipe の既定値のみ（identity 契約の検査対象）。 */
    private fun neutralRecipe(): EditRecipe = EditRecipe(
        documentId = "golden-deltae",
        source = EditRecipe.RecipeSource(assetId = "asset-1", fingerprint = "fp-golden"),
    )

    // ------------------------------------------------------------------ 成果物 JSON

    @Synchronized
    private fun persistReport(context: android.content.Context, case: JSONObject) {
        caseReports.add(case)
        val all = JSONObject().put("method", METHOD).put("cases", JSONArray(caseReports))
        try {
            val file = File(context.filesDir, "golden_dE_report.json")
            FileOutputStream(file).use { it.write(all.toString(2).toByteArray(Charsets.UTF_8)) }
            Log.i(TAG, "report written: ${file.absolutePath} cases=${caseReports.size}")
        } catch (e: Exception) {
            Log.w(TAG, "report write failed: $e")
        }
    }

    private fun caseJson(
        case: String,
        recipe: EditRecipe,
        metrics: DeltaEMetrics,
        preview: Bitmap,
        export: Bitmap,
        meanMax: Double,
        p95Max: Double?,
        provisional: Boolean,
    ): JSONObject = JSONObject()
        .put("case", case)
        .put("provisional", provisional)
        .put("method", METHOD)
        .put(
            "thresholds",
            JSONObject()
                .put("meanMax", meanMax)
                .apply { if (p95Max != null) put("p95Max", p95Max) },
        )
        .put(
            "metrics",
            JSONObject()
                .put("mean", metrics.mean)
                .put("p95", metrics.p95.toDouble())
                .put("p99", metrics.p99.toDouble())
                .put("max", metrics.max.toDouble())
                .put("pxCount", metrics.pxCount),
        )
        .put(
            "recipe",
            JSONObject()
                .put("exposureEv", recipe.develop.tone.exposureEv)
                .put("contrast", recipe.develop.tone.contrast)
                .put("tempKelvin", recipe.develop.whiteBalance.tempKelvin ?: "null")
                .put("tint", recipe.develop.whiteBalance.tint)
                .put("vibrance", recipe.develop.color.vibrance)
                .put("saturation", recipe.develop.color.saturation)
                .put("curveNeutral", recipe.develop.curve.rgb == listOf(listOf(0.0, 0.0), listOf(1.0, 1.0)))
                .put("geometryNeutral", recipe.geometry == EditRecipe.GeometryParams()),
        )
        .put(
            "preview",
            JSONObject().put("w", preview.width).put("h", preview.height),
        )
        .put(
            "export",
            JSONObject()
                .put("w", export.width)
                .put("h", export.height)
                .put("format", "JPEG")
                .put("quality", 95)
                .put("maxEdgePx", "null")
                .put("colorSpace", export.colorSpace?.name ?: "unknown"),
        )

    companion object {
        private const val TAG = "GoldenDeltaE"
        private const val METHOD = "CIE Delta-E76 (sRGB int -> linear -> XYZ D65 -> Lab)"

        private const val SRC_W = 2048
        private const val SRC_H = 1365
        private const val SRC_JPEG_QUALITY = 90

        /** 暫定閾値（first-run-baseline。nfr_catalog.md への確定移行は別PRで行う）。 */
        private const val PROVISIONAL_MEAN_MAX = 3.0
        private const val PROVISIONAL_P95_MAX = 6.0

        /** 中立ケースの実契約。プレビュー/書き出し同一ノード実装のため恒等差分のみ許す。 */
        private const val NEUTRAL_MEAN_MAX = 1.0

        private val caseReports = ArrayList<JSONObject>()
        private var goldenSourceFile: File? = null

        /** 合成ソース（2048x1365 グラデーション+象限アクセント・両テストで共用）を cacheDir に構成。 */
        @BeforeClass
        @JvmStatic
        fun buildSyntheticSource() {
            if (goldenSourceFile != null) return
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.cacheDir, "golden_source.jpg")
            if (!file.exists()) {
                val bitmap = buildSourceBitmap()
                try {
                    FileOutputStream(file).use { out ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, SRC_JPEG_QUALITY, out)) {
                            "合成ソースの JPEG エンコードに失敗"
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            goldenSourceFile = file
        }

        private fun buildSourceBitmap(): Bitmap {
            val pixels = IntArray(SRC_W * SRC_H)
            val qx = SRC_W / 2
            val qy = SRC_H / 2
            for (y in 0 until SRC_H) {
                for (x in 0 until SRC_W) {
                    var r = x * 255 / SRC_W
                    var g = y * 255 / SRC_H
                    var b = (r + g) / 2
                    when {
                        x < qx && y < qy -> r = (r + 80).coerceAtMost(255)
                        x >= qx && y < qy -> g = (g + 80).coerceAtMost(255)
                        x < qx -> b = (b + 80).coerceAtMost(255)
                        else -> { r = (r + 40).coerceAtMost(255); b = (b + 40).coerceAtMost(255) }
                    }
                    pixels[y * SRC_W + x] =
                        0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                }
            }
            val bitmap = Bitmap.createBitmap(SRC_W, SRC_H, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, SRC_W, 0, 0, SRC_W, SRC_H)
            return bitmap
        }

        /** LINEAR: sRGB 分段線形化の 256 段 LUT。 */
        private val LINEAR = DoubleArray(256) { v ->
            val c = v / 255.0
            if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }

        /** CIE 15 の分段定数（a/b セグメントの境界と勾配・Y 軸は正規化済み=1.0）。 */
        private const val LAB_EPS = 216.0 / 24389.0
        private const val LAB_KAPPA = 24389.0 / 27.0
        private const val WHITE_D65_X = 0.95047
        private const val WHITE_D65_Z = 1.08883
    }
}
