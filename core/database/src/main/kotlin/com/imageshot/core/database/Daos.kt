package com.imageshot.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(asset: AssetEntity)

    @Query("SELECT * FROM assets ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE id = :id")
    suspend fun get(id: String): AssetEntity?

    /** URI失効判定はデータのみ更新する（原本は参照しない／削除しない）。 */
    @Query("UPDATE assets SET availability = :availability, importedAt = importedAt WHERE id = :id")
    suspend fun updateAvailability(id: String, availability: String)

    // ==== 追加（Library-Import担当 M1: 既存メソッドは変更しない追加のみ） ====

    /**
     * 時系列グリッド（撮影日順。dateTaken不明は importedAt にフォールバック）。
     * ローカル検索（表示名）を同一クエリに含める（MVP: メタデータ絞り込みはM2）。
     */
    @Query(
        "SELECT * FROM assets WHERE displayName LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY COALESCE(dateTaken, importedAt) DESC",
    )
    fun observeByDateTaken(query: String): Flow<List<AssetEntity>>

    /** ローカル検索（表示名）× 表示名順ソート（MVP. M2: メタデータ検索/キーワード）。 */
    @Query(
        "SELECT * FROM assets WHERE displayName LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY displayName COLLATE NOCASE ASC",
    )
    fun observeByDisplayName(query: String): Flow<List<AssetEntity>>

    /** 同一URIの重複登録検査（ADR-009: 原本複製なし）。 */
    @Query("SELECT * FROM assets WHERE sourceUri = :sourceUri LIMIT 1")
    suspend fun getBySourceUri(sourceUri: String): AssetEntity?

    /** URI可用性再検査の走査源（切断状態表示）。Room internal executor 上で実行される。 */
    @Query("SELECT * FROM assets")
    suspend fun getAllOnce(): List<AssetEntity>

    /** 遅延 sha-256 ファンガープリントの埋め込み（U01）。 */
    @Query("UPDATE assets SET imageFingerprint = :fingerprint WHERE id = :id")
    suspend fun updateFingerprint(id: String, fingerprint: String)

    /** 遅延バックフィル対象の検出。 */
    @Query("SELECT * FROM assets WHERE imageFingerprint IS NULL")
    suspend fun findPendingFingerprint(): List<AssetEntity>

    /** 再関連付け（U01 切断状態からの導線）。原本は変更せず参照先と状態のみ更新する。 */
    @Query(
        "UPDATE assets SET sourceUri = :sourceUri, availability = :availability WHERE id = :id",
    )
    suspend fun updateSourceUri(id: String, sourceUri: String, availability: String)
}

@Dao
interface EditDocumentDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(document: EditDocumentEntity): Long

    @Query("SELECT * FROM edit_documents WHERE assetId = :assetId LIMIT 1")
    suspend fun getByAssetId(assetId: String): EditDocumentEntity?

    @Query("SELECT * FROM edit_documents WHERE id = :id LIMIT 1")
    suspend fun get(id: String): EditDocumentEntity?

    /** 現在改訂の更新。イベント追記と同一トランザクション内で呼ぶこと（§3.3）。 */
    @Query(
        "UPDATE edit_documents SET currentRevision = :revisionNo, lastCommittedAt = :committedAt, checksum = :checksum WHERE id = :id",
    )
    suspend fun commitRevision(id: String, revisionNo: Long, committedAt: Long, checksum: String)
}

/** Append-only DAO。UPDATE/DELETE を意図的に公開しない（ADR-007）。 */
@Dao
interface EditRevisionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun append(revision: EditRevisionEntity)

    @Query(
        "SELECT * FROM edit_revisions WHERE documentId = :documentId AND revisionNo > :sinceNo " +
            "ORDER BY revisionNo ASC",
    )
    suspend fun afterRevision(documentId: String, sinceNo: Long): List<EditRevisionEntity>

    @Query(
        "SELECT * FROM edit_revisions WHERE documentId = :documentId AND revisionNo > :snapshotNo " +
            "AND committedAt > 0 " +
            "ORDER BY revisionNo ASC",
    )
    suspend fun committedAfterSnapshot(
        documentId: String,
        snapshotNo: Long,
    ): List<EditRevisionEntity>

    /** 履歴再生/Undo遡行の正本読み込み（§3.3）。読み込み専用（append-only 契約を壊さない）。 */
    @Query("SELECT * FROM edit_revisions WHERE documentId = :documentId ORDER BY revisionNo ASC")
    suspend fun all(documentId: String): List<EditRevisionEntity>

    @Query(
        "SELECT * FROM edit_revisions WHERE documentId = :documentId ORDER BY revisionNo DESC LIMIT 1",
    )
    suspend fun latest(documentId: String): EditRevisionEntity?
}

@Dao
interface EditSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(snapshot: EditSnapshotEntity)

    @Query(
        "SELECT * FROM edit_snapshots WHERE documentId = :documentId ORDER BY revisionNo DESC LIMIT 1",
    )
    suspend fun latest(documentId: String): EditSnapshotEntity?
}

@Dao
interface ExportJobDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(job: ExportJobEntity): Long

    @Query(
        "UPDATE export_jobs SET state = :state, progress = :progress, errorCode = :errorCode, " +
            "attempt = :attempt, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun transition(
        id: String,
        state: String,
        progress: Int,
        errorCode: String?,
        attempt: Int,
        updatedAt: Long,
    )

    @Query("SELECT * FROM export_jobs WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<ExportJobEntity?>

    @Query("SELECT * FROM export_jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ExportJobEntity>>

    /** 再起動後に PREPARING..PUBLISHING を走査して復旧対象化する（§8.2）。 */
    @Query(
        "SELECT * FROM export_jobs WHERE state IN ('PREPARING','RENDERING','ENCODING','VERIFYING','PUBLISHING')",
    )
    suspend fun findIncompleteRecovering(): List<ExportJobEntity>
}

@Dao
interface ThumbnailDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(thumbnail: ThumbnailEntity)

    @Query("SELECT * FROM thumbnails WHERE assetId = :assetId ORDER BY level ASC")
    suspend fun forAsset(assetId: String): List<ThumbnailEntity>

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM thumbnails")
    suspend fun totalBytes(): Long
}
