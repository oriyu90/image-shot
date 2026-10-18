package com.imageshot.core.storage

import android.net.Uri
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.ImportPolicy
import com.imageshot.core.model.ImportProbe

/**
 * 取り込み契約（02_実装定義書.md §5 ImageImporter / 01_製品仕様書.md U01, §8）。
 * - inspect は unsupported でも例外を投げない（ADR-014: 非対応候補・URI失効・容量不足を
 *   エラー分類＋明示で返す）。
 * - register は原本を一切変更せず、参照＋選択的stagingのみ行う（ADR-009: 全写真複製なし）。
 * - sha-256 ファンガープリントは遅延実行可（U01）。取り込み応答を速くし、計算は
 *   [ensureFingerprint] で後処理する。
 * - すべてのIOは実装側で Dispatchers.IO 上で行う（main threadに置かない §10）。
 */
interface ImportRepository {
    /** ヘッダー/サイズ/対応可能性の検査。ネットワークアクセスは行わない。 */
    suspend fun inspect(uri: Uri): ImageOpResult<ImportProbe>

    /** ライブラリ登録。原本複製なし、fingerprint は sha-256（遅延計算は ensureFingerprint）。 */
    suspend fun register(uri: Uri, policy: ImportPolicy): ImageOpResult<String>

    /**
     * 遅延 sha-256 ファンガープリント計算（U01）。
     * 既に計算済みなら再計算せずその値を返す。
     */
    suspend fun ensureFingerprint(assetId: String): ImageOpResult<String>

    /** 登録済み保存値の上書きなしで単発計算。再関連付け時の同一性照合に使う。 */
    suspend fun computeFingerprint(uri: Uri): ImageOpResult<String>

    /**
     * ACTION_OPEN_DOCUMENT(_TREE) の結果 URI に対する persistable grant の単発取得。
     * 失敗時は短期権限のまま取り込みを継続させる（呼び出し側が警告表示する）。
     */
    suspend fun takePersistableReadGrant(uri: Uri): ImageOpResult<Unit>

    /**
     * ACTION_OPEN_DOCUMENT_TREE ルート直下の children を走査し、mime が image で始まるもののみ
     * 登録候補として返す。フォルダー単位取り込み（§8: ファイル種別選択は SAF）。
     */
    suspend fun listImagesInTree(treeUri: Uri): ImageOpResult<List<String>>

    /** URI 可用性検査（切断状態表示 U01）。原本の全量読み取りを行わない。 */
    suspend fun probeAvailability(uri: Uri): UriAccessibility
}
