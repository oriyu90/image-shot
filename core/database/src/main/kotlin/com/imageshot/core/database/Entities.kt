package com.imageshot.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 永続エンティティ（02_実装定義書.md §3.1 に準拠）。
 * recipe/events の payload は kotlinx.serialization JSON（schemaVersion付き）で保存する。
 * バイナリ（CBOR/Proto相当）への置き替えは ADR-022 で確定済み
 * （DORMANT: 実測条件が満たされた場合のみ再検討する）。
 */
@Entity(
    tableName = "assets",
    indices = [Index("availability")],
)
data class AssetEntity(
    @PrimaryKey val id: String,
    val sourceUri: String,
    val displayName: String,
    val mimeGuess: String,
    val sniffedFormat: String,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val dateTaken: Long?,
    val rawCameraId: String?,
    val colorProfileHash: String?,
    val imageFingerprint: String?,
    val availability: String,
    val importedAt: Long,
)

/** 編集文書。assetId は UNIQUE（Spec §3.1）。 */
@Entity(
    tableName = "edit_documents",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["assetId"], unique = true)],
)
data class EditDocumentEntity(
    @PrimaryKey val id: String,
    val assetId: String,
    val schemaVersion: Int,
    val currentRevision: Long,
    val sourceFingerprint: String,
    val lastCommittedAt: Long,
    val checksum: String,
)

/**
 * 編集改訂イベント。append-only（ADR-007）。
 * ORM側で UPDATE/DELETE を公開しないのは DAO の責務で担保する。
 */
@Entity(
    tableName = "edit_revisions",
    primaryKeys = ["documentId", "revisionNo"],
    foreignKeys = [
        ForeignKey(
            entity = EditDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId")],
)
data class EditRevisionEntity(
    val documentId: String,
    val revisionNo: Long,
    val eventType: String,
    val payloadJson: String,
    /** payloadJson のスキーマバージョン（RecipeSchema由来のイベント形式）。 */
    val payloadSchemaVersion: Int,
    val committedAt: Long,
    val checksum: String,
)

/** レシピ全体スナップショット。起動時の再生起点として使う（§3.3）。 */
@Entity(
    tableName = "edit_snapshots",
    primaryKeys = ["documentId", "revisionNo"],
    foreignKeys = [
        ForeignKey(
            entity = EditDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId")],
)
data class EditSnapshotEntity(
    val documentId: String,
    val revisionNo: Long,
    val recipeJson: String,
    val recipeSchemaVersion: Int,
    val checksum: String,
)

/**
 * 書き出しジョブ。2段階コミットの状態機械（§8.2, ADR-010）を持つ。
 */
@Entity(
    tableName = "export_jobs",
    indices = [Index("state"), Index("documentId")],
)
data class ExportJobEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val inputFingerprint: String,
    val recipeRevision: Long,
    val settingsHash: String,
    val outputUri: String,
    val state: String,
    val progress: Int,
    val attempt: Int,
    val errorCode: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** サムネイル（ピラミッド level taxonomy: 0=full, 段階数値は別定義）。LRU管理対象。 */
@Entity(
    tableName = "thumbnails",
    primaryKeys = ["assetId", "level"],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("assetId")],
)
data class ThumbnailEntity(
    val assetId: String,
    val level: Int,
    val cacheKey: String,
    val contentHash: String,
    val accessedAt: Long,
    val byteSize: Long,
)
