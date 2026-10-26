package com.imageshot.core.database

import androidx.room.withTransaction
import com.imageshot.core.model.AdjustmentId
import com.imageshot.core.model.EditEventKind
import com.imageshot.core.model.EditEventStatus
import com.imageshot.core.model.EditNodeSpecs
import com.imageshot.core.model.LEGACY_EVENT_TYPE_PARAM
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.ErrorCode
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.RecipeParameterEdit
import com.imageshot.core.model.RecipeSchema
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
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
 * - eventType は正本列挙（ADR-022, [EditEventKind]）の安定IDで書込む。ドキュメント内に
 *   未知種別/未対応 schemaVersion の行があっても、値を保持（PRESERVED_ONLY）し・適用せず・
 *   行を書換しない。集計は [EditDecodeSummary] で明示する（§7.1 哲学）。
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
        /** curve イベントのカーブ全量（正規化済み・master+R/G/B チャンネル）。スライダー種は null。 */
        val curve: EditRecipe.Curve? = null,
        /** curve イベントの編集対象チャンネル（"r"/"g"/"b"。master 編集・復帰系フォールバックは null）。 */
        val curveChannel: String? = null,
        /** geometry イベントの絶対状態（正規化済み）。スライダー種は null。 */
        val geometry: EditRecipe.GeometryParams? = null,
    )

    /** 編集イベントの payload 解放（再生）に関する明示的集計（§7.1 変換ステータスの哲学）。 */
    data class EditDecodeSummary(
        /** 総イベント件数（チェックサム基点込みの全 revision）。 */
        val totalRevisions: Int,
        /** 現在のレシピへ適用した既知イベント件数。 */
        val applied: Int,
        /**
         * PRESERVED_ONLY（未知 eventType / 未知パス）。値を保持し履歴として表示するが、
         * 現在構築では適用せず・既定値に落とさない・削除しない（§7.1, ADR-022）。
         */
        val preservedOnly: Int,
        /** 未対応 payloadSchemaVersion（将来バージョン）。適用も既定値化もしない。 */
        val unsupportedVersion: Int,
        /** チェックサム不一致 / JSON 破損。再生から除外する（§3.3）。 */
        val invalid: Int,
    ) {
        val isFullyApplied: Boolean get() = preservedOnly == 0 && unsupportedVersion == 0 && invalid == 0
    }

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
        /** 再生の明示集計（未知/未対応/破損イベントの扱いを無通知にしない, ADR-014）。 */
        val decodeSummary: EditDecodeSummary,
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

    /**
     * トーンカーブ（master + R/G/B チャンネル）を絶対状態として確定する。
     * payload 形状 v1: `{"curve":{"ch"?:"r"|"g"|"b","rgb":[[x,y]...],"r"?:[...],"g"?:[...],
     * "b"?:[...]},"op":...}`（ADR-022 の第2段・P4 / PR-28a でチャンネルを画素適用対象に昇格）。
     * - [curve] は全量（master `rgb` 必須・`r/g/b` は非空のときのみ payload に収める）。
     * - [editedChannel] は当該確認の編集対象チャンネル（"r"/"g"/"b"、master 編集は null）。
     *   適用は curve 全量で行い、本引数は payload `ch` に置かれて履歴表示を助ける補助値。
     * 正規化できない点列（master / 非空チャンネルのいずれも）は FAIL（既定値へ落とさない）。
     */
    suspend fun commitCurve(
        documentId: String,
        baseRevision: Long,
        curve: EditRecipe.Curve,
        editedChannel: String? = null,
        op: String = RecipeParameterEdit.OP_APPLY,
    ): ImageOpResult<EditStoreState> {
        if (editedChannel != null && editedChannel !in CURVE_CHANNEL_KEYS) {
            return ImageOpResult.Failure(
                ErrorCode.MALFORMED_METADATA,
                "invalid editedChannel: $editedChannel (require null or $CURVE_CHANNEL_KEYS)",
            )
        }
        val doc = backend.getDocument(documentId)
            ?: return ImageOpResult.Failure(ErrorCode.MALFORMED_METADATA, "edit document not found: $documentId")
        if (doc.currentRevision != baseRevision) {
            return ImageOpResult.Failure(
                ErrorCode.MALFORMED_METADATA,
                "stale baseRevision=$baseRevision current=${doc.currentRevision}",
            )
        }
        val normalizedRgb = EditNodeSpecs.normalizeCurve(curve.rgb)
            ?: return ImageOpResult.Failure(
                ErrorCode.MALFORMED_METADATA,
                "invalid curve points (finite/2..16 points/sorted required)",
            )
        // 非空チャンネルのみ正規化して収める（空 = 未編集 = payload から省略）。
        val normalizedR = normalizedChannelPoints(curve.r) ?: return invalidCurveChannel("r")
        val normalizedG = normalizedChannelPoints(curve.g) ?: return invalidCurveChannel("g")
        val normalizedB = normalizedChannelPoints(curve.b) ?: return invalidCurveChannel("b")
        val payload = buildJsonObject {
            put("op", op)
            put("curve", buildJsonObject {
                if (editedChannel != null) put("ch", editedChannel)
                put("rgb", curvePointsJson(normalizedRgb))
                if (normalizedR.isNotEmpty()) put("r", curvePointsJson(normalizedR))
                if (normalizedG.isNotEmpty()) put("g", curvePointsJson(normalizedG))
                if (normalizedB.isNotEmpty()) put("b", curvePointsJson(normalizedB))
            })
        }
        return appendNodeEvent(doc, EditEventKind.CURVE.id, payload)
    }

    /** チャンネルカーブの正規化（空は未編集としてそのまま通す・非空は正規化。失敗は null）。 */
    private fun normalizedChannelPoints(points: List<List<Double>>): List<List<Double>>? =
        if (points.isEmpty()) {
            points
        } else {
            EditNodeSpecs.normalizeCurve(points)
        }

    /** チャンネルカーブの正規化失敗（§7.2 INVALID・既定値へ落とさない）。 */
    private fun invalidCurveChannel(channel: String): ImageOpResult.Failure =
        ImageOpResult.Failure(
            ErrorCode.MALFORMED_METADATA,
            "invalid curve points for channel $channel (finite/2..16 points/sorted required)",
        )

    /**
     * ジオメトリ（回転/反転/クロップ）を絶対状態として確定する。
     * payload 形状 v1: `{"geometry":{...},"op":...}`。正規化できない入力は FAIL。
     */
    suspend fun commitGeometry(
        documentId: String,
        baseRevision: Long,
        geometry: EditRecipe.GeometryParams,
        op: String = RecipeParameterEdit.OP_APPLY,
    ): ImageOpResult<EditStoreState> {
        val doc = backend.getDocument(documentId)
            ?: return ImageOpResult.Failure(ErrorCode.MALFORMED_METADATA, "edit document not found: $documentId")
        if (doc.currentRevision != baseRevision) {
            return ImageOpResult.Failure(
                ErrorCode.MALFORMED_METADATA,
                "stale baseRevision=$baseRevision current=${doc.currentRevision}",
            )
        }
        val normalized = EditNodeSpecs.normalizeGeometry(geometry)
            ?: return ImageOpResult.Failure(
                ErrorCode.MALFORMED_METADATA,
                "invalid geometry params (angle must be finite; crop span > 0)",
            )
        val payload = buildJsonObject {
            put("op", op)
            put("geometry", buildJsonObject {
                put("angleDeg", normalized.angleDeg)
                put("mirrorX", normalized.mirrorX)
                put("mirrorY", normalized.mirrorY)
                put("crop", buildJsonArray { normalized.cropNorm.forEach { add(JsonPrimitive(it)) } })
            })
        }
        return appendNodeEvent(doc, EditEventKind.GEOMETRY.id, payload)
    }

    /** Undo: 末尾イベントの影響を取り消す新規イベント（op=undo）を追記。削除・書換はしない。 */
    suspend fun undo(documentId: String): ImageOpResult<UndoOutcome> {
        val doc = backend.getDocument(documentId)
            ?: return ImageOpResult.Failure(ErrorCode.MALFORMED_METADATA, "edit document not found: $documentId")
        val state = loadInternal(documentId) ?: return successStateFailure(documentId)
        val last = state.history.lastOrNull() ?: return ImageOpResult.Success(UndoOutcome.Noop)
        val lastOp = last.op
        if (lastOp == RecipeParameterEdit.OP_UNDO) return ImageOpResult.Success(UndoOutcome.Noop)
        // ノード（curve/geometry）イベントは絶対状態スナップショットで undo する（本Event前の状態へ）。
        if (last.eventType == EditEventKind.CURVE.id || last.eventType == EditEventKind.GEOMETRY.id) {
            val previous = previousNodeState(state.history, last.eventType, last.revisionNo, effectiveOnly = false)
            val committed = if (last.eventType == EditEventKind.CURVE.id) {
                commitCurve(
                    documentId,
                    doc.currentRevision,
                    previous?.curve ?: EditRecipe.Curve(), // フォールバック=中立 rgb + 未編集チャンネル。
                    previous?.curveChannel ?: last.curveChannel, // 履歴表示用の補助値（適用には無関係）。
                    RecipeParameterEdit.OP_UNDO,
                )
            } else {
                commitGeometry(
                    documentId,
                    doc.currentRevision,
                    previous?.geometry ?: EditRecipe.GeometryParams(),
                    RecipeParameterEdit.OP_UNDO,
                )
            }
            return toUndoOutcome(committed)
        }
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
        // ノード（curve/geometry）の redo: 取り消し直前の状態（末尾 undo の直前ノード状態）へ戻す。
        if (last.eventType == EditEventKind.CURVE.id || last.eventType == EditEventKind.GEOMETRY.id) {
            val redone = previousNodeState(state.history, last.eventType, last.revisionNo, effectiveOnly = true)
            val committed = if (last.eventType == EditEventKind.CURVE.id) {
                commitCurve(
                    documentId,
                    doc.currentRevision,
                    redone?.curve ?: EditRecipe.Curve(), // フォールバック=中立 rgb + 未編集チャンネル。
                    redone?.curveChannel ?: last.curveChannel, // 履歴表示用の補助値（適用には無関係）。
                    RecipeParameterEdit.OP_REDO,
                )
            } else {
                commitGeometry(
                    documentId,
                    doc.currentRevision,
                    redone?.geometry ?: EditRecipe.GeometryParams(),
                    RecipeParameterEdit.OP_REDO,
                )
            }
            return toUndoOutcome(committed)
        }
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

    /**
     * ノード（curve/geometry）履歴から 1つ前の絶対状態を復元する。
     * - [effectiveOnly]=false（undo 用）: 末尾を除く当該種の直近イベント（op 不問）の payload 状態。
     *   （すべてのノードイベントはその時点の結果状態を持つため、任意 op の payload が最新状態。）
     * - [effectiveOnly]=true（redo 用）: op=undo でない直近イベント（取り消し直前の状態）。
     * 見つからなければ null（呼出し側が既定 neutral 状態へ落とす = スライダーの id.default と同一規約）。
     */
    private fun previousNodeState(
        history: List<EditStoreHistoryItem>,
        eventType: String,
        beforeRevisionNo: Long,
        effectiveOnly: Boolean,
    ): EditStoreHistoryItem? {
        for (i in history.lastIndex downTo 0) {
            val item = history[i]
            if (item.revisionNo >= beforeRevisionNo) continue
            if (item.eventType != eventType) continue
            if (effectiveOnly && item.op == RecipeParameterEdit.OP_UNDO) continue
            return item
        }
        return null
    }

    /** Undo/Redo 結果の型変換（共通）。 */
    private fun toUndoOutcome(result: ImageOpResult<EditStoreState>): ImageOpResult<UndoOutcome> =
        when (result) {
            is ImageOpResult.Success -> ImageOpResult.Success(UndoOutcome.Applied(result.value))
            is ImageOpResult.Degraded -> ImageOpResult.Success(UndoOutcome.Applied(result.value))
            is ImageOpResult.Failure -> result
        }

    private suspend fun appendEvent(
        doc: EditDocumentEntity,
        id: AdjustmentId,
        value: Double,
        op: String,
    ): ImageOpResult<EditStoreState> {
        val revisionNo = doc.currentRevision + 1
        // 正本列挙（ADR-022）: 新規イベントの eventType は payload の path から導出した種別ID。
        val payload: JsonObject = buildJsonObject {
            put("path", id.recipePath)
            put("value", value)
            put("op", op)
        }
        return appendNodeEvent(doc, id.kind.id, payload)
    }

    /**
     * ノードイベント追記の共通部（スライダー / curve / geometry の3経路で同一トランザクション契約）。
     * 「イベント追記 + 文書カーソル更新 +（閾値到達時）スナップショット」を1トランザクションで行う。
     */
    private suspend fun appendNodeEvent(
        doc: EditDocumentEntity,
        eventType: String,
        payload: JsonObject,
    ): ImageOpResult<EditStoreState> {
        val revisionNo = doc.currentRevision + 1
        val payloadJson = EditRecordCodec.encodeEventPayload(payload)
        val committedAt = now()
        val checksum = EditChecksums.revisionChecksum(doc.id, revisionNo, eventType, payloadJson)
        val revision = EditRevisionEntity(
            documentId = doc.id,
            revisionNo = revisionNo,
            eventType = eventType,
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
        var applied = 0
        var preservedOnly = 0
        var unsupportedVersion = 0
        var invalid = 0
        val revisions = backend.allRevisions(documentId)
        for (rev in revisions) {
            val payload = runCatching { EditRecordCodec.decodeEventPayload(rev.payloadJson) }.getOrNull()
            if (payload == null) {
                invalid++
                continue
            }
            if (rev.checksum != EditChecksums.revisionChecksum(rev.documentId, rev.revisionNo, rev.eventType, rev.payloadJson)) {
                // §3.3: チェックサムが正しいイベントだけ再生する。
                invalid++
                continue
            }
            history += parseHistoryItem(rev, payload)
            if (rev.payloadSchemaVersion in 1..EVENT_PAYLOAD_SCHEMA_VERSION) {
                when (rev.eventType) {
                    // ---- P4 ノード種: 絶対状態 payload（スライダー解決経路とは別再生）----
                    EditEventKind.CURVE.id -> {
                        val curve = parseCurvePayload(payload)
                        when {
                            curve == null -> {
                                // 形状不一致（旧世代/外部由来）の curve 行: 適用せず保持のみ
                                // （§7.1 PRESERVED_ONLY 相当）。旧構築の解釈規則と同一挙動。
                                preservedOnly++
                            }
                            rev.revisionNo > baseRevision -> {
                                // カーブ全量（master+R/G/B）を絶対状態として適用（PR-28a）。
                                recipe = recipe.copy(develop = recipe.develop.copy(curve = curve.curve))
                                applied++
                            }
                            // スナップショット範囲内: 効果はスナップショットに取り込み済み。
                            else -> Unit
                        }
                    }
                    EditEventKind.GEOMETRY.id -> {
                        val geometry = parseGeometryPayload(payload)
                        when {
                            geometry == null -> preservedOnly++
                            rev.revisionNo > baseRevision -> {
                                recipe = recipe.copy(geometry = geometry)
                                applied++
                            }
                            else -> Unit
                        }
                    }
                    // ---- スライダー種（tone/wb/color/hsl）----
                    else -> {
                        val id = resolveAdjustment(rev.eventType, payload)
                        when {
                            id != null && rev.revisionNo > baseRevision -> {
                                val value = (payload["value"] as? JsonPrimitive)?.doubleOrNull
                                if (value != null && value.isFinite()) {
                                    recipe = id.apply(recipe, value)
                                    applied++
                                } else {
                                    // 有限値でない payload は既定値へ落とさず失敗側に数える（§7.2 INVALID）。
                                    invalid++
                                }
                            }
                            id == null -> {
                                // 種別既知でも payload が不正、または未知種別/未知パス:
                                // 値は保持するが適用しない（§7.1 PRESERVED_ONLY 相当, ADR-022）。
                                preservedOnly++
                            }
                            // id != null でスナップショット範囲内の既知イベントは、効果がスナップショットに
                            // 取り込まれているため適用も保持も数えない（history 表示のみ）。
                        }
                    }
                }
            } else {
                // 未対応の将来バージョン: 適用せず・既定値に落とさず・行も書換しない（ADR-022）。
                unsupportedVersion++
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
            decodeSummary = EditDecodeSummary(
                totalRevisions = revisions.size,
                applied = applied,
                preservedOnly = preservedOnly,
                unsupportedVersion = unsupportedVersion,
                invalid = invalid,
            ),
        )
    }

    /**
     * eventType + payload からスライダー種別を一意に解決する（ADR-022 正本列挙）。
     * - 正本ID（tone/wb/color）: payload の path から AdjustmentId を解決し、ドメ一致を検査。
     * - 旧雛形の "param"（[LEGACY_EVENT_TYPE_PARAM]）: 同様にパスのみで解決（後方互換エイリアス）。
     * - それ以外（未知種別・RESERVED 未確定種・パス不整合）は null。
     *   呼び出し側は PRESERVED_ONLY として値を保持し、適用・既定値化・削除をしない。
     */
    private fun resolveAdjustment(eventType: String, payload: JsonObject): AdjustmentId? {
        val kind = EditEventKind.byId(eventType)
        if (kind != null && kind.status != EditEventStatus.IMPLEMENTED_M1) {
            // RESERVED 種は payload 形状未確定であり解釈しない（誤適用を作らない）。
            return null
        }
        if (kind == null && eventType != LEGACY_EVENT_TYPE_PARAM) {
            // 未知種別は素通りさせない。path が既知でも PRESERVED_ONLY（ADR-022 判定規則）。
            return null
        }
        val path = (payload["path"] as? JsonPrimitive)?.content ?: return null
        val id = AdjustmentId.byPath(path) ?: return null
        // 種別既知の場合は payload の path とドメインが一致するか検査（不整合は保持のみ）。
        return if (kind == null || kind == id.kind) id else null
    }

    private fun parseHistoryItem(rev: EditRevisionEntity, payload: JsonObject): EditStoreHistoryItem {
        val path = (payload["path"] as? JsonPrimitive)?.content
        val value = (payload["value"] as? JsonPrimitive)?.doubleOrNull
        val op = (payload["op"] as? JsonPrimitive)?.content
        val curve = if (rev.eventType == EditEventKind.CURVE.id) parseCurvePayload(payload) else null
        val geometry = if (rev.eventType == EditEventKind.GEOMETRY.id) parseGeometryPayload(payload) else null
        return EditStoreHistoryItem(
            revisionNo = rev.revisionNo,
            eventType = rev.eventType,
            path = path,
            value = value,
            op = op,
            committedAt = rev.committedAt,
            curve = curve?.curve,
            curveChannel = curve?.editedChannel,
            geometry = geometry,
        )
    }

    /**
     * curve payload v1 → カーブ全量（正規化済み）+ 編集対象チャンネル。
     * `rgb` 必須。`r/g/b` は任意読み（非空時のみ収める契約・無ければ未編集 = 空）。
     * `ch` は任意（"r"/"g"/"b" のみ受理・無効値や master 編集は null）。
     * 適用対象点（rgb / 非空チャンネル）のどれか1つでも正規化できない場合は null
     * （PRESERVED_ONLY・部分適用を作らない）。
     */
    private fun parseCurvePayload(payload: JsonObject): ParsedCurve? {
        val curve = payload["curve"] as? JsonObject ?: return null
        val rgbOrNothing = parseCurvePointsNode(curve["rgb"]) ?: return null
        val rgb = EditNodeSpecs.normalizeCurve(rgbOrNothing) ?: return null
        val r = optionalChannelPoints(curve["r"]) ?: return null
        val g = optionalChannelPoints(curve["g"]) ?: return null
        val b = optionalChannelPoints(curve["b"]) ?: return null
        val ch = (curve["ch"] as? JsonPrimitive)?.content
        val editedChannel = ch?.takeIf { it in CURVE_CHANNEL_KEYS }
        if (ch != null && editedChannel == null) {
            // ch が存在して不正値: 解釈を誤らないため行全体を保持のみへ（§7.1）。
            return null
        }
        return ParsedCurve(
            curve = EditRecipe.Curve(rgb = rgb, r = r, g = g, b = b),
            editedChannel = editedChannel,
        )
    }

    /** payload の点列ノード → 2要素 finite 点列（各点 2要素=finite のみ。非配列や曲がった形は null）。 */
    private fun parseCurvePointsNode(node: kotlinx.serialization.json.JsonElement?): List<List<Double>>? {
        val rgb = node as? kotlinx.serialization.json.JsonArray ?: return null
        return rgb.map { element ->
            val pt = element as? kotlinx.serialization.json.JsonArray ?: return null
            pt.map { (it as? JsonPrimitive)?.doubleOrNull ?: return null }
        }
    }

    /** 任意のチャンネル点列（無キーは未編集=空。空配列は未編集。非空は正規化・失敗は null）。 */
    private fun optionalChannelPoints(node: kotlinx.serialization.json.JsonElement?): List<List<Double>>? {
        if (node == null) return emptyList()
        val points = parseCurvePointsNode(node) ?: return null
        return if (points.isEmpty()) emptyList() else EditNodeSpecs.normalizeCurve(points)
    }

    /** [parseCurvePayload] の結果（curve 適用値 + 履歴表示用の編集対象チャンネル補助値）。 */
    private data class ParsedCurve(
        val curve: EditRecipe.Curve,
        val editedChannel: String?,
    )

    /** geometry payload v1 → 正規化済み GeometryParams。形状不一致は null（PRESERVED_ONLY）。 */
    private fun parseGeometryPayload(payload: JsonObject): EditRecipe.GeometryParams? {
        val geo = payload["geometry"] as? JsonObject ?: return null
        val angleDeg = (geo["angleDeg"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val mirrorX = (geo["mirrorX"] as? JsonPrimitive)?.booleanOrNull ?: return null
        val mirrorY = (geo["mirrorY"] as? JsonPrimitive)?.booleanOrNull ?: return null
        val crop = (geo["crop"] as? kotlinx.serialization.json.JsonArray) ?: return null
        if (crop.size != 4) return null
        val cropNorm = crop.map { (it as? JsonPrimitive)?.doubleOrNull ?: return null }
        return EditNodeSpecs.normalizeGeometry(
            EditRecipe.GeometryParams(
                cropNorm = cropNorm,
                angleDeg = angleDeg,
                mirrorX = mirrorX,
                mirrorY = mirrorY,
            ),
        )
    }

    /** 正規化済み制御点 → payload の `rgb` 配列（commitCurve 用の唯一の書込み整形）。 */
    private fun curvePointsJson(points: List<List<Double>>) = buildJsonArray {
        points.forEach { pt ->
            add(buildJsonArray { pt.forEach { v -> add(JsonPrimitive(v)) } })
        }
    }

    companion object {
        /** スナップショット作成周期（§3.3「20–50イベント」帯の下限近く）。 */
        const val SNAPSHOT_EVERY = 24

        /** curve payload `ch` に受理するチャンネルキー（KEY=EditRecipe.Curve のフィールド名と一致）。 */
        private val CURVE_CHANNEL_KEYS = setOf("r", "g", "b")
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
