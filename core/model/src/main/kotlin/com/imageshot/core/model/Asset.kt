package com.imageshot.core.model

/**
 * ライブラリアセット（02_実装定義書.md §3.1 Room 永続エンティティのモデル側表現）。
 * 原本は複製しない（ADR-009）。URI失効時は availability で切断状態を表す。
 */
@kotlinx.serialization.Serializable
data class Asset(
    val id: String,
    val sourceUri: String,
    val displayName: String,
    val mimeGuess: String,
    /** 拡張子ではなく、sniff（magic bytes）による判定結果。 */
    val sniffedFormat: String,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val dateTaken: Long?,
    /** RAWのときのみ。機種/圧縮方式ごとの対応判定は別テーブル（§4.1）。 */
    val rawCameraId: String?,
    val colorProfileHash: String?,
    /** sha-256 ファンガープリント。書き出し/再検証の正本指紋。 */
    val imageFingerprint: String?,
    val availability: String,
    val importedAt: Long,
) {
    companion object {
        const val AVAILABILITY_OK = "ok"
        const val AVAILABILITY_REVOKED = "revoked"
        /** 「キャッシュ表示だけで原画像が存在すると誤認しない」(§8.1) 状態。 */
        const val AVAILABILITY_DISCONNECTED = "disconnected"
    }
}

/**
 * 取り込み検査結果（§5 ImageImporter.inspect）。unsupported でも例外を投げない。
 */
@kotlinx.serialization.Serializable
data class ImportProbe(
    val uri: String,
    val displayName: String?,
    val mimeGuess: String?,
    /** sniff の結果。unknown はUNSUPPORTED登録の候補。 */
    val sniffedFormat: String,
    val sizeBytes: Long?,
    /** seek 不可プロバイダー（§8.1）: staging へのコピー判定に使う。 */
    val seekable: Boolean,
    val supported: Boolean,
    val warnings: List<String> = emptyList(),
)

/**
 * 取り込みポリシー。権限は最小化し、persistable grant は継続再オープン用の
 * 明示選択時のみ取得する（ADR-009 / §8.1）。
 */
@kotlinx.serialization.Serializable
data class ImportPolicy(
    /** ContentResolver.takePersistableUriPermission を要求するか（SAFのみtrueを推奨）。 */
    val persistUriPermission: Boolean = false,
    /** 非seekable入力を staging へコピーしてよい上限バイト数。 */
    val maxStagingBytes: Long = DEFAULT_MAX_STAGING_BYTES,
) {
    companion object {
        const val DEFAULT_MAX_STAGING_BYTES: Long = 1L * 512 * 1024 * 1024 // 512 MiB（未実測の仮上限）
    }
}
