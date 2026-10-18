package com.imageshot.feature.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imageshot.core.database.AssetDao
import com.imageshot.core.database.EditStore
import com.imageshot.core.database.toModel
import com.imageshot.core.model.AdjustmentId
import com.imageshot.core.model.Asset
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.RecipeParameterEdit
import com.imageshot.core.model.RenderRequest
import com.imageshot.core.model.RenderResult
import com.imageshot.core.render.PhotoRenderer
import com.imageshot.core.render.RenderBitmapStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 編集 ViewModel（02_実装定義書.md §10 画面状態管理 / §3.3 保存規則）。
 *
 * 正本構造:
 * - 画面状態は単一 [EditorState] / 画像は [EditorImages]。DB 上の編集文書が正本。
 * - ドラッグ中の値は [EditorState.sliders] の一時プレビュー値のみを更新し、
 *   DB への書込みは一切行わない。onDragEnd（[onSliderCommit]）で 1 件の
 *   正規化イベントを [EditStore.commit] 経由で確定する（トランザクション完成だけが
 *   「保存済み」表示の根拠）。
 * - Undo/Redo は [EditStore.undo]/[redo]（既存イベントの削除ではなく新規イベント追加）。
 * - ドラッグ中は低解像度 DRAFT、指を離すと高解像度 PREVIEW リフレッシュ（§4.3 品質階層）。
 *   画質劣化解像度は保存に反映しない（保存はレシピのみ）。
 * - 長押し Before/After: [setBeforeVisible] で既定値レシピの別レンダリングを表示。
 *
 * WorkManager は不使用（export 担当の所有物）。エディタ内の描画は viewModelScope のみ。
 */
class EditorViewModel(
    private val assetId: String,
    private val assetDao: AssetDao,
    private val renderer: PhotoRenderer,
    private val bitmaps: RenderBitmapStore,
    private val editStore: EditStore,
) : ViewModel() {

    enum class SaveState { SAVED, SAVING, FAILED }

    /** スライダー単一スキーマ（AdjustmentId）を UI に投影したバインド。 */
    data class SliderBind(val id: AdjustmentId, val value: Double)

    data class HistoryRow(val revisionNo: Long, val label: String, val committedAt: Long)

    data class EditorState(
        val assetId: String,
        val asset: Asset?,
        /** ドラッグ中は当該スライダーのみ一時値が上書きされる（DB には影響しない）。 */
        val sliders: List<SliderBind> = AdjustmentId.entries.map { SliderBind(it, it.default) },
        val history: List<HistoryRow> = emptyList(),
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val saveState: SaveState = SaveState.SAVING,
        val saveFailure: String? = null,
        val renderWarnings: List<String> = emptyList(),
        val renderFailure: String? = null,
        val dragging: Boolean = false,
        val showBefore: Boolean = false,
        /** 確定済み改訂番号。履歴 UI / 描画の正本参照。 */
        val revision: Long = 0,
    )

    /** プレビュー/Before の両画像。UI 側は showBefore で選択する。 */
    data class EditorImages(val preview: ImageBitmap? = null, val before: ImageBitmap? = null)

    private data class RenderOutcome(val image: ImageBitmap?, val warnings: List<String>, val failure: String?) {
        companion object {
            val Idle = RenderOutcome(null, emptyList(), null)
        }
    }

    // ----- 状態ストリーム（UI へは state()/images() のみ公開）-----

    private val assetFlow = MutableStateFlow<Asset?>(null)
    private val recipe = MutableStateFlow<EditRecipe?>(null)
    private val transient = MutableStateFlow<Map<AdjustmentId, Double>>(emptyMap())
    private val history = MutableStateFlow<List<HistoryRow>>(emptyList())
    private val canUndo = MutableStateFlow(false)
    private val canRedo = MutableStateFlow(false)
    private val saveState = MutableStateFlow(SaveState.SAVING)
    private val saveFailure = MutableStateFlow<String?>(null)
    private val dragging = MutableStateFlow(false)
    private val showBefore = MutableStateFlow(false)
    private val render = MutableStateFlow(RenderOutcome.Idle)
    private val before = MutableStateFlow(RenderOutcome.Idle)
    private val revision = MutableStateFlow(0L)

    private var documentId: String? = null
    private var committedRevision = 0L
    private var previewGeneration = 0L
    private var beforeGeneration = 0L
    private var previewRenderJob: Job? = null
    private var beforeRenderJob: Job? = null
    private var lastDraftAt = 0L

    init {
        viewModelScope.launch {
            /* 起動時: AssetDao → EditStore（§10: UI スレッドに decode/hash を置かない）。 */
            val asset = assetDao.get(assetId)?.toModel()
            assetFlow.value = asset
            when (val result = editStore.ensureDocument(assetId, asset?.imageFingerprint ?: "unscanned")) {
                is ImageOpResult.Success -> {
                    documentId = result.value.documentId
                    applyStoreState(result.value)
                    saveState.value = SaveState.SAVED
                    // 読み出したレシピでプレビュー再現（App再起動後も同一レシピ）。
                    scheduleRender(RenderTarget.PREVIEW, RenderRequest.Quality.PREVIEW, result.value.recipe)
                    // Before/After 用の既定値レシピはドラフトで1回のみ生成。
                    scheduleRender(RenderTarget.BEFORE, RenderRequest.Quality.DRAFT, defaultRecipe(result.value.documentId))
                }
                is ImageOpResult.Failure -> {
                    saveState.value = SaveState.FAILED
                    saveFailure.value = "${result.code}: ${result.detail.orEmpty()}"
                    render.value = RenderOutcome(null, emptyList(), "編集文書の読み込みに失敗")
                }
                else -> Unit
            }
        }
    }

    // ----- 公開API -----

    fun state(): StateFlow<EditorState> = combine(
        assetFlow.asStateFlow(),
        sliderBinds(),
        history.asStateFlow(),
        combine(render.asStateFlow(), before.asStateFlow()) { r, b -> r.warnings to b.image },
        combine(canUndo, canRedo, dragging, showBefore, combine(saveState, saveFailure) { s, f -> s to f }) {
                undo, redo, isDragging, beforeOn, saves ->
            SessionUi(undo, redo, isDragging, beforeOn, saves.first, saves.second)
        },
    ) { asset, binds, rows, (warnings, _), session ->
        EditorState(
            assetId = assetId,
            asset = asset,
            sliders = binds,
            history = rows,
            canUndo = session.canUndo,
            canRedo = session.canRedo,
            saveState = session.saveState,
            saveFailure = session.saveFailure,
            renderWarnings = warnings,
            renderFailure = render.value.failure,
            dragging = session.dragging,
            showBefore = session.showBefore,
            revision = revision.value,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        // 初期値はスライダ基定値（DB からの復元は init  succeeded 後に反映される）。
        initialValue = EditorState(assetId = assetId, asset = null),
    )

    fun images(): StateFlow<EditorImages> = combine(
        render.asStateFlow(), before.asStateFlow(),
    ) { r, b -> EditorImages(preview = r.image, before = b.image) }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = EditorImages(),
    )

    /** ドラッグ中: 一時プレビュー値の更新 + DRAFT 描画（デバウンス相当の間引き、§3.3）。 */
    fun onSliderPreview(id: AdjustmentId, value: Double) {
        dragging.value = true
        val clamped = id.validClamped(value) ?: return
        transient.value = transient.value + (id to clamped)
        val now = System.currentTimeMillis()
        if (now - lastDraftAt >= DRAFT_INTERVAL_MS) {
            lastDraftAt = now
            scheduleRender(RenderTarget.PREVIEW, RenderRequest.Quality.DRAFT, draftRecipe())
        }
    }

    /** onDragEnd: 1件の正規化イベントを確定し、高解像度リフレッシュ（§3.3/§4.3）。 */
    fun onSliderCommit(id: AdjustmentId, value: Double) {
        dragging.value = false
        val clamped = id.validClamped(value) ?: return
        viewModelScope.launch {
            saveState.value = SaveState.SAVING
            saveFailure.value = null
            val docId = documentId
            if (docId == null || recipe.value == null) {
                saveState.value = SaveState.FAILED
                saveFailure.value = "編集文書が未ロード"
                return@launch
            }
            when (val result = editStore.commit(docId, committedRevision, RecipeParameterEdit(id.recipePath, clamped))) {
                is ImageOpResult.Success -> {
                    applyStoreState(result.value)
                    saveState.value = SaveState.SAVED
                    transient.value = emptyMap()
                    scheduleRender(RenderTarget.PREVIEW, RenderRequest.Quality.PREVIEW, result.value.recipe)
                }
                is ImageOpResult.Degraded -> {
                    applyStoreState(result.value)
                    saveState.value = SaveState.SAVED
                    saveFailure.value = "degraded: ${result.code}"
                    transient.value = emptyMap()
                }
                is ImageOpResult.Failure -> {
                    // 失効（stale baseRevision）等: 無通知続行せず失敗を明示し、正本へ再同期（NFR-06）。
                    saveState.value = SaveState.FAILED
                    saveFailure.value = "${result.code}: ${result.detail.orEmpty()}"
                    resyncAfterFailure()
                }
            }
        }
    }

    fun undo() {
        if (dragging.value) return
        actOnStore { editStore.undo(it) }
    }

    fun redo() {
        if (dragging.value) return
        actOnStore { editStore.redo(it) }
    }

    /** 長押し Before/After。true の間、既定値レシピの描画を表示に切替える（§4.3）。 */
    fun setBeforeVisible(active: Boolean) {
        showBefore.value = active
    }

    // ----- 内部実装 -----

    private data class SessionUi(
        val canUndo: Boolean,
        val canRedo: Boolean,
        val dragging: Boolean,
        val showBefore: Boolean,
        val saveState: SaveState,
        val saveFailure: String?,
    )

    private fun sliderBinds() = combine(recipe.asStateFlow(), transient.asStateFlow()) { base, overrides ->
        val effective = base ?: defaultRecipe(null)
        AdjustmentId.entries.map { id ->
            SliderBind(id, overrides[id] ?: id.sliderValueOf(effective))
        }
    }

    private fun actOnStore(
        action: suspend (String) -> ImageOpResult<EditStore.UndoOutcome>,
    ) {
        val docId = documentId ?: return
        viewModelScope.launch {
            saveState.value = SaveState.SAVING
            saveFailure.value = null
            when (val result = action(docId)) {
                is ImageOpResult.Success -> {
                    val outcome = result.value
                    when (outcome) {
                        is EditStore.UndoOutcome.Applied -> {
                            applyStoreState(outcome.state)
                            saveState.value = SaveState.SAVED
                            transient.value = emptyMap()
                            scheduleRender(
                                RenderTarget.PREVIEW,
                                RenderRequest.Quality.PREVIEW,
                                outcome.state.recipe,
                            )
                        }
                        EditStore.UndoOutcome.Noop -> saveState.value = SaveState.SAVED
                    }
                }
                is ImageOpResult.Degraded -> {
                    // 経路は未使用（EditStore は Success/Failure のみ返す）。
                    saveState.value = SaveState.FAILED
                    saveFailure.value = "degraded: ${result.code}"
                }
                is ImageOpResult.Failure -> {
                    saveState.value = SaveState.FAILED
                    saveFailure.value = "${result.code}: ${result.detail.orEmpty()}"
                    resyncAfterFailure()
                }
            }
        }
    }

    private fun applyStoreState(state: EditStore.EditStoreState) {
        documentId = state.documentId
        committedRevision = state.currentRevision
        revision.value = state.currentRevision
        recipe.value = state.recipe
        history.value = state.history.map { item -> historyRow(item) }
        canUndo.value = state.canUndo
        canRedo.value = state.canRedo
    }

    /** 確定失敗後の正本再同期（stale 楽観排他競合時）。イベント欠落は作らない。 */
    private fun resyncAfterFailure() {
        val docId = documentId ?: return
        viewModelScope.launch {
            when (val r = editStore.load(docId)) {
                is ImageOpResult.Success -> {
                    applyStoreState(r.value)
                    saveState.value = SaveState.SAVED
                    transient.value = emptyMap()
                    scheduleRender(RenderTarget.PREVIEW, RenderRequest.Quality.PREVIEW, r.value.recipe)
                }
                else -> Unit
            }
        }
    }

    /** ドラッグ中の一時値を仮レシピへ合成（DB には触れない）。 */
    private fun draftRecipe(): EditRecipe {
        val base = recipe.value ?: defaultRecipe(null)
        var out = base
        transient.value.forEach { (id, v) -> out = id.apply(out, v) }
        return out
    }

    private fun defaultRecipe(documentId: String?): EditRecipe = EditRecipe(
        documentId = documentId ?: "pending-doc",
        source = EditRecipe.RecipeSource(assetId = assetId, fingerprint = "unscanned"),
    )

    private fun historyRow(item: EditStore.EditStoreHistoryItem): HistoryRow {
        val id = item.path?.let { AdjustmentId.byPath(it) }
        val opLabel = when (item.op) {
            RecipeParameterEdit.OP_APPLY -> "変更"
            RecipeParameterEdit.OP_UNDO -> "取り消し"
            RecipeParameterEdit.OP_REDO -> "やり直し"
            else -> item.eventType
        }
        val valueText = when {
            id == null -> item.path ?: ""
            else -> {
                val v = item.value
                if (v != null) id.formatValue(v) + id.unit.display else ""
            }
        }
        return HistoryRow(
            revisionNo = item.revisionNo,
            label = "$opLabel: ${id?.label ?: item.path ?: item.eventType} $valueText".trim(),
            committedAt = item.committedAt,
        )
    }

    private enum class RenderTarget { PREVIEW, BEFORE }

    private fun scheduleRender(
        target: RenderTarget,
        quality: RenderRequest.Quality,
        targetRecipe: EditRecipe,
    ) {
        val docId = documentId
        if (docId == null || targetRecipe.documentId == "pending-doc") return
        val requestedPx = when (quality) {
            RenderRequest.Quality.DRAFT -> DRAFT_PX
            else -> PREVIEW_PX
        }
        val request = RenderRequest(
            documentId = docId,
            revision = committedRevision,
            viewRect = RenderRequest.Rect(0.0, 0.0, 1.0, 1.0),
            requestedPx = requestedPx,
            targetSpace = "srgb-d65",
            quality = quality,
            generationId = when (target) { RenderTarget.PREVIEW -> ++previewGeneration; RenderTarget.BEFORE -> ++beforeGeneration },
        )
        val targetFlow = when (target) {
            RenderTarget.PREVIEW -> render
            RenderTarget.BEFORE -> before
        }
        val expectedGen = request.generationId
        val job = viewModelScope.launch {
            when (val outcome = renderer.render(request, targetRecipe)) {
                is ImageOpResult.Success -> acceptRender(target, targetFlow, expectedGen, outcome.value, outcome.value.warnings)
                is ImageOpResult.Degraded -> acceptRender(target, targetFlow, expectedGen, outcome.value, outcome.value.warnings)
                is ImageOpResult.Failure -> {
                    if (!isCurrent(target, expectedGen)) return@launch
                    // ADR-014: 失敗は無通知にデフォルト画像へ代替しない。
                    targetFlow.value = RenderOutcome(null, emptyList(), "${outcome.code}: ${outcome.detail.orEmpty()}")
                }
            }
        }
        when (target) {
            RenderTarget.PREVIEW -> { previewRenderJob?.cancel(); previewRenderJob = job }
            RenderTarget.BEFORE -> { beforeRenderJob?.cancel(); beforeRenderJob = job }
        }
    }

    private fun acceptRender(
        target: RenderTarget,
        targetFlow: MutableStateFlow<RenderOutcome>,
        expectedGen: Long,
        result: RenderResult,
        warnings: List<String>,
    ) {
        if (!isCurrent(target, expectedGen)) return // 古い結果は捨てる（ADR-011）
        val image = bitmaps.get(result.bitmapHandle)?.asImageBitmap()
        targetFlow.value = RenderOutcome(image, warnings, null)
    }

    private fun isCurrent(target: RenderTarget, gen: Long): Boolean =
        when (target) {
            RenderTarget.PREVIEW -> previewGeneration == gen
            RenderTarget.BEFORE -> beforeGeneration == gen
        }

    // renderer.close() / bitmaps.clearAll() はプロセス終了時に AppContainer 側で明示的に呼ぶ
    // （所有権契約）。viewModelScope は onCleared で取消済みのため、ここで dispose しない。

    companion object {
        /** ドラッグ中 DRAFT 描画の間引き。確定イベントの永続化には関与しない（§3.3）。 */
        const val DRAFT_INTERVAL_MS = 90L
        const val DRAFT_PX = 600
        /** プレビュー最大1600px系の高速パス（§4.3）。 */
        const val PREVIEW_PX = 1400
    }
}
