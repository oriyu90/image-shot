package com.imageshot.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.imageshot.core.model.AdjustmentId
import com.imageshot.core.model.EditEventKind
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.RecipeParameterEdit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * EditStore の実 Room（framework 既定 SQLite driver・ADR-021）上の device 統合テスト。
 *
 * JVM 単体テスト（EditStoreTest・FakeBackend）の最重要契約を、実ドライバ/トランザクション/
 * 実体FK・チェックサム経路込みの環境へ移植する:
 * - 混合イベント種（スライダー/curve/geometry）の往復（[EditDecodeSummary] 込み）。
 * - 未知 eventType / RESERVED 種 → PRESERVED_ONLY（値を保持し・適用せず・行を書換しない）。
 * - 未対応 payloadSchemaVersion → unsupportedVersion（適用も既定値化もしない・行は無改写）。
 * - スナップショット周期とスナップショット基点再生（実 Room トランザクション24回）。
 * - チェックサム不一致行の再生スキップ（実ドライバでの破損注入）。
 * - DAO 単位の CRUD happy path（asset / edit document / export job）。
 *
 * DB は in-memory（@After で close）。ユーザーデータには触らない。
 */
class EditStoreInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: ImageShotDatabase
    private lateinit var store: EditStore

    private var clockTicks = 1_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, ImageShotDatabase::class.java)
            .allowMainThreadQueries() // 破損注入（直接 SQL）と Flow 未使用の単純化のため明示許可。
            .build()
        store = EditStore(RoomEditStoreBackend(db), now = { clockTicks++ })
        runBlocking { seedAsset("asset-1") }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------ mixed kinds 往復

    @Test
    fun mixedEventKinds_roundTripThroughRealRoomDriver() = runBlocking {
        var state = ensureBase()
        state = commit(state, AdjustmentId.EXPOSURE, 1.5)
        state = commit(state, AdjustmentId.SATURATION, 40.0)
        // 温度は往復可逆な領域の値（-40 は K 端でクランプされ非可逆のため -20 を使用）。
        state = commit(state, AdjustmentId.TEMPERATURE, -20.0)
        state = commitCurve(state, EditRecipe.Curve(rgb = listOf(listOf(0.8, 1.0), listOf(1.0, 0.6), listOf(0.2, 0.0))))
        state = commitGeometry(
            state,
            EditRecipe.GeometryParams(cropNorm = listOf(0.1, 0.1, 0.9, 1.0), angleDeg = 90.0, mirrorX = true),
        )

        val loaded = store.load(state.documentId).requireSuccess("load")
        assertEquals(5L, loaded.currentRevision)
        assertEquals(1.5, loaded.recipe.develop.tone.exposureEv, 1e-9)
        assertEquals(0.4, loaded.recipe.develop.color.saturation, 1e-9)
        // 温度は往復可逆な領域の値をスライダー値（絶対K）経由の正本写像で往復照合する。
        assertEquals(-20.0, AdjustmentId.TEMPERATURE.sliderValueOf(loaded.recipe), 1e-6)
        assertEquals(
            listOf(listOf(0.2, 0.0), listOf(0.8, 1.0), listOf(1.0, 0.6)),
            loaded.recipe.develop.curve.rgb,
        )
        assertEquals(90.0, loaded.recipe.geometry.angleDeg, 1e-9)
        assertTrue(loaded.recipe.geometry.mirrorX)
        assertEquals(listOf(0.1, 0.1, 0.9, 1.0), loaded.recipe.geometry.cropNorm)

        val summary = loaded.decodeSummary
        assertEquals(5, summary.totalRevisions)
        assertEquals(5, summary.applied)
        assertEquals(0, summary.preservedOnly)
        assertEquals(0, summary.unsupportedVersion)
        assertEquals(0, summary.invalid)
        assertTrue(summary.isFullyApplied)
        assertEquals(5, loaded.history.size)
        assertTrue(loaded.canUndo)
        assertFalse(loaded.canRedo)
    }

    // ------------------------------------------------------------------ PRESERVED_ONLY（未知/RESERVED）

    @Test
    fun unknownAndReservedKinds_preservedOnly_rowsUntouched() = runBlocking {
        val base = ensureBase()
        val docId = base.documentId
        val mysteryPayload = """{"path":"develop.tone.exposureEv","value":2.0,"op":"apply"}"""
        val reservedPayload = """{"points":[[0.0,0.0],[1.0,1.0]]}"""
        appendForeignRevision(docId, revisionNo = 1L, eventType = "mystery", payloadJson = mysteryPayload)
        appendForeignRevision(docId, revisionNo = 2L, eventType = EditEventKind.DETAIL.id, payloadJson = reservedPayload)
        // 疑似シナリオ内で文書カーソルも整合させる（本件の対象は保持挙動）。
        db.editDocumentDao().commitRevision(docId, 2L, clockTicks++, "scenario")

        val loaded = store.load(docId).requireSuccess("load")
        // PRESERVED_ONLY: 値保持・適用なし・既定値化なし。
        assertEquals(2, loaded.decodeSummary.preservedOnly)
        assertEquals(0, loaded.decodeSummary.applied)
        assertEquals(0.0, loaded.recipe.develop.tone.exposureEv, 1e-9)
        assertFalse(loaded.decodeSummary.isFullyApplied)
        assertEquals(2, loaded.decodeSummary.totalRevisions)

        // 行は無改写（payload/バージョン/チェックサムは注入時のまま）。
        val rows = db.editRevisionDao().all(docId)
        assertEquals(2, rows.size)
        assertEquals("mystery", rows[0].eventType)
        assertEquals(mysteryPayload, rows[0].payloadJson)
        assertEquals(1, rows[0].payloadSchemaVersion)
        assertEquals(EditEventKind.DETAIL.id, rows[1].eventType)
        assertEquals(reservedPayload, rows[1].payloadJson)

        // 保持後も連続確定は破たんしない（revisionNo 継続・既知イベントは適用）。
        val next = commit(loaded, AdjustmentId.EXPOSURE, -1.0)
        assertEquals(3L, next.currentRevision)
        assertEquals(-1.0, next.recipe.develop.tone.exposureEv, 1e-9)
        assertEquals(2, next.decodeSummary.preservedOnly)
        assertEquals(1, next.decodeSummary.applied)
    }

    // ------------------------------------------------------------------ 未対応 payloadSchemaVersion

    @Test
    fun higherPayloadSchemaVersion_notAppliedAndCounted_rowUnmodified() = runBlocking {
        val base = ensureBase()
        val docId = base.documentId
        val payload = """{"path":"develop.tone.exposureEv","value":3.0,"op":"apply"}"""
        appendForeignRevision(
            docId,
            revisionNo = 1L,
            eventType = EditEventKind.TONE.id,
            payloadJson = payload,
            payloadSchemaVersion = 2, // 未来バージョン: 解釈せず保持のみ。
        )
        db.editDocumentDao().commitRevision(docId, 1L, clockTicks++, "scenario")

        val loaded = store.load(docId).requireSuccess("load")
        // 3.0 を適用して既定値に落とさない（unsupportedVersion で明示）。
        assertEquals(0.0, loaded.recipe.develop.tone.exposureEv, 1e-9)
        assertEquals(1, loaded.decodeSummary.unsupportedVersion)
        assertEquals(0, loaded.decodeSummary.applied)
        assertFalse(loaded.decodeSummary.isFullyApplied)

        // 行は無改写（schemaVersion も payload も元のまま保存される）。
        val row = db.editRevisionDao().all(docId).single()
        assertEquals(2, row.payloadSchemaVersion)
        assertEquals(payload, row.payloadJson)
    }

    // ------------------------------------------------------------------ スナップショット（実 Room トランザクション）

    @Test
    fun snapshotCadence_createsSnapshot_replayStartsFromIt() = runBlocking {
        var state = ensureBase()
        repeat(EditStore.SNAPSHOT_EVERY) { index ->
            state = commit(state, AdjustmentId.EXPOSURE, if (index % 2 == 0) 0.1 else -0.1)
        }
        val snapshot = db.editSnapshotDao().latest(state.documentId)
        requireNotNull(snapshot) { "24イベント後のスナップショット行が存在しない" }
        assertEquals(EditStore.SNAPSHOT_EVERY.toLong(), snapshot.revisionNo)
        assertEquals(RECIPE_SCHEMA_VERSION, snapshot.recipeSchemaVersion)
        assertTrue(snapshot.checksum.isNotEmpty())

        val reloaded = store.load(state.documentId).requireSuccess("load")
        assertEquals(EditStore.SNAPSHOT_EVERY.toLong(), reloaded.snapshotRevisionNo)
        assertEquals(state.recipe.develop.tone.exposureEv, reloaded.recipe.develop.tone.exposureEv, 1e-9)
        // スナップショットに効果が取り込まれているため適用側に数えない（history 表示のみ）。
        assertEquals(0, reloaded.decodeSummary.applied)
        assertEquals(EditStore.SNAPSHOT_EVERY, reloaded.decodeSummary.totalRevisions)
        assertTrue(reloaded.decodeSummary.isFullyApplied)
    }

    // ------------------------------------------------------------------ チェックサムゲート（破損注入）

    @Test
    fun checksumMismatchRevision_skippedAtReplay() = runBlocking {
        var state = ensureBase()
        state = commit(state, AdjustmentId.EXPOSURE, 2.0)
        state = commit(state, AdjustmentId.SATURATION, 80.0)
        val docId = state.documentId

        // 実ドライバでの破損注入（append-only DAO 契約を壊さない: 直接 SQL による検証用の書換のみ）。
        db.openHelper.writableDatabase.execSQL(
            "UPDATE edit_revisions SET payloadJson = ? WHERE documentId = ? AND revisionNo = ?",
            arrayOf("""{"path":"develop.color.saturation","value":-999.0,"op":"apply"}""", docId, 2L),
        )

        val reloaded = store.load(docId).requireSuccess("load")
        // チェックサム不一致イベントは再生から除外（§3.3）。
        assertEquals(2.0, reloaded.recipe.develop.tone.exposureEv, 1e-9)
        assertEquals(0.0, reloaded.recipe.develop.color.saturation, 1e-9)
        assertEquals(1, reloaded.decodeSummary.applied)
        assertEquals(1, reloaded.decodeSummary.invalid)
        assertEquals(1, reloaded.history.size)
    }

    // ------------------------------------------------------------------ DAO CRUD happy path

    @Test
    fun assetDao_roundTripHappPath() = runBlocking {
        val dao = db.assetDao()
        dao.upsert(
            AssetEntity(
                id = "asset-crud",
                sourceUri = "file:///crud.jpg",
                displayName = "crud.jpg",
                mimeGuess = "image/jpeg",
                sniffedFormat = "JPEG",
                width = 64,
                height = 48,
                orientation = 1,
                dateTaken = null,
                rawCameraId = null,
                colorProfileHash = null,
                imageFingerprint = null,
                availability = "AVAILABLE",
                importedAt = clockTicks++,
            ),
        )
        val fetched = requireNotNull(dao.get("asset-crud")) { "upsert 直後の get が null" }
        assertEquals("crud.jpg", fetched.displayName)
        // URI失効判定はデータ更新のみ（原本参照/削除しない契約の CRUD 面）。
        dao.updateAvailability("asset-crud", "REVOKED")
        assertEquals("REVOKED", dao.get("asset-crud")!!.availability)
        assertEquals("asset-crud", dao.getBySourceUri("file:///crud.jpg")!!.id)
        assertTrue(dao.findPendingFingerprint().any { it.id == "asset-crud" })
        dao.updateFingerprint("asset-crud", "fp-crud")
        assertFalse(dao.findPendingFingerprint().any { it.id == "asset-crud" })
        assertTrue(dao.getAllOnce().any { it.id == "asset-crud" })
    }

    @Test
    fun editDocumentDao_insertAndCursorHappPath() = runBlocking {
        seedAsset("asset-doc")
        val docId = "doc-crud"
        val inserted = db.editDocumentDao().insert(
            EditDocumentEntity(
                id = docId,
                assetId = "asset-doc",
                schemaVersion = 1,
                currentRevision = 0L,
                sourceFingerprint = "fp-doc",
                lastCommittedAt = 1L,
                checksum = "",
            ),
        )
        assertTrue("IGNORE 戦略で -1（既存文書と解釈）になった", inserted != -1L)
        assertEquals(docId, db.editDocumentDao().getByAssetId("asset-doc")!!.id)
        val doc = requireNotNull(db.editDocumentDao().get(docId)) { "insert 直後の get が null" }
        assertEquals(0L, doc.currentRevision)
        // 文書カーソル更新（イベント追記と同一トランザクションで呼ぶ契約の実測点）。
        db.editDocumentDao().commitRevision(docId, 3L, 42L, "ck-3")
        val updated = db.editDocumentDao().get(docId)!!
        assertEquals(3L, updated.currentRevision)
        assertEquals("ck-3", updated.checksum)
        assertEquals(42L, updated.lastCommittedAt)
    }

    @Test
    fun exportJobDao_enqueueAndTransitionHappyPath() = runBlocking {
        val dao = db.exportJobDao()
        assertEquals(
            1L,
            dao.enqueue(
                ExportJobEntity(
                    id = "job-crud",
                    documentId = "doc-crud",
                    inputFingerprint = "fp",
                    recipeRevision = 1L,
                    settingsHash = "hash",
                    outputUri = "file:///out.jpg",
                    state = "QUEUED",
                    progress = 0,
                    attempt = 0,
                    errorCode = null,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            ),
        )
        dao.transition("job-crud", "RENDERING", 30, null, 0, 2L)
        assertTrue(dao.findIncompleteRecovering().any { it.id == "job-crud" })
        dao.transition("job-crud", "FAILED_RETRYABLE", 30, "unsupported_codec", 1, 3L)
        assertFalse(dao.findIncompleteRecovering().any { it.id == "job-crud" })
    }

    // ------------------------------------------------------------------ helpers

    /** Asset 種（FK 親）の播種（EditDocumentEntity の FK が実ドライバで ON のため）。 */
    private suspend fun seedAsset(id: String) {
        db.assetDao().upsert(
            AssetEntity(
                id = id,
                sourceUri = "file://$id.jpg",
                displayName = "$id.jpg",
                mimeGuess = "image/jpeg",
                sniffedFormat = "JPEG",
                width = 64,
                height = 48,
                orientation = 1,
                dateTaken = null,
                rawCameraId = null,
                colorProfileHash = null,
                imageFingerprint = null,
                availability = "AVAILABLE",
                importedAt = clockTicks++,
            ),
        )
    }

    private suspend fun ensureBase(): EditStore.EditStoreState =
        store.ensureDocument("asset-1", "fp-1").requireSuccess("ensureDocument")

    private suspend fun commit(
        state: EditStore.EditStoreState,
        id: AdjustmentId,
        value: Double,
    ): EditStore.EditStoreState =
        store.commit(state.documentId, state.currentRevision, RecipeParameterEdit(id.recipePath, value))
            .requireSuccess("commit ${id.recipePath}")

    private suspend fun commitCurve(
        state: EditStore.EditStoreState,
        curve: EditRecipe.Curve,
    ): EditStore.EditStoreState =
        store.commitCurve(state.documentId, state.currentRevision, curve).requireSuccess("commitCurve")

    private suspend fun commitGeometry(
        state: EditStore.EditStoreState,
        geometry: EditRecipe.GeometryParams,
    ): EditStore.EditStoreState =
        store.commitGeometry(state.documentId, state.currentRevision, geometry).requireSuccess("commitGeometry")

    /**
     * 正本 API を経由しない外部由来の revision 行の注入（未知種別/RESERVED/未来版の再現に使用）。
     * チェックサムは正本規約で正しく計算する（「読取れる正しい行」を再生側がどう振る舞うかの検査）。
     */
    private suspend fun appendForeignRevision(
        documentId: String,
        revisionNo: Long,
        eventType: String,
        payloadJson: String,
        payloadSchemaVersion: Int = EVENT_PAYLOAD_SCHEMA_VERSION,
    ) {
        db.editRevisionDao().append(
            EditRevisionEntity(
                documentId = documentId,
                revisionNo = revisionNo,
                eventType = eventType,
                payloadJson = payloadJson,
                payloadSchemaVersion = payloadSchemaVersion,
                committedAt = clockTicks++,
                checksum = EditChecksums.revisionChecksum(documentId, revisionNo, eventType, payloadJson),
            ),
        )
    }

    /** ImageOpResult の成否縮小（失敗を無通知にしない・メッセージに出す）。 */
    private fun <T> ImageOpResult<T>.requireSuccess(label: String): T = when (this) {
        is ImageOpResult.Success<T> -> value
        is ImageOpResult.Degraded<T> -> throw AssertionError("$label degraded: code=$code warnings=$warnings")
        is ImageOpResult.Failure -> throw AssertionError("$label failed: code=$code detail=$detail")
    }
}
