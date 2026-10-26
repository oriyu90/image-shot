package com.imageshot.core.database

import com.imageshot.core.model.AdjustmentId
import com.imageshot.core.model.EditEventKind
import com.imageshot.core.model.EditEventStatus
import com.imageshot.core.model.EditNodeSpecs
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.LEGACY_EVENT_TYPE_PARAM
import com.imageshot.core.model.MaskSpecs
import com.imageshot.core.model.RecipeParameterEdit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * EditStore の動作テスト（Room を使わない JVM 単体テスト。Room 実装経路は
 * 移行/トランザクション破壊テストの対象であり本テストでは扱わない — 明示）。
 * 検証対象: イベント確定（トランザクション的整合）、楽観排他、スナップショット周期、
 * チェックサム再生ゲート、Undo/Redo の新規イベント追記方式（ADR-007/§3.3）。
 */
class EditStoreTest {

    /** 実装直結のメモリ内バックエンド。トランザクション内の例外を伝播しない簡易二相。 */
    private class FakeBackend : EditStoreBackend {
        val docs = mutableMapOf<String, EditDocumentEntity>()
        val revisions = mutableListOf<EditRevisionEntity>()
        val snapshots = mutableListOf<EditSnapshotEntity>()
        var transactionsOpen = 0

        override suspend fun <T> withTransaction(block: suspend () -> T): T {
            transactionsOpen++
            return try {
                block()
            } finally {
                transactionsOpen--
            }
        }

        override suspend fun insertDocumentIfAbsent(entity: EditDocumentEntity): Boolean {
            if (docs.containsKey(entity.id)) return false
            docs[entity.id] = entity
            return true
        }

        override suspend fun getDocument(documentId: String): EditDocumentEntity? = docs[documentId]

        override suspend fun getDocumentByAsset(assetId: String): EditDocumentEntity? =
            docs.values.firstOrNull { it.assetId == assetId }

        override suspend fun allRevisions(documentId: String): List<EditRevisionEntity> =
            revisions.filter { it.documentId == documentId }.sortedBy { it.revisionNo }

        override suspend fun appendRevision(entity: EditRevisionEntity): Boolean {
            if (revisions.any { it.documentId == entity.documentId && it.revisionNo == entity.revisionNo }) {
                return false
            }
            revisions += entity
            return true
        }

        override suspend fun latestRevision(documentId: String): EditRevisionEntity? =
            allRevisions(documentId).lastOrNull()

        override suspend fun latestSnapshot(documentId: String): EditSnapshotEntity? =
            snapshots.filter { it.documentId == documentId }.maxByOrNull { it.revisionNo }

        override suspend fun insertSnapshot(entity: EditSnapshotEntity): Boolean {
            if (snapshots.any { it.documentId == entity.documentId && it.revisionNo == entity.revisionNo }) {
                return false
            }
            snapshots += entity
            return true
        }

        override suspend fun commitRevisionCursor(
            id: String,
            revisionNo: Long,
            committedAt: Long,
            checksum: String,
        ) {
            docs[id] = docs[id]!!.copy(
                currentRevision = revisionNo,
                lastCommittedAt = committedAt,
                checksum = checksum,
            )
        }
    }

    private var clockTicks = 1_000L
    private fun store(backend: FakeBackend = FakeBackend()) =
        EditStore(backend, now = { clockTicks++ })

    private fun bootstrap(store: EditStore): EditStore.EditStoreState {
        val result = runBlocking { store.ensureDocument("asset-1", "fp-1") }
        return assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(result).value
    }

    private fun commit(
        store: EditStore,
        base: EditStore.EditStoreState,
        id: AdjustmentId,
        value: Double,
    ): EditStore.EditStoreState = runBlocking {
        assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            store.commit(
                base.documentId,
                base.currentRevision,
                RecipeParameterEdit(id.recipePath, value),
            ),
        ).value
    }

    // ---- 確定 ----

    @Test
    fun commit_appendsEvent_updatesCursor_andAppliesToRecipe() {
        val backend = FakeBackend()
        val store = store(backend)
        val base = bootstrap(store)
        assertEquals(0L, base.currentRevision)

        val next = commit(store, base, AdjustmentId.EXPOSURE, -1.5)
        assertEquals(1L, next.currentRevision)
        assertEquals(-1.5, next.recipe.develop.tone.exposureEv, 1e-9)
        assertTrue(next.canUndo)
        assertFalse(next.canRedo)
        assertEquals(1, backend.revisions.size)
        val cursorUpdated = backend.docs[next.documentId]!!
        assertEquals(1L, cursorUpdated.currentRevision)
        assertTrue(cursorUpdated.checksum.isNotEmpty())
    }

    @Test
    fun commit_staleBaseRevision_isRejected_withoutAnyAppends() {
        val backend = FakeBackend()
        val store = store(backend)
        val base = bootstrap(store)
        val failure = runBlocking {
            store.commit(base.documentId, 99L, RecipeParameterEdit(AdjustmentId.EXPOSURE.recipePath, 1.0))
        }
        assertIs<ImageOpResult.Failure>(failure)
        assertEquals(0, backend.revisions.size)
        assertEquals(0L, backend.docs[base.documentId]!!.currentRevision)
    }

    @Test
    fun commit_unknownPath_andNonFiniteValue_areRejected() {
        val store = store()
        val base = bootstrap(store)
        val unknown = runBlocking {
            store.commit(base.documentId, 0L, RecipeParameterEdit("develop.tone.fakeSlider", 1.0))
        }
        assertIs<ImageOpResult.Failure>(unknown)

        val nan = runBlocking {
            store.commit(base.documentId, 0L, RecipeParameterEdit(AdjustmentId.EXPOSURE.recipePath, Double.NaN))
        }
        assertIs<ImageOpResult.Failure>(nan)
    }

    @Test
    fun commit_clampsOutOfRange_valueOnce() {
        val store = store()
        val base = bootstrap(store)
        val next = commit(store, base, AdjustmentId.EXPOSURE, 42.0)
        assertEquals(5.0, next.recipe.develop.tone.exposureEv, 1e-9)
        assertEquals(5.0, next.history.last().value!!, 1e-9)
    }

    @Test
    fun commit_outOfOrderInsertion_isRejected() {
        // 「確定イベントは revision 順で連続」: 競合した追記は失敗し、欠落を作らない。
        val backend = FakeBackend()
        val store = store(backend)
        val base = bootstrap(store)
        val docId = base.documentId
        val legacyPayload = """{"path":"develop.tone.exposureEv","value":0.2,"op":"apply"}"""
        backend.revisions += EditRevisionEntity(
            documentId = docId,
            revisionNo = 1L,
            eventType = EditEventKind.TONE.id,
            payloadJson = legacyPayload,
            payloadSchemaVersion = EVENT_PAYLOAD_SCHEMA_VERSION,
            committedAt = 1L,
            checksum = EditChecksums.revisionChecksum(docId, 1L, EditEventKind.TONE.id, legacyPayload),
        )
        val failure = runBlocking {
            store.commit(docId, 0L, RecipeParameterEdit(AdjustmentId.EXPOSURE.recipePath, 1.0))
        }
        assertIs<ImageOpResult.Failure>(failure)
    }

    // ---- Undo / Redo（新規イベント方式）----

    @Test
    fun undo_restoresPreviousValue_viaNewEvent_andRedoReturnsIt() {
        val store = store()
        var state = bootstrap(store)
        state = commit(store, state, AdjustmentId.EXPOSURE, 1.0)
        state = commit(store, state, AdjustmentId.EXPOSURE, -2.0)
        assertEquals(-2.0, state.recipe.develop.tone.exposureEv, 1e-9)

        val undoResult = runBlocking { store.undo(state.documentId) }
        val undone = assertIs<ImageOpResult.Success<EditStore.UndoOutcome>>(undoResult).value
        val afterUndo = assertIs<EditStore.UndoOutcome.Applied>(undone).state
        assertEquals(1.0, afterUndo.recipe.develop.tone.exposureEv, 1e-9)
        assertFalse(afterUndo.canUndo)
        assertTrue(afterUndo.canRedo)
        assertEquals(RecipeParameterEdit.OP_UNDO, afterUndo.history.last().op)

        val redoResult = runBlocking { store.redo(afterUndo.documentId) }
        val redone = assertIs<ImageOpResult.Success<EditStore.UndoOutcome>>(redoResult).value
        val afterRedo = assertIs<EditStore.UndoOutcome.Applied>(redone).state
        assertEquals(-2.0, afterRedo.recipe.develop.tone.exposureEv, 1e-9)
        assertTrue(afterRedo.canUndo)
        assertFalse(afterRedo.canRedo)
        assertEquals(RecipeParameterEdit.OP_REDO, afterRedo.history.last().op)
    }

    @Test
    fun undo_firstReachForPath_restoresSchemaDefault() {
        val store = store()
        var state = bootstrap(store)
        state = commit(store, state, AdjustmentId.SATURATION, 40.0)
        val undone = runBlocking { store.undo(state.documentId) }
        val afterUndo = assertIs<EditStore.UndoOutcome.Applied>(
            assertIs<ImageOpResult.Success<EditStore.UndoOutcome>>(undone).value,
        ).state
        assertEquals(
            AdjustmentId.SATURATION.sliderValueOf(afterUndo.recipe),
            0.0,
            1e-9,
        )
        assertEquals(0.0, afterUndo.recipe.develop.color.saturation, 1e-9)
    }

    @Test
    fun redo_afterConvergentCommit_isNoop() {
        val store = store()
        var state = bootstrap(store)
        state = commit(store, state, AdjustmentId.CONTRAST, 30.0)
        val undone = assertIs<EditStore.UndoOutcome.Applied>(
            assertIs<ImageOpResult.Success<EditStore.UndoOutcome>>(runBlocking { store.undo(state.documentId) }).value,
        ).state
        // 分岐確定（apply）で Redo 分岐は消える（固定単分岐モデル）。
        val diverged = commit(store, undone, AdjustmentId.CONTRAST, 60.0)
        assertFalse(diverged.canRedo)
        val redoOutcome = runBlocking { store.redo(diverged.documentId) }
        val noop = assertIs<ImageOpResult.Success<EditStore.UndoOutcome>>(redoOutcome).value
        assertIs<EditStore.UndoOutcome.Noop>(noop)
    }

    @Test
    fun undo_onEmptyHistory_isNoop() {
        val store = store()
        val base = bootstrap(store)
        val outcome = runBlocking { store.undo(base.documentId) }
        assertIs<ImageOpResult.Success<EditStore.UndoOutcome>>(outcome)
        assertIs<EditStore.UndoOutcome.Noop>(assertIs<ImageOpResult.Success<EditStore.UndoOutcome>>(outcome).value)
    }

    // ---- 正本列挙 / 既知・未知イベントの永続契約（ADR-022）----

    @Test
    fun commit_writesCanonicalEventType_bySliderDomain() {
        val backend = FakeBackend()
        val store = store(backend)
        var state = bootstrap(store)
        state = commit(store, state, AdjustmentId.EXPOSURE, 1.0)
        state = commit(store, state, AdjustmentId.SATURATION, 30.0)
        state = commit(store, state, AdjustmentId.TEMPERATURE, -40.0)
        assertEquals(
            listOf(
                EditEventKind.TONE.id,
                EditEventKind.COLOR.id,
                EditEventKind.WHITE_BALANCE.id,
            ),
            backend.revisions.map { it.eventType },
        )
        assertEquals(1, backend.revisions[0].payloadSchemaVersion)
    }

    @Test
    fun unknownEventType_isPreservedOnly_appliedNeverAndCounted() {
        val backend = FakeBackend()
        val store = store(backend)
        val base = bootstrap(store)
        val docId = base.documentId
        val payload = """{"path":"develop.tone.exposureEv","value":2.0,"op":"apply"}"""
        val mystery = EditRevisionEntity(
            documentId = docId,
            revisionNo = 1L,
            eventType = "mystery",
            payloadJson = payload,
            payloadSchemaVersion = 1,
            committedAt = 1L,
            checksum = EditChecksums.revisionChecksum(docId, 1L, "mystery", payload),
        )
        backend.revisions += mystery
        // 疑似シナリオ内で文書カーソルも整合させる（本件の対象は保持挙動）。
        backend.docs[docId] = backend.docs[docId]!!.copy(currentRevision = 1L)

        val reloaded = assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            runBlocking { store.load(docId) },
        ).value
        // 保持: 行は無改写・履歴に現れ・適用はしない（既定値へは落とさない）。
        assertEquals(listOf(mystery), backend.revisions.toList())
        assertEquals(1, reloaded.history.size)
        assertEquals("mystery", reloaded.history.single().eventType)
        assertEquals(0.0, reloaded.recipe.develop.tone.exposureEv, 1e-9)
        assertEquals(1, reloaded.decodeSummary.preservedOnly)
        assertEquals(0, reloaded.decodeSummary.applied)
        assertTrue(reloaded.decodeSummary.isFullyApplied.not())

        // 往復: 保持後も連続確定は破たんしない（revisionNo は継続、既知イベントは適用）。
        val next = commit(store, reloaded, AdjustmentId.EXPOSURE, -1.0)
        assertEquals(2L, next.currentRevision)
        assertEquals(-1.0, next.recipe.develop.tone.exposureEv, 1e-9)
        assertEquals(1, next.decodeSummary.applied)
        assertEquals(1, next.decodeSummary.preservedOnly)
    }

    @Test
    fun reservedKindEvent_isPreservedOnly_neverApplied() {
        val backend = FakeBackend()
        val store = store(backend)
        val base = bootstrap(store)
        val docId = base.documentId
        // RESERVED 種（M1 では生成しない）が DB に入っている場合でも解釈しない。
        val payload = """{"points":[[0.0,0.0],[1.0,1.0]]}"""
        backend.revisions += EditRevisionEntity(
            documentId = docId,
            revisionNo = 1L,
            eventType = EditEventKind.CURVE.id,
            payloadJson = payload,
            payloadSchemaVersion = 1,
            committedAt = 1L,
            checksum = EditChecksums.revisionChecksum(docId, 1L, EditEventKind.CURVE.id, payload),
        )
        val reloaded = assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            runBlocking { store.load(docId) },
        ).value
        assertEquals(0.0, reloaded.recipe.develop.tone.exposureEv, 1e-9)
        assertEquals(1, reloaded.decodeSummary.preservedOnly)
        assertEquals(0, reloaded.decodeSummary.applied)
    }

    /**
     * P4: 新種（curve）に対する「旧構築の解読動作」の維持検証。
     * - P3 までの構築では curve は RESERVED_M2 であり、どんな payload でも
     *   PRESERVED_ONLY（適用しない・既定値に落とさない）だった。
     * - 旧 payload 形状（本構築の v1 形状でない点列）が DB に混入した場合も、
     *   現行構築は同規約（preservedOnly）を維持する。旧バージョン→本バージョンの
     *   将来データは（旧 payload が v1 新形状のため）本構築では「適用」側へ回る —
     *   逆方向（本構築→旧構築）では RESERVED のまま拒否解釈されるため、
     *   行は無改写で保持される（ここでは現行構築側の保持挙動を同等形状で再現検証）。
     */
    @Test
    fun curveKindPayload_inOldShape_staysPreservedOnly_matchingLegacyDecode() {
        val backend = FakeBackend()
        val store = store(backend)
        val base = bootstrap(store)
        val docId = base.documentId
        // 本構築の commitCurve と同 eventType を使いながら、path を持たない旧想定 payload。
        val payload = """{"path":"develop.curve.rgb","value":1.0,"op":"apply"}"""
        backend.revisions += EditRevisionEntity(
            documentId = docId,
            revisionNo = 1L,
            eventType = EditEventKind.CURVE.id,
            payloadJson = payload,
            payloadSchemaVersion = 1,
            committedAt = 1L,
            checksum = EditChecksums.revisionChecksum(docId, 1L, EditEventKind.CURVE.id, payload),
        )
        // 疑似シナリオ内で文書カーソルも整合させる（本件の対象は保持挙動）。
        backend.docs[docId] = backend.docs[docId]!!.copy(currentRevision = 1L)
        val reloaded = assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            runBlocking { store.load(docId) },
        ).value
        // 旧形状は既定何に落とされず、preservedOnly として明示集計される。
        assertEquals(0, reloaded.decodeSummary.applied)
        assertEquals(1, reloaded.decodeSummary.preservedOnly)
        assertEquals(false, reloaded.decodeSummary.isFullyApplied)
        // 未解釈でも連続確定は破たんしない。
        val next = commit(store, reloaded, AdjustmentId.EXPOSURE, 1.0)
        assertEquals(2L, next.currentRevision)
        assertEquals(1.0, next.recipe.develop.tone.exposureEv, 1e-9)
        assertEquals(1, next.decodeSummary.preservedOnly)
    }

    @Test
    fun higherPayloadSchemaVersion_isUnsupported_notAppliedAndCounted() {
        val backend = FakeBackend()
        val store = store(backend)
        val base = bootstrap(store)
        val docId = base.documentId
        val payload = """{"path":"develop.tone.exposureEv","value":3.0,"op":"apply"}"""
        backend.revisions += EditRevisionEntity(
            documentId = docId,
            revisionNo = 1L,
            eventType = EditEventKind.TONE.id,
            payloadJson = payload,
            payloadSchemaVersion = 2, // 未来バージョン: 解釈せず保持のみ。
            committedAt = 1L,
            checksum = EditChecksums.revisionChecksum(docId, 1L, EditEventKind.TONE.id, payload),
        )
        val reloaded = assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            runBlocking { store.load(docId) },
        ).value
        assertEquals(0.0, reloaded.recipe.develop.tone.exposureEv, 1e-9) // 3.0 を適用しない。
        assertEquals(1, reloaded.decodeSummary.unsupportedVersion)
        assertEquals(0, reloaded.decodeSummary.applied)
        // 行は無改写。
        assertEquals(2, backend.revisions.single().payloadSchemaVersion)
        // codec 契約: 未対応バージョンは明示失敗（既定値へのフォールバック禁止）。
        assertFailsWith<IllegalArgumentException> {
            EditRecordCodec.decodeEventPayload(payload, 2)
        }
        assertEquals(
            3.0,
            (EditRecordCodec.decodeEventPayload(payload, 1)["value"] as JsonPrimitive).doubleOrNull!!,
            1e-9,
        )
    }

    @Test
    fun legacyParamAlias_stillApplies_forKnownPath() {
        val backend = FakeBackend()
        val store = store(backend)
        val base = bootstrap(store)
        val docId = base.documentId
        val payload = """{"path":"develop.color.saturation","value":40.0,"op":"apply"}"""
        backend.revisions += EditRevisionEntity(
            documentId = docId,
            revisionNo = 1L,
            eventType = LEGACY_EVENT_TYPE_PARAM,
            payloadJson = payload,
            payloadSchemaVersion = 1,
            committedAt = 1L,
            checksum = EditChecksums.revisionChecksum(
                docId,
                1L,
                LEGACY_EVENT_TYPE_PARAM,
                payload,
            ),
        )
        val reloaded = assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            runBlocking { store.load(docId) },
        ).value
        assertEquals(0.4, reloaded.recipe.develop.color.saturation, 1e-9)
        assertEquals(1, reloaded.decodeSummary.applied)
        assertEquals(0, reloaded.decodeSummary.preservedOnly)
    }

    // ---- P4 ノード種（curve / geometry）の確定・絶対状態 undo/redo ----

    private fun commitCurve(
        store: EditStore,
        base: EditStore.EditStoreState,
        curve: EditRecipe.Curve,
        editedChannel: String? = null,
    ): EditStore.EditStoreState = runBlocking {
        assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            store.commitCurve(base.documentId, base.currentRevision, curve, editedChannel),
        ).value
    }

    private fun commitGeometry(
        store: EditStore,
        base: EditStore.EditStoreState,
        geometry: EditRecipe.GeometryParams,
    ): EditStore.EditStoreState = runBlocking {
        assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            store.commitGeometry(base.documentId, base.currentRevision, geometry),
        ).value
    }

    @Test
    fun commitCurve_writesCanonicalKind_appliesToRecipe_andNormalizes() {
        val backend = FakeBackend()
        val store = store(backend)
        var state = bootstrap(store)
        state = commitCurve(
            store,
            state,
            EditRecipe.Curve(rgb = listOf(listOf(0.8, 1.0), listOf(1.0, 0.6), listOf(0.2, 0.0))),
        ) // 無順序入力
        assertEquals(1L, state.currentRevision)
        assertEquals(EditEventKind.CURVE.id, backend.revisions.single().eventType)
        // 正規化済み（x 昇順・y クランプ）。
        assertEquals(
            listOf(listOf(0.2, 0.0), listOf(0.8, 1.0), listOf(1.0, 0.6)),
            state.recipe.develop.curve.rgb,
        )
        assertTrue(state.canUndo)

        // 不正な点列は FAIL（既定値に落とさない）。
        val failure = runBlocking {
            store.commitCurve(state.documentId, state.currentRevision, EditRecipe.Curve(rgb = listOf(listOf(0.5, 0.5))))
        }
        assertIs<ImageOpResult.Failure>(failure)
    }

    @Test
    fun undoCurve_restoresPreviousPoints_andRedoRestoresIt() {
        val store = store()
        var state = bootstrap(store)
        state = commitCurve(store, state, EditRecipe.Curve(rgb = listOf(listOf(0.0, 0.0), listOf(0.5, 0.7), listOf(1.0, 1.0))))
        state = commitCurve(store, state, EditRecipe.Curve(rgb = listOf(listOf(0.0, 0.5), listOf(1.0, 1.0))))
        assertEquals(listOf(0.0, 0.5), state.recipe.develop.curve.rgb[0])

        val afterUndo = assertIs<EditStore.UndoOutcome.Applied>(
            assertIs<ImageOpResult.Success<EditStore.UndoOutcome>>(
                runBlocking { store.undo(state.documentId) },
            ).value,
        ).state
        assertEquals(listOf(listOf(0.0, 0.0), listOf(0.5, 0.7), listOf(1.0, 1.0)), afterUndo.recipe.develop.curve.rgb)
        assertFalse(afterUndo.canUndo)
        assertTrue(afterUndo.canRedo)

        val afterRedo = assertIs<EditStore.UndoOutcome.Applied>(
            assertIs<ImageOpResult.Success<EditStore.UndoOutcome>>(
                runBlocking { store.redo(afterUndo.documentId) },
            ).value,
        ).state
        assertEquals(listOf(listOf(0.0, 0.5), listOf(1.0, 1.0)), afterRedo.recipe.develop.curve.rgb)
    }

    @Test
    fun undoFirstCurveEvent_restoresNeutralCurve() {
        val store = store()
        var state = bootstrap(store)
        state = commitCurve(store, state, EditRecipe.Curve(rgb = listOf(listOf(0.25, 0.1), listOf(0.75, 0.9))))
        val afterUndo = assertIs<EditStore.UndoOutcome.Applied>(
            assertIs<ImageOpResult.Success<EditStore.UndoOutcome>>(
                runBlocking { store.undo(state.documentId) },
            ).value,
        ).state
        assertEquals(EditNodeSpecs.CURVE_NEUTRAL_RGB, afterUndo.recipe.develop.curve.rgb)
    }

    // ---- PR-28a: R/G/B チャンネルカーブ（curve 全量 payload・ch 補助値）----

    @Test
    fun commitChannelCurve_writesFullCurvePayload_andAppliesChannelsToRecipe() {
        val backend = FakeBackend()
        val store = store(backend)
        var state = bootstrap(store)
        state = commitCurve(
            store,
            state,
            EditRecipe.Curve(
                rgb = EditNodeSpecs.CURVE_NEUTRAL_RGB,
                r = listOf(listOf(0.0, 0.0), listOf(0.5, 0.62), listOf(1.0, 1.0)),
                b = listOf(listOf(0.0, 0.0), listOf(1.0, 0.85)),
            ),
            editedChannel = "r",
        )
        assertEquals(EditEventKind.CURVE.id, backend.revisions.single().eventType)
        // payload 形状 v1: rgb 必須・非空チャンネルのみ・ch は編集対象。
        val payload = EditRecordCodec.decodeEventPayload(backend.revisions.single().payloadJson)
        val curveObj = payload["curve"] as JsonObject
        assertEquals(EditNodeSpecs.CURVE_NEUTRAL_RGB, parsePointsOf(curveObj, "rgb"))
        assertEquals(listOf(listOf(0.0, 0.0), listOf(0.5, 0.62), listOf(1.0, 1.0)), parsePointsOf(curveObj, "r"))
        assertEquals(listOf(listOf(0.0, 0.0), listOf(1.0, 0.85)), parsePointsOf(curveObj, "b"))
        assertNull(curveObj["g"]) // 未編集チャンネルは収めない
        assertEquals("r", (curveObj["ch"] as JsonPrimitive).content)
        // recipe への適用（チャンネルごと入る・master は無変化）。
        assertEquals(EditNodeSpecs.CURVE_NEUTRAL_RGB, state.recipe.develop.curve.rgb)
        assertEquals(listOf(listOf(0.0, 0.0), listOf(0.5, 0.62), listOf(1.0, 1.0)), state.recipe.develop.curve.r)
        assertEquals(listOf(listOf(0.0, 0.0), listOf(1.0, 0.85)), state.recipe.develop.curve.b)
        assertEquals(emptyList<List<Double>>(), state.recipe.develop.curve.g)
        // 履歴項目: カーブ全量 + 編集チャンネル。
        assertEquals("r", state.history.single().curveChannel)
        assertEquals(state.recipe.develop.curve, state.history.single().curve)
    }

    @Test
    fun undoChannelCurve_restoresEmptyAndRedoRestoresPoints() {
        val store = store()
        var state = bootstrap(store)
        state = commitCurve(
            store,
            state,
            EditRecipe.Curve(
                rgb = EditNodeSpecs.CURVE_NEUTRAL_RGB,
                r = listOf(listOf(0.0, 0.0), listOf(0.5, 0.62), listOf(1.0, 1.0)),
            ),
            editedChannel = "r",
        )
        assertTrue(state.recipe.develop.curve.r.isNotEmpty())

        // undo はカーブ全量を戻す（フォールバック=中立 rgb+未編集ch ではなく、直前状態へ）。
        val afterUndo = applied(runBlocking { store.undo(state.documentId) })
        assertEquals(EditNodeSpecs.CURVE_NEUTRAL_RGB, afterUndo.recipe.develop.curve.rgb)
        assertEquals(emptyList<List<Double>>(), afterUndo.recipe.develop.curve.r)
        assertEquals("r", afterUndo.history.last().curveChannel)
        assertTrue(afterUndo.canRedo)

        val afterRedo = applied(runBlocking { store.redo(afterUndo.documentId) })
        assertEquals(listOf(listOf(0.0, 0.0), listOf(0.5, 0.62), listOf(1.0, 1.0)), afterRedo.recipe.develop.curve.r)
        assertEquals("r", afterRedo.history.last().curveChannel)
    }

    @Test
    fun commitChannelCurve_invalidChannel_orInvalidEditedChannel_failsNoEventAppended() {
        val backend = FakeBackend()
        val store = store(backend)
        val state = bootstrap(store)
        // 非空チャンネルの正規化失敗（1点のみ）は FAIL（master と同一の規約継続）。
        val badChannel = runBlocking {
            store.commitCurve(
                state.documentId,
                state.currentRevision,
                EditRecipe.Curve(r = listOf(listOf(0.4, 0.7))),
                "r",
            )
        }
        assertIs<ImageOpResult.Failure>(badChannel)
        // editedChannel は r/g/b または null のみ受理。
        val badKey = runBlocking {
            store.commitCurve(state.documentId, state.currentRevision, EditRecipe.Curve(), "red")
        }
        assertIs<ImageOpResult.Failure>(badKey)
        assertEquals(0, backend.revisions.size) // 失敗時はイベントを追加しない（§7.2）。
    }

    @Test
    fun rawCurvePayload_withInvalidCh_orMalformedChannel_staysPreservedOnly() {
        val backend = FakeBackend()
        val store = store(backend)
        val base = bootstrap(store)
        val docId = base.documentId
        // ch が不正値: 行全体を解釈せず保持のみ（部分適用を作らない）。
        val badCh = """{"op":"apply","curve":{"ch":"red","rgb":[[0.0,0.0],[1.0,1.0]]}}"""
        // チャンネル点列が正規化不能（1点）: 同じく保持のみ。
        val badChannel = """{"op":"apply","curve":{"rgb":[[0.0,0.0],[1.0,1.0]],"r":[[0.4,0.7]]}}"""
        backend.docs[docId] = backend.docs[docId]!!.copy(currentRevision = 2L)
        backend.revisions += EditRevisionEntity(
            documentId = docId,
            revisionNo = 1L,
            eventType = EditEventKind.CURVE.id,
            payloadJson = badCh,
            payloadSchemaVersion = 1,
            committedAt = 1L,
            checksum = EditChecksums.revisionChecksum(docId, 1L, EditEventKind.CURVE.id, badCh),
        )
        backend.revisions += EditRevisionEntity(
            documentId = docId,
            revisionNo = 2L,
            eventType = EditEventKind.CURVE.id,
            payloadJson = badChannel,
            payloadSchemaVersion = 1,
            committedAt = 2L,
            checksum = EditChecksums.revisionChecksum(docId, 2L, EditEventKind.CURVE.id, badChannel),
        )
        val reloaded = assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            runBlocking { store.load(docId) },
        ).value
        assertEquals(0, reloaded.decodeSummary.applied)
        assertEquals(2, reloaded.decodeSummary.preservedOnly)
        // どちらも中立的な既定レシピから動かさない（未解釈に落とさない）。
        assertEquals(EditNodeSpecs.CURVE_NEUTRAL_RGB, reloaded.recipe.develop.curve.rgb)
        assertEquals(emptyList<List<Double>>(), reloaded.recipe.develop.curve.r)
    }

    /** raw payload の点列キー → 点列（型が違えば null）。テスト用の限定的読み出し。 */
    private fun parsePointsOf(curve: JsonObject, key: String): List<List<Double>>? {
        val arr = curve[key] as? kotlinx.serialization.json.JsonArray ?: return null
        return arr.map { pt ->
            val nums = pt as? kotlinx.serialization.json.JsonArray ?: return null
            nums.map { (it as? JsonPrimitive)?.doubleOrNull ?: return null }
        }
    }

    /** UndoOutcome を Applied 状態へほどく（既存コードの型はやや冗長のため共通化）。 */
    private fun applied(outcome: ImageOpResult<EditStore.UndoOutcome>): EditStore.EditStoreState =
        assertIs<EditStore.UndoOutcome.Applied>(
            assertIs<ImageOpResult.Success<EditStore.UndoOutcome>>(outcome).value,
        ).state

    @Test
    fun commitGeometry_undoGeometry_roundTrip() {
        val backend = FakeBackend()
        val store = store(backend)
        var state = bootstrap(store)
        state = commitGeometry(
            store,
            state,
            EditRecipe.GeometryParams(
                cropNorm = listOf(0.1, 0.1, 0.9, 1.0),
                angleDeg = 90.0,
                mirrorX = true,
                mirrorY = true,
            ),
        )
        assertEquals(EditEventKind.GEOMETRY.id, backend.revisions.single().eventType)
        assertEquals(90.0, state.recipe.geometry.angleDeg, 1e-9)
        assertTrue(state.recipe.geometry.mirrorX && state.recipe.geometry.mirrorY)
        assertEquals(listOf(0.1, 0.1, 0.9, 1.0), state.recipe.geometry.cropNorm)

        // 次の状態へ（反転を解消・180°へ）。
        state = commitGeometry(
            store,
            state,
            EditRecipe.GeometryParams(cropNorm = listOf(0.1, 0.1, 0.9, 1.0), angleDeg = 180.0),
        )
        val afterUndo = assertIs<EditStore.UndoOutcome.Applied>(
            assertIs<ImageOpResult.Success<EditStore.UndoOutcome>>(
                runBlocking { store.undo(state.documentId) },
            ).value,
        ).state
        assertEquals(90.0, afterUndo.recipe.geometry.angleDeg, 1e-9)
        assertTrue(afterUndo.recipe.geometry.mirrorX)
        val afterRedo = assertIs<EditStore.UndoOutcome.Applied>(
            assertIs<ImageOpResult.Success<EditStore.UndoOutcome>>(
                runBlocking { store.redo(afterUndo.documentId) },
            ).value,
        ).state
        assertEquals(180.0, afterRedo.recipe.geometry.angleDeg, 1e-9)
        assertFalse(afterRedo.recipe.geometry.mirrorX)
    }

    @Test
    fun commitGeometry_freeAngle_isAcceptedCanonicalizedAndUndoable() {
        // PR-28b: 90°刻み限定を解除。任意角は [0,360) へ正準化して保存し、
        // payload 形状（angleDeg/mirrorX/mirrorY/crop）は v1 のまま（後方互換）。
        val backend = FakeBackend()
        val store = store(backend)
        var state = bootstrap(store)

        state = commitGeometry(store, state, EditRecipe.GeometryParams(angleDeg = 3.5))
        assertEquals(3.5, state.recipe.geometry.angleDeg, 0.0)
        assertEquals(EditEventKind.GEOMETRY.id, backend.revisions.single().eventType)
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(backend.revisions.single().payloadJson)
            as kotlinx.serialization.json.JsonObject
        val geo = payload["geometry"] as kotlinx.serialization.json.JsonObject
        assertEquals(3.5, (geo["angleDeg"] as JsonPrimitive).doubleOrNull!!, 0.0)

        // -90° は 270° として保存（同じ回転の正準表現）。
        state = commitGeometry(store, state, EditRecipe.GeometryParams(angleDeg = -90.0))
        assertEquals(270.0, state.recipe.geometry.angleDeg, 0.0)

        // 360° は 0°（中立）。
        state = commitGeometry(store, state, EditRecipe.GeometryParams(angleDeg = 360.0))
        assertEquals(0.0, state.recipe.geometry.angleDeg, 0.0)

        // undo/redo は絶対状態スナップショット（任意角でも同じ契約）。
        val afterUndo = applied(runBlocking { store.undo(state.documentId) })
        assertEquals(270.0, afterUndo.recipe.geometry.angleDeg, 0.0)
        val afterRedo = applied(runBlocking { store.redo(afterUndo.documentId) })
        assertEquals(0.0, afterRedo.recipe.geometry.angleDeg, 0.0)

        // 再読込（DB 再構築経路）でも任意角が保たれる。
        val reloaded = assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            runBlocking { store.load(state.documentId) },
        ).value
        assertEquals(0.0, reloaded.recipe.geometry.angleDeg, 0.0)
    }

    @Test
    fun commitGeometry_nonFiniteAngle_isRejected_noEventAppended() {
        val backend = FakeBackend()
        val store = store(backend)
        val base = bootstrap(store)
        val failure = runBlocking {
            store.commitGeometry(base.documentId, base.currentRevision, EditRecipe.GeometryParams(angleDeg = Double.NaN))
        }
        assertIs<ImageOpResult.Failure>(failure)
        assertEquals(0, backend.revisions.size)
    }

    @Test
    fun detailSliders_viaStandardCommit_applyNormalizeAndUndo() {
        // PR-28c: detail はスライダー共通 payload（path/value/op）。0..100 → 0..1 正規化。
        val store = store()
        var state = bootstrap(store)
        state = commit(store, state, AdjustmentId.DETAIL_SHARPEN, 60.0)
        state = commit(store, state, AdjustmentId.DETAIL_LUMA_NOISE, 25.0)
        assertEquals(0.6, state.recipe.develop.detail.sharpen, 1e-9)
        assertEquals(0.25, state.recipe.develop.detail.lumaNoise, 1e-9)
        assertEquals(60.0, AdjustmentId.DETAIL_SHARPEN.sliderValueOf(state.recipe), 1e-9)
        assertEquals(25.0, AdjustmentId.DETAIL_LUMA_NOISE.sliderValueOf(state.recipe), 1e-9)

        val afterUndo = applied(runBlocking { store.undo(state.documentId) })
        assertEquals(0.6, afterUndo.recipe.develop.detail.sharpen, 1e-9)
        assertEquals(0.0, afterUndo.recipe.develop.detail.lumaNoise, 1e-9)

        // 再読込でも同じ値（payload 再生）。
        val reloaded = assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            runBlocking { store.load(state.documentId) },
        ).value
        assertEquals(0.6, reloaded.recipe.develop.detail.sharpen, 1e-9)
    }

    @Test
    fun hslSlider_viaStandardCommit_appliesAndUndoes() {
        val store = store()
        var state = bootstrap(store)
        state = commit(store, state, AdjustmentId.HSL_RED_HUE, 50.0)
        assertEquals(0.5, state.recipe.develop.color.hsl["red.hue"] ?: 0.0, 1e-9)
        val afterUndo = assertIs<EditStore.UndoOutcome.Applied>(
            assertIs<ImageOpResult.Success<EditStore.UndoOutcome>>(
                runBlocking { store.undo(state.documentId) },
            ).value,
        ).state
        assertEquals(0.0, afterUndo.recipe.develop.color.hsl["red.hue"] ?: 0.0, 1e-9)
    }

    // ---- スナップショット（§3.3: 20–50イベント周期・チェックサムゲート）----

    @Test
    fun snapshot_createdAtCadence_andReplayStartsFromIt() {
        val backend = FakeBackend()
        val store = store(backend)
        var state = bootstrap(store)
        repeat(EditStore.SNAPSHOT_EVERY) { index ->
            state = commit(store, state, AdjustmentId.EXPOSURE, if (index % 2 == 0) 0.1 else -0.1)
        }
        assertEquals(1, backend.snapshots.size)
        val snapshot = backend.snapshots.single()
        assertEquals(
            EditStore.SNAPSHOT_EVERY.toLong(),
            snapshot.revisionNo,
        )
        // スナップショットチェックサム検証 → 再生はスナップショット基点。
        val reloaded = runBlocking { store.load(state.documentId) }
        val reloadedState = assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(reloaded).value
        assertEquals(EditStore.SNAPSHOT_EVERY.toLong(), reloadedState.snapshotRevisionNo)
        assertEquals(
            state.recipe.develop.tone.exposureEv,
            reloadedState.recipe.develop.tone.exposureEv,
            1e-9,
        )
    }

    @Test
    fun corruptedEvent_isSkipped_atReplay() {
        val backend = FakeBackend()
        val store = store(backend)
        var state = bootstrap(store)
        state = commit(store, state, AdjustmentId.EXPOSURE, 2.0)
        state = commit(store, state, AdjustmentId.SATURATION, 80.0)
        // ペイロードを書換（チェックサム不一致）。
        val corrupted = backend.revisions.last()
        backend.revisions.remove(corrupted)
        backend.revisions += corrupted.copy(payloadJson = """{"path":"develop.color.saturation","value":-999,"op":"apply"}""")
        val reloaded = assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            runBlocking { store.load(state.documentId) },
        ).value
        assertEquals(2.0, reloaded.recipe.develop.tone.exposureEv, 1e-9)
        assertEquals(0.0, reloaded.recipe.develop.color.saturation, 1e-9)
        // 履歴表示にも混入しない。
        assertEquals(1, reloaded.history.size)
    }

    @Test
    fun load_missingDocument_failsWithoutCrash() {
        val store = store()
        val failure = runBlocking { store.load("missing-doc") }
        assertIs<ImageOpResult.Failure>(failure)
    }

    @Test
    fun recipeRoundTrip_viaCodec_keepsRecipeEdgeValues() {
        val recipe = EditRecipe(
            documentId = "doc",
            source = EditRecipe.RecipeSource("asset", "fp"),
            develop = EditRecipe.DevelopParams(
                tone = EditRecipe.Tone(exposureEv = -2.0, contrast = -1.0, blacks = 1.0),
                color = EditRecipe.ColorParams(vibrance = -1.0, saturation = 1.0, hsl = mapOf("red.hue" to 0.5)),
                curve = EditRecipe.Curve(rgb = listOf(listOf(0.0, 0.1), listOf(0.5, 0.4), listOf(1.0, 1.0))),
            ),
            geometry = EditRecipe.GeometryParams(
                cropNorm = listOf(0.1, 0.2, 0.9, 1.0),
                angleDeg = 270.0,
                mirrorX = true,
                mirrorY = true,
            ),
        )
        val json = EditRecordCodec.encodeRecipe(recipe)
        // 旧/未知スキーマバージョンは拒否（§3.2: 旧版読み出し+移行テストの前置）。
        val rejected = runCatching { EditRecordCodec.decodeRecipe(json, 99) }
        assertTrue(rejected.isFailure)
        val decoded = EditRecordCodec.decodeRecipe(json, RecipeSchemaVersion1)
        assertEquals(recipe.develop, decoded.develop)
        assertEquals(recipe.geometry, decoded.geometry)
    }

    // ---- PR-29a/29b: 局所マスク（LOCAL イベント・payload v1・絶対状態スナップショット）----

    /** 正規化可能なマスク（linear・必須4点 + 既定 feather を明示）。 */
    private fun mask(
        id: String = "m1",
        maskType: String = "linear",
        parameters: Map<String, Double> = mapOf(
            "x0" to 0.2,
            "y0" to 0.2,
            "x1" to 0.8,
            "y1" to 0.8,
            "feather" to 0.6,
        ),
        adjustments: Map<String, Double> = mapOf("develop.tone.exposureEv" to 0.7),
    ) = EditRecipe.MaskNode(
        maskId = id,
        maskType = maskType,
        parameters = parameters,
        adjustments = adjustments,
    )

    private fun commitMaskList(
        store: EditStore,
        base: EditStore.EditStoreState,
        masks: List<EditRecipe.MaskNode>,
        maskId: String? = null,
    ): EditStore.EditStoreState = runBlocking {
        assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            store.commitMasks(base.documentId, base.currentRevision, masks, maskId),
        ).value
    }

    @Test
    fun commitMasks_writesLocalPayloadV1_appliesRecipeAndHistory() {
        val backend = FakeBackend()
        val store = store(backend)
        var state = bootstrap(store)

        state = commitMaskList(store, state, listOf(mask(id = "m1")), maskId = "m1")
        assertEquals(1L, state.currentRevision)
        // 正本列挙: LOCAL は IMPLEMENTED_M1 の payload v1（Room schema 不変）。
        assertEquals(EditEventKind.LOCAL.id, backend.revisions.single().eventType)
        assertEquals(1, backend.revisions.single().payloadSchemaVersion)
        assertEquals(EditEventStatus.IMPLEMENTED_M1, EditEventKind.LOCAL.status)
        assertEquals(1, EditEventKind.LOCAL.payloadShapeSchemaVersion)
        // レシピへ適用（正規化済み）。
        val applied = state.recipe.masks.single()
        assertEquals("m1", applied.maskId)
        assertEquals("linear", applied.maskType)
        assertEquals(1, applied.version)
        assertEquals(0.7, applied.adjustments.getValue("develop.tone.exposureEv"), 0.0)
        assertEquals(0.6, applied.parameters.getValue("feather"), 0.0)
        assertTrue(applied.brushStrokes.isEmpty())

        // payload 形状 v1: {"masks":[...],"maskId":...,"op":...}
        val payload = EditRecordCodec.decodeEventPayload(backend.revisions.single().payloadJson)
        assertEquals("apply", (payload["op"] as JsonPrimitive).content)
        assertEquals("m1", (payload["maskId"] as JsonPrimitive).content)
        val masksArray = payload["masks"] as kotlinx.serialization.json.JsonArray
        val maskObject = masksArray.single() as JsonObject
        assertEquals("m1", (maskObject["maskId"] as JsonPrimitive).content)
        assertEquals("linear", (maskObject["maskType"] as JsonPrimitive).content)
        assertEquals(1, (maskObject["version"] as JsonPrimitive).content.toInt())
        assertEquals(
            listOf("x0", "y0", "x1", "y1", "feather"),
            (maskObject["parameters"] as JsonObject).keys.toList(),
        )
        assertEquals(
            listOf("develop.tone.exposureEv"),
            (maskObject["adjustments"] as JsonObject).keys.toList(),
        )
        assertTrue((maskObject["brushStrokes"] as kotlinx.serialization.json.JsonArray).isEmpty())
        // 履歴: 絶対状態（masks）と操作対象 maskId を持つ。
        assertEquals(1, state.history.single().masks!!.size)
        assertEquals("m1", state.history.single().maskId)
        assertTrue(state.canUndo)
    }

    @Test
    fun commitMasks_brushStrokesRoundTripThroughPayload() {
        val backend = FakeBackend()
        val store = store(backend)
        var state = bootstrap(store)
        state = commitMaskList(
            store,
            state,
            listOf(
                EditRecipe.MaskNode(
                    maskId = "b1",
                    maskType = "brush",
                    brushStrokes = listOf(
                        EditRecipe.BrushStroke(0.25, 0.75, 0.1, opacity = 0.5),
                        EditRecipe.BrushStroke(0.5, 0.5, 0.2),
                    ),
                    adjustments = mapOf("develop.color.saturation" to -0.5),
                ),
            ),
        )
        assertEquals(2, state.recipe.masks.single().brushStrokes.size)
        assertEquals(
            listOf(
                EditRecipe.BrushStroke(0.25, 0.75, 0.1, opacity = 0.5),
                EditRecipe.BrushStroke(0.5, 0.5, 0.2, opacity = 1.0),
            ),
            state.recipe.masks.single().brushStrokes,
        )
        val payload = EditRecordCodec.decodeEventPayload(backend.revisions.single().payloadJson)
        val maskObject = (payload["masks"] as kotlinx.serialization.json.JsonArray).single() as JsonObject
        val strokes = maskObject["brushStrokes"] as kotlinx.serialization.json.JsonArray
        assertEquals(2, strokes.size)
        assertEquals(0.5, ((strokes[0] as JsonObject)["opacity"] as JsonPrimitive).content.toDouble(), 0.0)
    }

    @Test
    fun commitMasks_rejectsInvalidInputsWithoutAppendingEvents() {
        val backend = FakeBackend()
        val store = store(backend)
        val base = bootstrap(store)

        // 1) maskId 重複
        assertIs<ImageOpResult.Failure>(
            runBlocking {
                store.commitMasks(
                    base.documentId,
                    0L,
                    listOf(mask(id = "dup"), mask(id = "dup")),
                )
            },
        )
        // 2) 上限超過（MAX_MASKS + 1）
        assertIs<ImageOpResult.Failure>(
            runBlocking {
                store.commitMasks(
                    base.documentId,
                    0L,
                    List(MaskSpecs.MAX_MASKS + 1) { mask(id = "m$it") },
                )
            },
        )
        // 3) 未知種別
        assertIs<ImageOpResult.Failure>(
            runBlocking {
                store.commitMasks(base.documentId, 0L, listOf(mask(maskType = "vignette")))
            },
        )
        // 4) 不合格 adjustments パス / 範囲外
        assertIs<ImageOpResult.Failure>(
            runBlocking {
                store.commitMasks(
                    base.documentId,
                    0L,
                    listOf(mask(adjustments = mapOf("develop.detail.sharpen" to 0.5))),
                )
            },
        )
        assertIs<ImageOpResult.Failure>(
            runBlocking {
                store.commitMasks(
                    base.documentId,
                    0L,
                    listOf(mask(adjustments = mapOf("develop.tone.contrast" to 1.5))),
                )
            },
        )
        // 5) 楽観排他（stale baseRevision）
        assertIs<ImageOpResult.Failure>(
            runBlocking { store.commitMasks(base.documentId, 7L, listOf(mask())) },
        )
        // 失敗時はイベントを追加しない（§7.2）・レシピも動かさない。
        assertEquals(0, backend.revisions.size)
        assertEquals(0L, backend.docs[base.documentId]!!.currentRevision)
        assertTrue(base.recipe.masks.isEmpty())
    }

    @Test
    fun commitMasks_allowsLimitBoundaryAndEmptyList() {
        val backend = FakeBackend()
        val store = store(backend)
        var state = bootstrap(store)
        // 上限ちょうど（MAX_MASKS）は許可。
        state = commitMaskList(store, state, List(MaskSpecs.MAX_MASKS) { mask(id = "m$it") })
        assertEquals(8, state.recipe.masks.size)
        // 空リストは全削除として許可。
        state = commitMaskList(store, state, emptyList())
        assertTrue(state.recipe.masks.isEmpty())
        assertEquals(2L, state.currentRevision)
        val payload = EditRecordCodec.decodeEventPayload(backend.revisions.last().payloadJson)
        assertTrue((payload["masks"] as kotlinx.serialization.json.JsonArray).isEmpty())
        assertNull(payload["maskId"])
    }

    @Test
    fun undoRedoMasks_restoreAbsoluteStates() {
        val store = store()
        var state = bootstrap(store)
        val first = listOf(mask(id = "m1", adjustments = mapOf("develop.tone.exposureEv" to 0.7)))
        val second = listOf(
            mask(id = "m1", adjustments = mapOf("develop.tone.exposureEv" to 0.7)),
            mask(id = "m2", adjustments = mapOf("develop.color.saturation" to -0.5)),
        )
        state = commitMaskList(store, state, first, maskId = "m1")
        state = commitMaskList(store, state, second, maskId = "m2")
        assertEquals(2, state.recipe.masks.size)

        // undo は「1つ前の絶対状態」へ差し替え（削除ではなく新規イベント）。
        val afterUndo = applied(runBlocking { store.undo(state.documentId) })
        assertEquals(1, afterUndo.recipe.masks.size)
        assertEquals("m1", afterUndo.recipe.masks.single().maskId)
        assertEquals(RecipeParameterEdit.OP_UNDO, afterUndo.history.last().op)
        assertEquals(EditEventKind.LOCAL.id, afterUndo.history.last().eventType)
        assertTrue(afterUndo.canRedo)

        val afterRedo = applied(runBlocking { store.redo(afterUndo.documentId) })
        assertEquals(2, afterRedo.recipe.masks.size)
        assertEquals(listOf("m1", "m2"), afterRedo.recipe.masks.map { it.maskId })
        assertEquals(RecipeParameterEdit.OP_REDO, afterRedo.history.last().op)
    }

    @Test
    fun undoFirstMaskEvent_restoresEmptyMasks() {
        val store = store()
        var state = bootstrap(store)
        state = commitMaskList(store, state, listOf(mask(id = "m1")))
        val afterUndo = applied(runBlocking { store.undo(state.documentId) })
        assertTrue(afterUndo.recipe.masks.isEmpty())
        val afterRedo = applied(runBlocking { store.redo(afterUndo.documentId) })
        assertEquals("m1", afterRedo.recipe.masks.single().maskId)
    }

    @Test
    fun masksReload_fromEventPayload_andFromSnapshotRange() {
        val backend = FakeBackend()
        val store = store(backend)
        var state = bootstrap(store)
        state = commitMaskList(store, state, listOf(mask(id = "m1", maskType = "brush", parameters = mapOf("feather" to 0.3))))
        // イベント再生（スナップショット未作成）でも復元できる。
        val reloaded = assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            runBlocking { store.load(state.documentId) },
        ).value
        assertEquals(1, reloaded.recipe.masks.size)
        assertEquals("brush", reloaded.recipe.masks.single().maskType)
        assertEquals(1, reloaded.decodeSummary.applied)

        // スナップショット周期まで進める（24 件目で作成）。
        while (state.currentRevision < EditStore.SNAPSHOT_EVERY) {
            state = commit(store, state, AdjustmentId.EXPOSURE, 0.1)
        }
        assertEquals(1, backend.snapshots.size)
        assertTrue(backend.snapshots.single().recipeJson.contains("\"masks\""))
        val fromSnapshot = assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            runBlocking { store.load(state.documentId) },
        ).value
        assertEquals(EditStore.SNAPSHOT_EVERY.toLong(), fromSnapshot.snapshotRevisionNo)
        assertEquals("m1", fromSnapshot.recipe.masks.single().maskId)
        // スナップショット範囲内の LOCAL イベントは二重適用しない（適用数に数えない）。
        assertEquals(0, fromSnapshot.decodeSummary.applied)
    }

    @Test
    fun invalidMaskPayloadRows_stayPreservedOnly() {
        val backend = FakeBackend()
        val store = store(backend)
        val base = bootstrap(store)
        val docId = base.documentId
        val payloads = listOf(
            // 未知種別
            """{"op":"apply","masks":[{"maskId":"x","maskType":"vignette"}]}""",
            // masks が配列でない
            """{"op":"apply","masks":"nope"}""",
            // masks 欠落
            """{"op":"apply"}""",
            // maskId 重複
            """{"op":"apply","masks":[
                {"maskId":"d","maskType":"linear","parameters":{"x0":0.0,"y0":0.0,"x1":1.0,"y1":1.0}},
                {"maskId":"d","maskType":"linear","parameters":{"x0":0.0,"y0":0.0,"x1":1.0,"y1":1.0}}]}""",
            // 範囲外 adjustment
            """{"op":"apply","masks":[{"maskId":"o","maskType":"linear",
                "parameters":{"x0":0.0,"y0":0.0,"x1":1.0,"y1":1.0},
                "adjustments":{"develop.tone.contrast":2.0}}]}""",
        )
        backend.docs[docId] = backend.docs[docId]!!.copy(currentRevision = payloads.size.toLong())
        payloads.forEachIndexed { index, payload ->
            val revisionNo = index + 1L
            backend.revisions += EditRevisionEntity(
                documentId = docId,
                revisionNo = revisionNo,
                eventType = EditEventKind.LOCAL.id,
                payloadJson = payload,
                payloadSchemaVersion = 1,
                committedAt = revisionNo,
                checksum = EditChecksums.revisionChecksum(docId, revisionNo, EditEventKind.LOCAL.id, payload),
            )
        }
        val reloaded = assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            runBlocking { store.load(docId) },
        ).value
        assertEquals(0, reloaded.decodeSummary.applied)
        assertEquals(payloads.size, reloaded.decodeSummary.preservedOnly)
        // 既定値へ落とさない（マスク無しのまま）・履歴には現れる（表示は保持）。
        assertTrue(reloaded.recipe.masks.isEmpty())
        assertEquals(payloads.size, reloaded.history.size)
        assertTrue(reloaded.history.all { it.masks == null })
        // 保持行の後でも継続確定は壊れない。
        val next = commitMaskList(store, reloaded, listOf(mask(id = "after")))
        assertEquals(1, next.recipe.masks.size)
        assertEquals("after", next.recipe.masks.single().maskId)
    }

    @Test
    fun maskPayloadWithoutOptionalFields_isReadableWithDefaults() {
        val backend = FakeBackend()
        val store = store(backend)
        val base = bootstrap(store)
        val docId = base.documentId
        // version / adjustments / brushStrokes を欠く payload（追加フィールド前の書込み形式）。
        val payload = """{"op":"apply","masks":[{"maskId":"m1","maskType":"linear",
            "parameters":{"y1":1.0,"x0":0.0,"y0":0.0,"x1":1.0}}]}"""
        backend.docs[docId] = backend.docs[docId]!!.copy(currentRevision = 1L)
        backend.revisions += EditRevisionEntity(
            documentId = docId,
            revisionNo = 1L,
            eventType = EditEventKind.LOCAL.id,
            payloadJson = payload,
            payloadSchemaVersion = 1,
            committedAt = 1L,
            checksum = EditChecksums.revisionChecksum(docId, 1L, EditEventKind.LOCAL.id, payload),
        )
        val reloaded = assertIs<ImageOpResult.Success<EditStore.EditStoreState>>(
            runBlocking { store.load(docId) },
        ).value
        assertEquals(1, reloaded.decodeSummary.applied)
        val node = reloaded.recipe.masks.single()
        assertEquals(1, node.version)
        assertTrue(node.adjustments.isEmpty())
        assertTrue(node.brushStrokes.isEmpty())
        // feather は既定補完（linear=1.0）・キー順は正準。
        assertEquals(1.0, node.parameters.getValue("feather"), 0.0)
        assertEquals(listOf("x0", "y0", "x1", "y1", "feather"), node.parameters.keys.toList())
    }

    private companion object {
        const val RecipeSchemaVersion1 = 1
    }
}
