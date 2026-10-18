package com.imageshot.core.model

/**
 * 確定済み編集イベント（ADR-007: append-only 永続化）。
 * - ドラッグ中のプレビュー値はここに入らない（onDragEnd で正規化した1件のみ確定）。
 * - Undo は履歴カーソル/新規イベントで表現し、既存イベントを削除しない。
 */
@kotlinx.serialization.Serializable
data class EditEvent(
    val documentId: String,
    /** 1から始まる単調増加の改訂番号。 */
    val revisionNo: Long,
    val eventType: String,
    /** 正規化済みイベント内容。ペイロードのスキーマは eventType ごとに固定。 */
    val payload: kotlinx.serialization.json.JsonObject,
    val committedAt: Long,
) {
    companion object {
        // TODO(ADR確認): イベント種別の正本列挙（tone/curve/geometry/mask/...）は
        // §3.3 の確定操作記録設計とともに次PRで確定する。
        const val TYPE_TONE = "tone"
    }
}

/**
 * レシピ全体スナップショット（20–50イベントごとに作成、§3.3）。
 * 起動時は最終スナップショットに、正しいチェックサムのイベントだけを再生する。
 */
@kotlinx.serialization.Serializable
data class EditSnapshot(
    val documentId: String,
    val revisionNo: Long,
    val recipe: EditRecipe,
    /** スナップショット整合性検証用のチェックサム。 */
    val checksum: String,
)

/** 編集文書（Asset 1:1）。currentRevision はトランザクションで更新する。 */
@kotlinx.serialization.Serializable
data class EditDocument(
    val id: String,
    val assetId: String,
    val schemaVersion: Int = RecipeSchema.CURRENT,
    val currentRevision: Long,
    val sourceFingerprint: String,
    val lastCommittedAt: Long,
    val checksum: String,
)
