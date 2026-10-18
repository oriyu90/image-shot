package com.imageshot.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imageshot.core.database.AssetDao
import com.imageshot.core.database.toModel
import com.imageshot.core.model.Asset
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.RenderRequest
import com.imageshot.core.model.RenderResult
import com.imageshot.core.render.PhotoRenderer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * 編集ViewModel（§10 画面状態管理の最小骨格）。
 * - ドラッグ中の値は StateFlow の一時プレビューのみ更新（palette preview でも DB 書込みなし）。
 * - onDragEndで1件の正規化イベントを確定（§3.3）。DBへの追記は editStore に集約（TODO: 次 Sprint）。
 */
class EditorViewModel(
    private val assetId: String,
    private val assetDao: AssetDao,
    private val renderer: PhotoRenderer,
) : ViewModel() {

    data class SliderValues(
        val exposureEv: Double = 0.0,
        val contrast: Double = 0.0,
        val saturation: Double = 0.0,
    )

    data class EditorState(
        val assetId: String,
        val asset: Asset?,
        val sliders: SliderValues = SliderValues(),
        val historyCount: Int = 0,
        val renderWarnings: List<String> = emptyList(),
        val renderFailure: String? = null,
        /** ドラッグ中の品質。ドラッグ中は低解像度、静定時に高画質（§4.3）。 */
        val dragging: Boolean = false,
    )

    private val sliders = MutableStateFlow(SliderValues())
    private val dragging = MutableStateFlow(false)
    private val renderState = MutableStateFlow<RenderOutcome>(RenderOutcome.Idle)
    private var revision = 1L
    private var generation = 0L
    private var renderJob: Job? = null

    /** 排他生成（ADR-011: 最新 generationId のみ有効）。 */
    private val renderMutex = Semaphore(1)

    /** 履歴確定数の正本。DB反映は editStore 導入時に置き換え。 */
    private val committedCount = MutableStateFlow(0)

    private data class RenderOutcome(val result: RenderResult?, val failure: String?) {
        companion object {
            val Idle = RenderOutcome(null, null)
        }
    }

    fun state(): StateFlow<EditorState> = combine(
        assetDao.observeAll(),
        sliders.asStateFlow(),
        dragging.asStateFlow(),
        combine(renderState, committedCount) { r, c -> r to c },
    ) { assets, sliderVals, isDragging, (outcome, count) ->
        EditorState(
            assetId = assetId,
            asset = assets.firstOrNull { it.id == assetId }?.toModel(),
            sliders = sliderVals,
            historyCount = count,
            renderWarnings = outcome.result?.warnings ?: emptyList(),
            renderFailure = outcome.failure,
            dragging = isDragging,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000), // 5秒保持
        initialValue = EditorState(assetId = assetId, asset = null),
    )

    /** ドラッグ中の軽量更新。DB・履歴には触れない（§3.3）。 */
    fun onSliderPreview(values: SliderValues) {
        dragging.value = true
        sliderState = values
        sliders.value = values
    }

    /**
     * ドラッグ終了: レシピを正規化し、1件の履歴確定として表示を積む。
     * TODO(ADR確認): Room への event append + currentRevision UPDATE のトランザクション実装は
     *  次のSprint C で editStore（§3.3）として導入。この雛形ではメモリ上の履歴のみ更新。
     */
    fun onSliderCommitted(values: SliderValues) {
        dragging.value = false
        sliders.value = values
        revision += 1
        committedCount.value += 1
        schedulePreviewRender(values)
    }

    private var sliderState = SliderValues()

    /** プレビュー描画（低品質ドラッグ中パス含む）。UI平滑性のため最後に要求された世代のみ適用。 */
    fun schedulePreviewRender(values: SliderValues = sliders.value) = run {
        generation += 1
        val request = RenderRequest(
            documentId = assetId,
            revision = revision,
            viewRect = RenderRequest.Rect(0.0, 0.0, 1.0, 1.0),
            requestedPx = if (dragging.value) 600 else 1400, // §4.3の簡易階層
            targetSpace = "srgb-d65",
            quality = if (dragging.value) RenderRequest.Quality.DRAFT else RenderRequest.Quality.PREVIEW,
            generationId = generation,
        )
        val recipe = recipeFor(values)
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            renderMutex.acquire()  // 実行排他
            try {
                when (val r = renderer.render(request, recipe)) {
                    is ImageOpResult.Success ->
                        renderState.value = RenderOutcome(r.value, null)
                    is ImageOpResult.Failure ->
                        renderState.value = RenderOutcome(null, "${r.code}: ${r.detail.orEmpty()}")
                    is ImageOpResult.Degraded ->
                        renderState.value = RenderOutcome(r.value, null)
                }
            } finally {
                renderMutex.release()
            }
        }
    }

    /** 送っている履歴は確定イベントのみ。undo は履歴カーソルで（ADR-007、UI骨格は最小）。 */
    fun undoLast() {
        if (committedCount.value > 0) {
            committedCount.value -= 1
            revision -= 1
            sliders.value = SliderValues() // TODO(ADR確認): イベント再生型 Undo は editStore で
        }
    }

    private fun recipeFor(values: SliderValues): EditRecipe = EditRecipe(
        documentId = assetId,
        source = EditRecipe.RecipeSource(
            assetId = assetId,
            fingerprint = assetDaoFingerprint(),
        ),
        develop = EditRecipe.DevelopParams(
            tone = EditRecipe.Tone(
                exposureEv = values.exposureEv,
                contrast = values.contrast,
            ),
            color = EditRecipe.ColorParams(saturation = values.saturation),
        ),
    )

    private fun assetDaoFingerprint(): String = "pending-scan" // TODO(ADR確認): AssetDaoから取得

    // renderer.close() はプロセス終了時に AppContainer 側で明示的に呼ぶ（ADR-002 の所有権契約）。
    // viewModelScope は onCleared で既に取消済みのため、ここでは close を発火しない。
}
