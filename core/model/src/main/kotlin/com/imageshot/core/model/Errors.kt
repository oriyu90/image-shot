package com.imageshot.core.model

/**
 * エラー/復旧仕様（01_製品仕様書.md §11）。UI は元画像の破損と表示処理失敗を区別すること。
 */
enum class ErrorCode(val uiHintKey: String) {
    UNSUPPORTED_CODEC("unsupported_codec"),
    UNSUPPORTED_CAMERA("unsupported_camera"),
    INVALID_ICC("invalid_icc"),
    MALFORMED_METADATA("malformed_metadata"),
    URI_REVOKED("uri_revoked"),
    OUT_OF_MEMORY("out_of_memory"),
    GPU_CONTEXT_LOST("gpu_context_lost"),
    EXPORT_INTERRUPTED("export_interrupted"),
    INSUFFICIENT_STORAGE("insufficient_storage"),
    PRESET_PARTIAL("preset_partial"),
}

/**
 * 操作結果（ADR-014: 音のない障害より説明可能な機能縮減を優先）。
 * 失敗時にデフォルト値で無通知続行することを禁止するため、全境界でこれを使う。
 */
sealed interface ImageOpResult<out T> {
    data class Success<T>(val value: T) : ImageOpResult<T>
    /** 中身が正しくても能力不足などで部分対応の場合（例: PRESET_PARTIAL）。 */
    data class Degraded<T>(val value: T, val code: ErrorCode, val warnings: List<String>) :
        ImageOpResult<T>
    data class Failure(val code: ErrorCode, val detail: String? = null) : ImageOpResult<Nothing>
}
