package com.imageshot.core.database

import androidx.room.withTransaction
import com.imageshot.core.model.AdjustmentId
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.ErrorCode
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.RecipeParameterEdit
import com.imageshot.core.model.RecipeSchema
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * 編集イベント確定・スナップショット・Undo カーソルの正本（02_実装定義書.md §3.3, ADR-007）。
 *
 * 保存規則:
 * - スライダーの onDragEnd で1件の正規化イベントのみ確定。ドラッグ中の値は永続化しない。
 * - 確定操作は1つの Room トランザクションで「イベント追記 + EditDocument.currentRevision 更新」。
 *   トランザクション commit 完了だけが「保存済み」の表示根拠。
 * - レシピ全体スナップショットは [SNAPSHOT_EVERY] イベントごとに同トランザクションで作成。
 *   起動時は最終スナップショットを基点に、チェックサムが正しいイベントのみ再生する。
 * - Undo は既存イベントの削除ではなく新規イベント追記（op=undo）で表現する。
 *   Redo は op=redo の追記。after-undo 直後の新規確定で redo 分岐は消える
 *   （§3.3 の「固定された履歴モデル」: 分岐保持は持たない固定単分岐）。
 *   → Undo 可能条件 = 末尾イベント op ∈ {apply, redo}、Redo 可能条件 = 末尾 op = undo。
 * - 故障時: 操作途中の kill は最後の確定イベントまで戻ってよい。確定後 kill でイベント欠落は不可。
 *   チェックサム不一致イベントは再生スキップし（§3.3「正しいチェックサムのイベントだけ再生」）。
 *
 * 上位層（feature/editor）との契約:
 * - baseRevision 非一致の確定要求は「失効」として拒否し、上位は reload して再同期する
 *   （NFR-06: 最後に確定した操作の損失を防ぐための楽観排他）。
 */
class EditStore(
    private val backend: EditStoreBackend,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    // ---------------------------------------------------------------- 公开API 型

    /** 履歴項目（payload 解析済み。UI はこの型のみ扱い JSON に直接触れない）。 */
    data class EditStoreHistoryItem(
        val revisionNo: Long,
        val eventType: String,
        val path: String?,
        val value: Double?,
        val op: String?,
        val committedAt: Long,
    )

    data class EditStoreState(
        val documentId: String,
        val assetId: String,
        val sourceFingerprint: String,
        val recipe: EditRecipe,
        val currentRevision: Long,
        val history: List<EditStoreHistoryItem>,
        val canUndo: Boolean,
        val canRedo: Boolean,
        /** 有効スナップショットの改訂番号（0 = スナップショット未作成）。 */
        val snapshotRevisionNo: Long,
    )

    sealed interface UndoOutcome {
        data class Applied(val state: EditStoreState) : UndoOutcome
        /** 取り消せるイベントがない（履歴末尾が undo 等）。エラーではなく状態表示対象。 */
        data object Noop : UndoOutcome
    }

    /**
     * Asset の編集文書を取得し、無ければ作成する（取り込み済み Asset の初回編集時）。
     * documentId は新規生成 UUID。既存ならそのまま load する。
     */
    suspend fun ensureDocument(assetId: String, sourceFingerprint: String): ImageOpResult<EditStoreState> {
        backend.getDocumentByAsset(assetId)?.let { existing ->
            return load(existing.id)
        }
        val entity = EditDocumentEntity(
            id = UUID.randomUUID().toString(),
            assetId = assetId,
            schemaVersion = RecipeSchema.CURRENT,
            currentRevision = 0L,
            sourceFingerprint = sourceFingerprint,
            lastCommittedAt = now(),
            checksum = "",
        )
        val inserted = backend.withTransaction { backend.insertDocumentIfAbsent(entity) }
        if (!inserted) {
            // 競合で他者が先に作成した場合は既存文書を採用（二重作成しない）。
            val winner = backend.getDocumentByAsset(assetId)
                ?: return ImageOpResult.Failure(ErrorCode.MALFORMED_METADATA, "edit document create failed")
            return load(winner.id)
        }
        return load(entity.id)
    }

    /** スナップショット + チェックサム検証済みイベントの再生（§3.3）。 */
    suspend fun load(documentId: String): ImageOpResult<EditStoreState> {
        val state = loadInternal(documentId)
            ?: return ImageOpResult.Failure(
                ErrorCode.MALFORMED_METADATA,
                "edit document not found: $documentId",
            )
        return ImageOpResult.Success(state)
    }

    /**
     * 1件の調整イベントを確定する。トランザクション内でイベント追記 + currentRevision 更新 +
     * （閾値到達時に）スナップショット作成まで行う。
     */
    suspend fun commit(
        documentId: String,
        baseRevision: Long,
        edit: RecipeParameterEdit,
        op: String = RecipeParameterEdit.OP_APPLY,
    ): ImageOpResult<EditStoreState> {
        val doc = backend.getDocument(documentId)
            ?: return ImageOpResult.Failure(ErrorCode.MALFORMED_METADATA, "edit document not found: $documentId")

        val validated = RecipeParameterEdit.validate(edit.path, edit.value).getOrNull()
            ?: return ImageOpResult.Failure(
                ErrorCode.MALFORMED_METADATA,
                "invalid adjustment edit (unknown path or non-finite value)",
            )
        val id = AdjustmentId.byPath(edit.path)!!

        // 楽観排他: 確定要求時の改訂が DB 上の最新でないなら失効扱い（上位は reload して同期）。
        if (doc.currentRevision != baseRevision) {
            return ImageOpResult.Failure(
                ErrorCode.MALFORMED_METADATA,
                "stale baseRevision=$baseRevision current=${doc.currentRevision}",
            )
        }
        return appendEvent(doc, id, validated.value, op)
    }

    /** Undo: 末尾イベントの影響を取り消す新規イベント（op=undo）を追記。削除・書換はしない。 */
    suspend fun undo(documentId: String): ImageOpResult<UndoOutcome> {
        val doc = backend.getDocument(documentId)
            ?: return ImageOpResult.Failure(ErrorCode.MALFORMED_METADATA, "edit document not found: $documentId")
        val state = loadInternal(documentId) ?: return successStateFailure(documentId)
        val last = state.history.lastOrNull() ?: return ImageOpResult.Success(UndoOutcome.Noop)
        val lastOp = last.op
        if (lastOp == RecipeParameterEdit.OP_UNDO) return ImageOpResult.Success(UndoOutcome.Noop)
        val id = last.path?.let { AdjustmentId.byPath(it) } ?: return ImageOpResult.Success(UndoOutcome.Noop)
        val previous = previousSliderValue(state.history, id)
        val committed = appendEvent(doc, id, previous, RecipeParameterEdit.OP_UNDO)
        return when (val outcome = committed) {
            is ImageOpResult.Success -> ImageOpResult.Success(UndoOutcome.Applied(outcome.value))
            is ImageOpResult.Degraded -> ImageOpResult.Success(UndoOutcome.Applied(outcome.value))
            is ImageOpResult.Failure -> outcome
        }
    }

    /** Redo: 直前に undo された値（末尾 op=undo のとき）を再度適用する新規イベント（op=redo）。 */
    suspend fun redo(documentId: String): ImageOpResult<UndoOutcome> {
        val doc = backend.getDocument(documentId)
            ?: return ImageOpResult.Failure(ErrorCode.MALFORMED_METADATA, "edit document not found: $documentId")
        val state = loadInternal(documentId) ?: return successStateFailure(documentId)
        val last = state.history.lastOrNull() ?: return ImageOpResult.Success(UndoOutcome.Noop)
        if (last.op != RecipeParameterEdit.OP_UNDO) return ImageOpResult.Success(UndoOutcome.Noop)
        val id = last.path?.let { AdjustmentId.byPath(it) } ?: return ImageOpResult.Success(UndoOutcome.Noop)
        val undone = last.revisionNo.let { rev ->
            state.history.lastOrNull {
                it.revisionNo < rev && it.path == last.path && it.op != RecipeParameterEdit.OP_UNDO
            }
        }
        val value = undone?.value ?: id.default
        val committed = appendEvent(doc, id, value, RecipeParameterEdit.OP_REDO)
        return when (val outcome = committed) {
            is ImageOpResult.Success -> ImageOpResult.Success(UndoOutcome.Applied(outcome.value))
            is ImageOpResult.Degraded -> ImageOpResult.Success(UndoOutcome.Applied(outcome.value))
            is ImageOpResult.Failure -> outcome
        }
    }

    // ---------------------------------------------------------------- 内部実装

    private fun successStateFailure(documentId: String): ImageOpResult.Failure =
        ImageOpResult.Failure(ErrorCode.MALFORMED_METADATA, "load failed: $documentId")

    /** 末尾取り消し対象パスの1つ前のスライダー値（履歴に無ければ既定値）。 */
    private fun previousSliderValue(history: List<EditStoreHistoryItem>, id: AdjustmentId): Double {
        for (i in history.lastIndex - 1 downTo 0) {
            if (history[i].path == id.recipePath) {
                return history[i].value ?: id.default
            }
        }
        return id.default
    }

    private suspend fun appendEvent(
        doc: EditDocumentEntity,
        id: AdjustmentId,
        value: Double,
        op: String,
    ): ImageOpResult<EditStoreState> {
        val revisionNo = doc.currentRevision + 1
        val payload: JsonObject = buildJsonObject {
            put("path", id.recipePath)
            put("value", value)
            put("op", op)
        }
        val payloadJson = EditRecordCodec.encodeEventPayload(payload)
        val committedAt = now()
        val checksum = EditChecksums.revisionChecksum(doc.id, revisionNo, EVENT_TYPE_PARAM, payloadJson)
        val revision = EditRevisionEntity(
            documentId = doc.id,
            revisionNo = revisionNo,
            eventType = EVENT_TYPE_PARAM,
            payloadJson = payloadJson,
            payloadSchemaVersion = EVENT_PAYLOAD_SCHEMA_VERSION,
            committedAt = committedAt,
            checksum = checksum,
        )

        val appended: Boolean = backend.withTransaction {
            val ok = backend.appendRevision(revision)
            if (!ok) {
                false
            } else {
                // 「イベント追記 + 文書カーソル更新」は同一トランザクションでのみ保存済み扱い（§3.3）。
                backend.commitRevisionCursor(doc.id, revisionNo, committedAt, checksum)
                maybeCreateSnapshot(doc.id, revisionNo)
                true
            }
        }
        if (!appended) {
            return ImageOpResult.Failure(
                ErrorCode.MALFORMED_METADATA,
                "revision conflict at $revisionNo (document ${doc.id})",
            )
        }
        val state = loadInternal(doc.id)
            ?: return ImageOpResult.Failure(ErrorCode.MALFORMED_METADATA, "reload failed: ${doc.id}")
        return ImageOpResult.Success(state)
    }

    /** [SNAPSHOT_EVERY] イベントごとにレシピ全体スナップショットを作成（同トランザクション内で呼ぶ）。 */
    private suspend fun maybeCreateSnapshot(documentId: String, revisionNo: Long) {
        val latestSnapshotNo = backend.latestSnapshot(documentId)?.revisionNo ?: 0L
        if (revisionNo - latestSnapshotNo < SNAPSHOT_EVERY) return
        val projection = loadInternal(documentId) ?: return
        val recipeJson = EditRecordCodec.encodeRecipe(projection.recipe)
        val snapshot = EditSnapshotEntity(
            documentId = documentId,
            revisionNo = revisionNo,
            recipeJson = recipeJson,
            recipeSchemaVersion = RECIPE_SCHEMA_VERSION,
            checksum = EditChecksums.snapshotChecksum(documentId, revisionNo, recipeJson),
        )
        // 既に同一番号のスナップショットがある（再試行）場合は登録済みを採用して続行。
        backend.insertSnapshot(snapshot)
    }

    private suspend fun loadInternal(documentId: String): EditStoreState? {
        val doc = backend.getDocument(documentId) ?: return null
        var base = EditRecipe(
            documentId = doc.id,
            source = EditRecipe.RecipeSource(assetId = doc.assetId, fingerprint = doc.sourceFingerprint),
        )
        var baseRevision = 0L
        backend.latestSnapshot(documentId)?.let { snap ->
            val expected = EditChecksums.snapshotChecksum(snap.documentId, snap.revisionNo, snap.recipeJson)
            if (snap.checksum == expected) {
                try {
                    base = EditRecordCodec.decodeRecipe(snap.recipeJson, snap.recipeSchemaVersion)
                    baseRevision = snap.revisionNo
                } catch (_: Exception) {
                    // スナップショット破損時はイベント全件再生へフォールバック（opaque に捨てない）。
                }
            }
        }

        var recipe = base
        val history = mutableListOf<EditStoreHistoryItem>()
        for (rev in backend.allRevisions(documentId)) {
            val payload = runCatching { EditRecordCodec.decodeEventPayload(rev.payloadJson) }.getOrNull()
            if (payload == null) continue
            if (rev.checksum != EditChecksums.revisionChecksum(rev.documentId, rev.revisionNo, rev.eventType, rev.payloadJson)) {
                // §3.3: チェックサムが正しいイベントだけ再生する。
                continue
            }
            history += parseHistoryItem(rev, payload)
            if (rev.revisionNo > baseRevision) {
                recipe = applyPayload(recipe, payload) ?: recipe
            }
        }

        val lastOp = history.lastOrNull()?.op
        val canUndoState = lastOp == RecipeParameterEdit.OP_APPLY || lastOp == RecipeParameterEdit.OP_REDO
        val canRedoState = lastOp == RecipeParameterEdit.OP_UNDO
        return EditStoreState(
            documentId = doc.id,
            assetId = doc.assetId,
            sourceFingerprint = doc.sourceFingerprint,
            recipe = recipe,
            currentRevision = doc.currentRevision,
            history = history,
            canUndo = canUndoState,
            canRedo = canRedoState,
            snapshotRevisionNo = baseRevision,
        )
    }

    private fun applyPayload(recipe: EditRecipe, payload: JsonObject): EditRecipe? {
        val path = (payload["path"] as? JsonPrimitive)?.content ?: return null
        val value = (payload["value"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val id = AdjustmentId.byPath(path) ?: return null // 未知パスはスキップ（破壊保存しない）
        return id.apply(recipe, value)
    }

    private fun parseHistoryItem(rev: EditRevisionEntity, payload: JsonObject): EditStoreHistoryItem {
        val path = (payload["path"] as? JsonPrimitive)?.content
        val value = (payload["value"] as? JsonPrimitive)?.doubleOrNull
        val op = (payload["op"] as? JsonPrimitive)?.content
        return EditStoreHistoryItem(
            revisionNo = rev.revisionNo,
            eventType = rev.eventType,
            path = path,
            value = value,
            op = op,
            committedAt = rev.committedAt,
        )
    }

    companion object {
        const val EVENT_TYPE_PARAM = "param"
        /** スナップショット作成周期（§3.3「20–50イベント」帯の下限近く）。 */
        const val SNAPSHOT_EVERY = 24
    }
}

/**
 * レビジョン/スナップショットのチェックサム正本。
 * 形式: sha-256 hex of "v1|<kind>|<documentId>|<revisionNo>|<eventType|SNAP>|<payloadJson|recipeJson>"。
 */
object EditChecksums {
    fun revisionChecksum(documentId: String, revisionNo: Long, eventType: String, payloadJson: String): String =
        sha256("v1|rev|$documentId|$revisionNo|$eventType|$payloadJson")

    fun snapshotChecksum(documentId: String, revisionNo: Long, recipeJson: String): String =
        sha256("v1|snap|$documentId|$revisionNo|SNAP|$recipeJson")

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * 永続バックエンド抽象。Room 実装（[RoomEditStoreBackend]）と JVM 単体テスト用フェイクを
 * 同一契約で走らせる。DB は描画 API を知らない（§2 依存方向）。
 */
interface EditStoreBackend {
    suspend fun <T> withTransaction(block: suspend () -> T): T
    suspend fun insertDocumentIfAbsent(entity: EditDocumentEntity): Boolean
    suspend fun getDocument(documentId: String): EditDocumentEntity?
    suspend fun getDocumentByAsset(assetId: String): EditDocumentEntity?
    suspend fun allRevisions(documentId: String): List<EditRevisionEntity>
    suspend fun appendRevision(entity: EditRevisionEntity): Boolean
    suspend fun latestRevision(documentId: String): EditRevisionEntity?
    suspend fun latestSnapshot(documentId: String): EditSnapshotEntity?
    suspend fun insertSnapshot(entity: EditSnapshotEntity): Boolean

    /** 文書カーソル更新。必ず withTransaction 内で呼ばれること（§3.3）。 */
    suspend fun commitRevisionCursor(id: String, revisionNo: Long, committedAt: Long, checksum: String)
}

/** Room 実装。WAL 上で1トランザクション=1確定操作（ADR-007）。 */
class RoomEditStoreBackend(private val db: ImageShotDatabase) : EditStoreBackend {
    private val documentDao = db.editDocumentDao()
    private val revisionDao = db.editRevisionDao()
    private val snapshotDao = db.editSnapshotDao()

    override suspend fun <T> withTransaction(block: suspend () -> T): T =
        db.withTransaction { block() }

    override suspend fun insertDocumentIfAbsent(entity: EditDocumentEntity): Boolean =
        documentDao.insert(entity) != -1L

    override suspend fun getDocument(documentId: String): EditDocumentEntity? =
        documentDao.get(documentId)

    override suspend fun getDocumentByAsset(assetId: String): EditDocumentEntity? =
        documentDao.getByAssetId(assetId)

    override suspend fun allRevisions(documentId: String): List<EditRevisionEntity> =
        revisionDao.all(documentId)

    override suspend fun appendRevision(entity: EditRevisionEntity): Boolean {
        return runCatching { revisionDao.append(entity) }.isSuccess
    }

    override suspend fun latestRevision(documentId: String): EditRevisionEntity? =
        revisionDao.latest(documentId)

    override suspend fun latestSnapshot(documentId: String): EditSnapshotEntity? =
        snapshotDao.latest(documentId)

    override suspend fun insertSnapshot(entity: EditSnapshotEntity): Boolean =
        runCatching { snapshotDao.insert(entity) }.isSuccess

    override suspend fun commitRevisionCursor(id: String, revisionNo: Long, committedAt: Long, checksum: String) =
        documentDao.commitRevision(id, revisionNo, committedAt, checksum)
}
