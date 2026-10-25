package com.imageshot.core.render

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/**
 * 共有デコードパス（プレビュー SimpleBitmapPhotoRenderer と 書き出し ExportBitmapRenderer の両方が使う）。
 *
 * - 入力 ICC の正本化: [ImageDecoder.setTargetColorSpace] で sRGB 強制（P3/AdobeRGB 等の入力を
 *   出力 sRGB へ変換させる。Wave-1 PR-2 の本体。変換の実測検証はデコード後の
 *   bitmap.colorSpace 照合のみで行い、検証不能な場合は colorSpaceConverted=false として
 *   呼出し側に警告させる（検証していない変換を実装済みと報告しない）。
 * - EXIF orientation: 1本の追加ストリームで androidx [ExifInterface]（InputStream）から読む。
 *   読めない場合は失敗にせず orientation=0（不明）として返し、正規化せずに警告させる。
 * - サンプリング: [ImageDecoder.setTargetSampleSize]（従来 inSampleSize と同じ数値〔2の冪〕で
 *   パリティを保つ。ピラミッド算出 maxSampleFor は呼出し側のまま）。
 * - ALLOCATOR_SHARED_MEMORY: getPixels 可能な software bitmap を保証するための指定
 *   （HARDWARE だと getPixels が失敗するため、防御的に BitmapFactory へフォールバックし、
 *   その場合は sRGB 強制変換が係らなかったことを警告する）。
 * - デコード失敗は現行失敗契約を保つ（呼出し側は ErrorCode.UNSUPPORTED_CODEC で返す）。
 *
 * 【明示・EXIF 戦略】[SourceDecodePolicy.exifStrategy] により、向き正規化を自前ピクセル転置
 * （MANUAL_APPLY）で行うか、プラットフォームデコーダ任せ（PLATFORM_APPLIED）にするか切替える。
 * ImageDecoder が URI ソースで EXIF orientation を自動適用するか否かは実機で判明させる事項であり
 * （どちらが実鳴りかは JVM 上では決められない）、既定値は MANUAL_APPLY。
 * 実機統合テストで正本が判明したら本定数は flip/削除される（orchestrator 処置）。
 * 契約: MANUAL_APPLY では自前でちょうど1回だけ正規化し、PLATFORM_APPLIED では自前適用を
 * 行わない（重複適用/片落ちのどちらもしない）。
 */
object SourceDecode {

    /**
     * ImageDecoder が software でない bitmap（≒HARDWARE 構成）を返した場合のフォールバック
     * （BitmapFactory + inPreferredConfig）を明示する警告。このフォールバックでは
     * setTargetColorSpace 相当の入力 ICC sRGB 変換が係らなかった可能性がある。
     */
    const val FALLBACK_BITMAP_FACTORY_WARNING: String =
        "ImageDecoder returned a non-software bitmap; fell back to BitmapFactory decode " +
            "(input ICC sRGB conversion not applied by ImageDecoder)"

    /** デコード結果（bitmap と、その真実性を検証するための最小メタ）。 */
    data class Result(
        val bitmap: Bitmap,
        /** sRGB 変換の実測検証結果。true は bitmap.colorSpace が sRGB と一致した場合のみ。 */
        val colorSpaceConverted: Boolean,
        /** 実測の色空間ラベル（検証不能時は実名 / "unknown" をそのまま出す）。 */
        val colorSpaceLabel: String,
        /**
         * EXIF orientation 1..8。読めない/読取失敗は 0（「不明」）。0 の場合、正規化は行わず
         * 呼出し側に警告させる（失敗にはしない）。
         */
        val sourceOrientation: Int,
        /** 手動正規化が実行されたか（MANUAL_APPLY かつ orientation >= 2）。 */
        val orientationAppliedByUs: Boolean,
        /** BitmapFactory フォールバックを使ったか（警告条件）。 */
        val usedFallback: Boolean,
        /**
         * 源画像が gainmap (Ultra HDR / ISO 21496-1) を持っていたか（decode 直後の実測）。
         * [実測] PR-22 spike: BitmapFactory / ImageDecoder（sRGB+SOFTWARE＝本クラスの
         * 同一構成）の双方が hasGainmap を自動検出する。DB schema 変更なし（07 PR-23）。
         */
        val hasGainmap: Boolean,
    )

    /**
     * 共有デコード。sampleSize = 従来の inSampleSize 相当（1 はフル解像度）。
     * 失敗時は null（呼出し側が現行の失敗契約〔UNSUPPORTED_CODEC〕へ踏み込む）。
     */
    fun decode(
        resolver: ContentResolver,
        uri: Uri,
        sampleSize: Int,
    ): Result? {
        val sample = if (sampleSize < 1) 1 else sampleSize
        val orientation = readExifOrientation(resolver, uri)
        val manualNormalize = SourceDecodePolicy.exifStrategy == ExifStrategy.MANUAL_APPLY && orientation >= 2

        // プライマリ: ImageDecoder（sRGB ターゲット・SHARED memory = software bitmap）。
        val primary: Bitmap? = runCatching {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                // PR-2 本体: 入力 ICC（P3/AdobeRGB 等）を出力 sRGB へ変換させる。
                decoder.setTargetColorSpace(srgbColorSpace())
                // inSampleSize 相当（既存ピラミッドが 2 の冪を渡す）。setTargetSize は本実装では未使用。
                decoder.setTargetSampleSize(sample)
                // getPixels 可能な software bitmap を強制（HARDWARE 禁止）。
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SHARED_MEMORY)
            }
        }.getOrNull()

        val bitmap: Bitmap
        val usedFallback: Boolean
        if (primary != null && primary.config != Bitmap.Config.HARDWARE) {
            bitmap = primary
            usedFallback = false
        } else {
            // 防御的フォールバック: HARDWARE 構成の流出（仕様外・実機在り得る差分）に対して
            // getPixels 契約が壊れないよう BitmapFactory で取り直す。この経路では ImageDecoder の
            // sRGB ターゲット変換は係っていない → 上位で FALLBACK 警告を必ず付ける。
            bitmap = decodeViaBitmapFactory(resolver, uri, sample) ?: return null
            usedFallback = true
        }

        val cs = bitmap.colorSpace
        val target = runCatching { srgbColorSpace() }.getOrNull()
        val converted = target != null && cs == target
        return Result(
            bitmap = bitmap,
            colorSpaceConverted = converted,
            colorSpaceLabel = cs?.name ?: "unknown",
            sourceOrientation = orientation,
            orientationAppliedByUs = manualNormalize,
            usedFallback = usedFallback,
            hasGainmap = bitmap.hasGainmap(),
        )
    }

    /** sRGB ColorSpace（命名色空間は常に存在するが、防御的に取得）。 */
    private fun srgbColorSpace(): ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)

    /** BitmapFactory 経由のフォールバック（software ARGB_8888・inSampleSize 継承）。失敗時 null。 */
    private fun decodeViaBitmapFactory(resolver: ContentResolver, uri: Uri, sample: Int): Bitmap? =
        runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(
                    stream,
                    null,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inSampleSize = sample
                    },
                )
            }
        }.getOrNull()

    /**
     * EXIF orientation の読み取り（1本の追加ストリーム）。
     * androidx ExifInterface 値の写像: UNDEFINED(0)/NORMAL(1) → 1、FLIP_HORIZONTAL(2)…
     * ROTATE_270(8) → そのまま 2..8。範囲外の値は NORMAL 扱い（1）。
     * 読取失敗（例外・null ストリーム）は失敗にせず 0（「不明」）を返す — 不明は不明のまま
     * 正規化せず、呼出し側が警告文字列で明示する。
     */
    fun readExifOrientation(resolver: ContentResolver, uri: Uri): Int = runCatching {
        val value = resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        } ?: ExifInterface.ORIENTATION_UNDEFINED
        when (value) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 2
            ExifInterface.ORIENTATION_ROTATE_180 -> 3
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> 4
            ExifInterface.ORIENTATION_TRANSPOSE -> 5
            ExifInterface.ORIENTATION_ROTATE_90 -> 6
            ExifInterface.ORIENTATION_TRANSVERSE -> 7
            ExifInterface.ORIENTATION_ROTATE_270 -> 8
            else -> 1 // NORMAL / UNDEFINED / 範囲外 → 1（正規化不要）
        }
    }.getOrDefault(0) // 読取失敗は「不明」（0）。恒等・警告対象。
}

enum class ExifStrategy { PLATFORM_APPLIED, MANUAL_APPLY }

/**
 * EXIF orientation の適用方針の正本。
 *
 * 【実機確定（2026-10-19, TB710FU / API 36）】ImageDecoder は content URI ソースの
 * JPEG EXIF orientation を**自動適用する**。probe 測定では orientation 5..8 の
 * raw decode が既に正立（寸法交換 48x64、corner color が直立期待と±3以内）を示した
 * （core/render androidTest: PlatformExifBehaviorProbeTest / ExifOrientationPipelineTest）。
 * よって正本は PLATFORM_APPLIED とし、自前正規化（[SourceOrientation]）を重畳させない
 * （二重回転防止）。自前正規化のコードと JVM テストは BitmapFactory フォールバック経路
 * （プラットフォーム適用が無い場合の退避手段）として保持する。
 */
object SourceDecodePolicy {
    val exifStrategy: ExifStrategy = ExifStrategy.PLATFORM_APPLIED
}
