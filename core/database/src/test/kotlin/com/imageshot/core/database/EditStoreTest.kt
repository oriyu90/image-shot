package com.imageshot.core.database

import com.imageshot.core.model.AdjustmentId
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.RecipeParameterEdit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
        backend.revisions += EditRevisionEntity(
            documentId = docId,
            revisionNo = 1L,
            eventType = EditStore.EVENT_TYPE_PARAM,
            payloadJson = """{"path":"develop.tone.exposureEv","value":0.2,"op":"apply"}""",
            payloadSchemaVersion = EVENT_PAYLOAD_SCHEMA_VERSION,
            committedAt = 1L,
            checksum = EditChecksums.revisionChecksum(docId, 1L, EditStore.EVENT_TYPE_PARAM, """{"path":"develop.tone.exposureEv","value":0.2,"op":"apply"}"""),
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
                color = EditRecipe.ColorParams(vibrance = -1.0, saturation = 1.0),
            ),
        )
        val json = EditRecordCodec.encodeRecipe(recipe)
        // 旧/未知スキーマバージョンは拒否（§3.2: 旧版読み出し+移行テストの前置）。
        val rejected = runCatching { EditRecordCodec.decodeRecipe(json, 99) }
        assertTrue(rejected.isFailure)
        val decoded = EditRecordCodec.decodeRecipe(json, RecipeSchemaVersion1)
        assertEquals(recipe.develop, decoded.develop)
    }

    private companion object {
        const val RecipeSchemaVersion1 = 1
    }
}
