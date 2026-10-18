package com.imageshot.feature.export

import com.imageshot.core.model.ErrorCode
import com.imageshot.core.model.ImageOpResult

/**
 * MVP書き出しフォーマット（01_製品仕様書.md §6 形式マトリクス）。
 * - JPEG / PNG は初期公開区分 A。WebP は任意とし本PRでは未対応（§6 注記の通り）。
 * - HEIC はエンコーダー対応が機種別未検証のため非対応（ADR-014: 偽装をしない）。
 */
enum class ExportFormat(val mime: String, val fileExtension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    ;

    companion object {
        fun fromName(name: String): ExportFormat? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * フォーマット別の妥当性検査。失敗時にデフォルト値で無通知続行しない（ADR-014）。
 * 【既知の限界】§11 の ErrorCode 群に「要求パラメータ不正」の専用コードが無いため、
 * 設定値の範囲外は UNSUPPORTED_CODEC（unsupported export settings）に分類する。
 * 専用ErrorCodeの追加は core:model（編集不可）の変更を伴うため、別PRでのADR登録を推奨（報告書参照）。
 */
object ExportFormatValidator {
    /** 画質 1..100。PNG は可逆で画質パラメータが意味を持たないが、UI簡素化のため常に 1..100 を要求する。 */
    const val MIN_QUALITY = 1
    const val MAX_QUALITY = 100

    /** リサイズ最大長（px）の下限。極小出力は誤操作防止のため抑止。 */
    const val MIN_MAX_EDGE_PX = 64
    const val MAX_MAX_EDGE_PX = 20_000

    fun validate(format: String, quality: Int, maxEdgePx: Int?): ImageOpResult<Unit> {
        val fmt = ExportFormat.fromName(format)
            ?: return ImageOpResult.Failure(ErrorCode.UNSUPPORTED_CODEC, "unsupported export format: $format")
        if (quality !in MIN_QUALITY..MAX_QUALITY) {
            return ImageOpResult.Failure(
                ErrorCode.UNSUPPORTED_CODEC,
                "unsupported export settings: quality $quality out of $MIN_QUALITY..$MAX_QUALITY (format=${fmt.name})",
            )
        }
        if (maxEdgePx != null && maxEdgePx !in MIN_MAX_EDGE_PX..MAX_MAX_EDGE_PX) {
            return ImageOpResult.Failure(
                ErrorCode.UNSUPPORTED_CODEC,
                "unsupported export settings: maxEdgePx $maxEdgePx out of $MIN_MAX_EDGE_PX..$MAX_MAX_EDGE_PX",
            )
        }
        return ImageOpResult.Success(Unit)
    }
}
