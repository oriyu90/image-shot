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
import com.imageshot.core.model.EditEventKind
import com.imageshot.core.model.EditNodeSpecs
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.Strings
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.MaskSpecs
import com.imageshot.core.model.RecipeParameterEdit
import com.imageshot.core.model.RenderRequest
import com.imageshot.core.model.RenderResult
import com.imageshot.core.render.PhotoRenderer
import com.imageshot.core.render.RecipeGeometry
import com.imageshot.core.render.RenderBitmapStore
import com.imageshot.feature.editor.R as EditorR
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    /** 文言取得（PR-27 G1）。app 層が Context.getString を結線する。VM 内に日本語リテラルは置かない。 */
    private val strings: Strings,
) : ViewModel() {

    enum class SaveState { SAVED, SAVING, FAILED }

    /** スライダー単一スキーマ（AdjustmentId）を UI に投影したバインド。 */
    data class SliderBind(val id: AdjustmentId, val value: Double)

    data class HistoryRow(val revisionNo: Long, val label: String, val committedAt: Long)

    /**
     * カーブ編集の対象チャンネル（chips の正本・PR-28a）。
     * [eventKey] は EditStore.commitCurve の editedChannel / payload `ch`（master は省略=null）。
     */
    enum class CurveChannel(val eventKey: String?, val labelRes: Int) {
        MASTER(null, EditorR.string.editor_curve_channel_master),
        RED("r", EditorR.string.editor_curve_channel_r),
        GREEN("g", EditorR.string.editor_curve_channel_g),
        BLUE("b", EditorR.string.editor_curve_channel_b),
    }

    data class EditorState(
        val assetId: String,
        val asset: Asset?,
        /** ドラッグ中は当該スライダーのみ一時値が上書きされる（DB には影響しない）。 */
        val sliders: List<SliderBind> = AdjustmentId.entries.map { SliderBind(it, it.default) },
        /** カーブ（master+R/G/B チャンネル、正規化済み）。ドラッグ中は選択チャンネルのみ一時値が上書きされる。 */
        val curve: EditRecipe.Curve = EditRecipe.Curve(),
        /** 選択中のカーブチャンネル（chips・編集対象の正本。未選択=master）。 */
        val curveChannel: CurveChannel = CurveChannel.MASTER,
        /** ジオメトリ（回転/反転/クロップ）の確定済み絶対状態。 */
        val geometry: EditRecipe.GeometryParams = EditRecipe.GeometryParams(),
        /**
         * 局所マスク（PR-29c）。レシピから復元し、ドラッグ中は一時プレビュー値で上書きされる。
         * 順序はレシピと同じ（描画順＝適用順）。
         */
        val masks: List<EditRecipe.MaskNode> = emptyList(),
        /**
         * 選択中マスク（overlay のハンドル表示・パネル編集の対象）。
         * UI セッション状態で、履歴・DB には保存しない（undo で消えたマスクは非選択へ落ちる）。
         */
        val selectedMaskId: String? = null,
        /** brush の半径（ツール設定・正規化。マスク内容ではないため履歴を作らない）。 */
        val brushRadius: Double = MaskDraft.DEFAULT_BRUSH_RADIUS,
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
    /**
     * カーブのドラッグ中一時値（選択チャンネルのみ差し替えた全量・正規化済み）。確定でクリア。
     * master のみ / チャンネルのいずれの編集でもこの単一フローを使い、差し替え点だけが違う。
     */
    private val transientCurve = MutableStateFlow<EditRecipe.Curve?>(null)
    /**
     * マスクのドラッグ中一時値（全量・正規化済み）。確定/失敗でクリアする。
     * `null` = 一時値なし（レシピの masks を表示）。空リストは「全削除の一時状態」なので
     * `null` と区別する（空リストでレシピへフォールバックしない）。
     */
    private val transientMasks = MutableStateFlow<List<EditRecipe.MaskNode>?>(null)
    /** 選択中マスク（UI セッション状態・DB 保存なし）。 */
    private val selectedMaskId = MutableStateFlow<String?>(null)
    /** brush 半径（ツール設定・UI セッション状態）。 */
    private val brushRadius = MutableStateFlow(MaskDraft.DEFAULT_BRUSH_RADIUS)
    /**
     * brush の 1 ジェスチャ中に未確定のストローク（maskId ごと）。
     * ドラッグ中の見た目は [transientMasks] に畳み、確定時に 1 イベントへまとめる。
     */
    private var brushPendingMaskId: String? = null
    private var brushPending: List<EditRecipe.BrushStroke> = emptyList()
    /** 選択中のカーブチャンネル（chips・UI セッション状態。DB には保存しない）。 */
    private val selectedCurveChannel = MutableStateFlow(CurveChannel.MASTER)
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
                    render.value = RenderOutcome(null, emptyList(), strings.get(EditorR.string.editor_msg_document_load_failed))
                }
                else -> Unit
            }
        }
    }

    // ----- 公開API -----

    fun state(): StateFlow<EditorState> = combine(
        assetFlow.asStateFlow(),
        panelProjection(),
        history.asStateFlow(),
        combine(render.asStateFlow(), before.asStateFlow()) { r, b -> r.warnings to b.image },
        combine(canUndo, canRedo, dragging, showBefore, combine(saveState, saveFailure) { s, f -> s to f }) {
                undo, redo, isDragging, beforeOn, saves ->
            SessionUi(undo, redo, isDragging, beforeOn, saves.first, saves.second)
        },
    ) { asset, projection, rows, (warnings, _), session ->
        EditorState(
            assetId = assetId,
            asset = asset,
            sliders = projection.sliders,
            curve = projection.curve,
            curveChannel = projection.curveChannel,
            geometry = projection.geometry,
            masks = projection.masks,
            selectedMaskId = projection.selectedMaskId,
            brushRadius = projection.brushRadius,
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
        // 初期値はスライダ基定値（DB からの復元は init succeeded 後に反映される）。
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
                saveFailure.value = strings.get(EditorR.string.editor_msg_doc_not_loaded)
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

    /**
     * カーブ: ドラッグ中の一時更新（選択チャンネルのみを差し替えた全量で DRAFT 描画、DB には触れない）。
     * 正規化できない入力は無視する（UI 側に立たせる・既定値へ落とさない）。
     */
    fun onCurvePreview(points: List<List<Double>>) {
        dragging.value = true
        val normalized = EditNodeSpecs.normalizeCurve(points) ?: return
        transientCurve.value = curveOverrideForSelection(normalized)
        val now = System.currentTimeMillis()
        if (now - lastDraftAt >= DRAFT_INTERVAL_MS) {
            lastDraftAt = now
            scheduleRender(RenderTarget.PREVIEW, RenderRequest.Quality.DRAFT, draftRecipe())
        }
    }

    /** 選択チャンネルのみを差し替えたカーブ全量（一時値 / 確定値の共通部）。 */
    private fun curveOverrideForSelection(normalized: List<List<Double>>): EditRecipe.Curve {
        val base = recipe.value?.develop?.curve ?: EditRecipe.Curve()
        return when (selectedCurveChannel.value) {
            CurveChannel.MASTER -> base.copy(rgb = normalized)
            CurveChannel.RED -> base.copy(r = normalized)
            CurveChannel.GREEN -> base.copy(g = normalized)
            CurveChannel.BLUE -> base.copy(b = normalized)
        }
    }

    /**
     * カーブ: 指を離した時点で絶対状態イベントを確定（EditStore.commitCurve・カーブ全量）。
     * 確定済みカーブと同一（実質無変化）の場合はイベントを作らない。
     */
    fun onCurveCommit(points: List<List<Double>>) {
        dragging.value = false
        viewModelScope.launch {
            val normalized = EditNodeSpecs.normalizeCurve(points)
            if (normalized == null) {
                // ドラッグの残滓が不正な場合は一時値を捨てて確定済み状態へ戻す。
                transientCurve.value = null
                scheduleRender(RenderTarget.PREVIEW, RenderRequest.Quality.PREVIEW, recipe.value ?: return@launch)
                return@launch
            }
            val base = recipe.value
            val target = curveOverrideForSelection(normalized)
            if (base?.develop?.curve == target) {
                transientCurve.value = null
                scheduleRender(RenderTarget.PREVIEW, RenderRequest.Quality.PREVIEW, base)
                return@launch
            }
            commitToStore("curve") { docId ->
                editStore.commitCurve(docId, committedRevision, target, selectedCurveChannel.value.eventKey)
            }
        }
    }

    /** 選択中カーブチャンネルを中立へ戻す。master は中立点列、チャンネルは未編集（空）へ。
     *  確定済みがすでに中立ならイベントを作らない。 */
    fun onCurveReset() {
        dragging.value = false
        transientCurve.value = null
        val channel = selectedCurveChannel.value
        val current = recipe.value?.develop?.curve ?: EditRecipe.Curve()
        val target = when (channel) {
            CurveChannel.MASTER -> current.copy(rgb = EditNodeSpecs.CURVE_NEUTRAL_RGB)
            CurveChannel.RED -> current.copy(r = emptyList())
            CurveChannel.GREEN -> current.copy(g = emptyList())
            CurveChannel.BLUE -> current.copy(b = emptyList())
        }
        if (current == target) {
            scheduleRender(RenderTarget.PREVIEW, RenderRequest.Quality.PREVIEW, recipe.value ?: return)
            return
        }
        commitToStore("curve") { docId ->
            editStore.commitCurve(docId, committedRevision, target, channel.eventKey)
        }
    }

    /** カーブチャンネル chips の選択（UI 状態・イベントは作らない）。 */
    fun onCurveChannelSelect(channel: CurveChannel) {
        selectedCurveChannel.value = channel
    }

    /**
     * ジオメトリ: 90°単位の回転（+1 = 時計回り, -1 = 反時計回り）。
     * 自由角度（PR-28b）導入後もボタンは「現在角 + ±90°」の加算として作用する
     * （微調整成分は保持したまま 90°回転する。例 12.5° + 90° = 102.5°）。
     */
    fun onGeometryRotate(deltaQuarters: Int) {
        val current = recipe.value?.geometry ?: EditRecipe.GeometryParams()
        val canonical = EditNodeSpecs.canonicalAngleDeg(current.angleDeg) ?: 0.0
        val next = EditNodeSpecs.canonicalAngleDeg(canonical + deltaQuarters * 90.0) ?: return
        commitGeometryNormalized(current.copy(angleDeg = next))
    }

    /**
     * ジオメトリ: 角度の微調整（PR-28b・-45..45）。UI スライダー/数値入力の確定値を受ける。
     * 90°刻み基準（現在角 − 微調整成分）を固定し、微調整だけを差し替えることで
     * スライダーの連続操作で基準が跳ねない（保存は正準域 [0,360) の全角）。
     */
    fun onGeometryFineAngle(fineDeg: Double) {
        if (!fineDeg.isFinite()) return
        val current = recipe.value?.geometry ?: EditRecipe.GeometryParams()
        val canonical = EditNodeSpecs.canonicalAngleDeg(current.angleDeg) ?: 0.0
        val base = canonical - EditNodeSpecs.fineAngleDeg(canonical)
        val next = EditNodeSpecs.canonicalAngleDeg(base + fineDeg.coerceIn(-45.0, 45.0)) ?: return
        commitGeometryNormalized(current.copy(angleDeg = next))
    }

    /** ジオメトリ: 回転角を 0° へ戻す（反転・クロップは保持）。 */
    fun onGeometryAngleReset() {
        val current = recipe.value?.geometry ?: EditRecipe.GeometryParams()
        commitGeometryNormalized(current.copy(angleDeg = 0.0))
    }

    /** ジオメトリ: 反転トグル（mirrorX = 水平, mirrorY = 垂直）。 */
    fun onGeometryFlip(mirrorX: Boolean) {
        val current = recipe.value?.geometry ?: EditRecipe.GeometryParams()
        val nextGeometry = if (mirrorX) current.copy(mirrorX = !current.mirrorX) else current.copy(mirrorY = !current.mirrorY)
        commitGeometryNormalized(nextGeometry)
    }

    /** ジオメトリ: アスペクト比プリセットの中央寄せ最大矩形クロップ。null は原寸（全面）。 */
    fun onGeometryCrop(aspect: Double?) {
        val asset = assetFlow.value ?: return
        val current = recipe.value?.geometry ?: EditRecipe.GeometryParams()
        // 回転後キャンバス寸法（任意角は外接矩形・90°刻みは寸法入替）を単一正本から取得する。
        val (canvasWpx, canvasHpx) = RecipeGeometry.outputSize(asset.width, asset.height, current.angleDeg)
        val canvasW = canvasWpx.toDouble()
        val canvasH = canvasHpx.toDouble()
        val cropNorm = if (aspect == null) {
            EditRecipe.GeometryParams().cropNorm
        } else {
            // 縦長キャンバスではプリセット比を 90°回転した形で最近似させる。
            val canvasAspect = canvasW / canvasH
            val target = if (canvasAspect >= 1.0) aspect else 1.0 / aspect
            centeredMaxRect(canvasW, canvasH, target)
        }
        commitGeometryNormalized(current.copy(cropNorm = cropNorm))
    }

    /** ジオメトリ: クロップ解除（回転・反転は保持）。 */
    fun onGeometryResetCrop() {
        val current = recipe.value?.geometry ?: EditRecipe.GeometryParams()
        commitGeometryNormalized(current.copy(cropNorm = EditRecipe.GeometryParams().cropNorm))
    }

    /**
     * ジオメトリ: クロップ枠の平行移動（PR-28b・正規化キャンバス座標の増分）。
     * 枠サイズは保持し、キャンバス外へはみ出さないようクランプする（枠は縮めない）。
     */
    fun onGeometryCropNudge(dx: Double, dy: Double) {
        val current = recipe.value?.geometry ?: EditRecipe.GeometryParams()
        val c = current.cropNorm
        val spanX = c[2] - c[0]
        val spanY = c[3] - c[1]
        val maxX0 = (1.0 - spanX).coerceAtLeast(0.0)
        val maxY0 = (1.0 - spanY).coerceAtLeast(0.0)
        val x0 = (c[0] + dx).coerceIn(0.0, maxX0)
        val y0 = (c[1] + dy).coerceIn(0.0, maxY0)
        commitGeometryNormalized(current.copy(cropNorm = listOf(x0, y0, x0 + spanX, y0 + spanY)))
    }

    /**
     * ジオメトリ: クロップ枠のサイズ変更（PR-28b・中心固定）。[factor] < 1 で枠を縮小（拡大表示）、
     * 1 超で拡大。スパンは [EditNodeSpecs.CROP_MIN_SPAN]..1 にクランプし、キャンバス外へ出さない。
     */
    fun onGeometryCropScale(factor: Double) {
        if (!factor.isFinite() || factor <= 0.0) return
        val current = recipe.value?.geometry ?: EditRecipe.GeometryParams()
        val c = current.cropNorm
        val cx = (c[0] + c[2]) / 2.0
        val cy = (c[1] + c[3]) / 2.0
        val spanX = ((c[2] - c[0]) * factor).coerceIn(EditNodeSpecs.CROP_MIN_SPAN, 1.0)
        val spanY = ((c[3] - c[1]) * factor).coerceIn(EditNodeSpecs.CROP_MIN_SPAN, 1.0)
        val x0 = (cx - spanX / 2.0).coerceIn(0.0, 1.0 - spanX)
        val y0 = (cy - spanY / 2.0).coerceIn(0.0, 1.0 - spanY)
        commitGeometryNormalized(current.copy(cropNorm = listOf(x0, y0, x0 + spanX, y0 + spanY)))
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

    // ----- 局所マスク（PR-29c・D3）-----

    /**
     * マスク追加（**1 追加 = 1 履歴イベント**）。既定形状は [MaskDraft]。
     * 上限 [MaskSpecs.MAX_MASKS] では何もしない（UI も追加ボタンを無効化する）。
     * 追加したマスクを選択状態にする（すぐ overlay で編集できる）。
     */
    fun onMaskAdd(type: String) {
        val current = recipe.value?.masks ?: return
        if (current.size >= MaskSpecs.MAX_MASKS) return
        val node = MaskDraft.create(type) ?: return
        selectedMaskId.value = node.maskId
        commitMasks(current + node, node.maskId)
    }

    /** 選択マスクの切替（UI セッション状態・イベントは作らない）。 */
    fun onMaskSelect(maskId: String?) {
        selectedMaskId.value = maskId
    }

    /** マスク削除（**1 削除 = 1 履歴イベント**・選択は解除）。 */
    fun onMaskRemove(maskId: String) {
        val current = recipe.value?.masks ?: return
        if (current.none { it.maskId == maskId }) return
        if (selectedMaskId.value == maskId) selectedMaskId.value = null
        commitMasks(current.filterNot { it.maskId == maskId }, null)
    }

    /**
     * マスク全量のドラッグ中プレビュー（履歴に書かない・DRAFT 再描画のみ）。
     * 空リストは「全削除の一時状態」であり有効値（null と区別する）。
     */
    fun onMaskPreview(masks: List<EditRecipe.MaskNode>) {
        val normalized = MaskSpecs.normalizeAll(masks) ?: return
        dragging.value = true
        transientMasks.value = normalized
        val now = System.currentTimeMillis()
        if (now - lastDraftAt >= DRAFT_INTERVAL_MS) {
            lastDraftAt = now
            scheduleRender(RenderTarget.PREVIEW, RenderRequest.Quality.DRAFT, draftRecipe())
        }
    }

    /**
     * マスク全量の確定（**1 操作 = 1 履歴イベント**・楽観排他は [EditStore.commitMasks]）。
     * 確定値と実質同一ならイベントを作らない（スライダーの `onSliderCommit` と同じ流儀）。
     */
    fun onMaskCommit(masks: List<EditRecipe.MaskNode>, maskId: String?) {
        dragging.value = false
        val normalized = MaskSpecs.normalizeAll(masks)
        if (normalized == null) {
            // ドラッグの残滓が不正: 一時値を捨てて確定状態へ戻す（既定値へ落とさない）。
            transientMasks.value = null
            clearBrushPending()
            recipe.value?.let { scheduleRender(RenderTarget.PREVIEW, RenderRequest.Quality.PREVIEW, it) }
            return
        }
        if (normalized == recipe.value?.masks) {
            transientMasks.value = null
            clearBrushPending()
            recipe.value?.let { scheduleRender(RenderTarget.PREVIEW, RenderRequest.Quality.PREVIEW, it) }
            return
        }
        commitMasks(normalized, maskId)
    }

    /** マスク内 adjustments のドラッグ中プレビュー（表示値を受ける）。 */
    fun onMaskAdjustPreview(maskId: String, path: String, value: Double) {
        adjustMask(maskId, path, value, commit = false)
    }

    /** マスク内 adjustments の確定（1 スライダー操作 = 1 履歴イベント）。 */
    fun onMaskAdjustCommit(maskId: String, path: String, value: Double) {
        adjustMask(maskId, path, value, commit = true)
    }

    /** マスク内 adjustments を全消去（=「0 に戻す」・マスク調整のみ・他の補正は不変）。 */
    fun onMaskResetAdjustments(maskId: String) {
        updateMask(maskId, commit = true) { node ->
            if (node.adjustments.isEmpty()) node else node.copy(adjustments = emptyMap())
        }
    }

    /** feather / angleDeg のドラッグ中プレビュー（angleDeg は表示 -180..180 を受ける）。 */
    fun onMaskParamPreview(maskId: String, key: String, value: Double) {
        setMaskParam(maskId, key, value, commit = false)
    }

    /** feather / angleDeg の確定（1 スライダー操作 = 1 履歴イベント）。 */
    fun onMaskParamCommit(maskId: String, key: String, value: Double) {
        setMaskParam(maskId, key, value, commit = true)
    }

    /**
     * brush の 1 点プレビュー（ドラッグ中）。点は [MaskBrushSampler] の間引き済みを渡す。
     * ジェスチャ中のストロークは VM が保持し、確定時にまとめて 1 イベントにする。
     */
    fun onMaskBrushAddPreview(maskId: String, stroke: EditRecipe.BrushStroke) {
        val base = transientMasks.value ?: recipe.value?.masks ?: return
        val index = base.indexOfFirst { it.maskId == maskId }
        if (index < 0) return
        val node = base[index]
        val strokes = accumulateBrush(maskId, stroke, node) ?: return
        val updated = base.toMutableList().also { it[index] = node.copy(brushStrokes = strokes) }
        onMaskPreview(updated)
    }

    /**
     * brush のジェスチャ確定（**1 ジェスチャ = 1 履歴イベント**）。
     * 上限 [MaskSpecs.MAX_BRUSH_STROKES] を超える点は取り込まない（[MaskBrushSampler] が ignore）。
     */
    fun onMaskBrushAddCommit(maskId: String, stroke: EditRecipe.BrushStroke) {
        val base = recipe.value?.masks ?: return
        val index = base.indexOfFirst { it.maskId == maskId }
        if (index < 0) {
            clearBrushPending()
            return
        }
        val node = base[index]
        val pending = if (brushPendingMaskId == maskId) brushPending else emptyList()
        val withFinal = MaskBrushSampler.append(node.brushStrokes + pending, stroke)
        val merged = withFinal.drop(node.brushStrokes.size)
        clearBrushPending()
        if (merged.isEmpty()) return
        onMaskCommit(
            base.toMutableList().also { it[index] = node.copy(brushStrokes = node.brushStrokes + merged) },
            maskId,
        )
    }

    /** brush の半径（ツール設定・履歴を作らない・[EditorState.brushRadius] に反映）。 */
    fun onMaskBrushRadius(radius: Double) {
        brushRadius.value = MaskDraft.clampBrushRadius(radius)
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

    /** 下部パネルへの一次投影（スライダー値・カーブ・ジオメトリ・マスク。combine 段数の集約のため導入）。 */
    private data class PanelProjection(
        val sliders: List<SliderBind>,
        /** 選択チャンネルの一時値を差し替えた後のカーブ全量。 */
        val curve: EditRecipe.Curve,
        val curveChannel: CurveChannel,
        val geometry: EditRecipe.GeometryParams,
        /** 確定値 or ドラッグ中一時値（順序はレシピ＝適用順）。 */
        val masks: List<EditRecipe.MaskNode>,
        /** 選択中マスク（[masks] に存在しない ID は null へ落とす）。 */
        val selectedMaskId: String?,
        val brushRadius: Double,
    )

    private fun panelProjection() = combine(
        recipe.asStateFlow(),
        transient.asStateFlow(),
        combine(transientCurve.asStateFlow(), selectedCurveChannel.asStateFlow()) { curve, channel ->
            curve to channel
        },
        transientMasks.asStateFlow(),
        combine(selectedMaskId.asStateFlow(), brushRadius.asStateFlow()) { maskId, radius ->
            maskId to radius
        },
    ) { base, overrides, curveState, maskOverride, maskSession ->
        val effective = base ?: defaultRecipe(null)
        val masks = maskOverride ?: effective.masks
        val selected = maskSession.first?.takeIf { id -> masks.any { it.maskId == id } }
        PanelProjection(
            sliders = AdjustmentId.entries.map { id ->
                SliderBind(id, overrides[id] ?: id.sliderValueOf(effective))
            },
            curve = curveState.first ?: effective.develop.curve,
            curveChannel = curveState.second,
            geometry = effective.geometry,
            masks = masks,
            selectedMaskId = selected,
            brushRadius = maskSession.second,
        )
    }

    /**
     * 確定共通経路（スライダー / カーブ / ジオメトリ / マスクで同一契約）。
     * 失効（stale baseRevision）等: 無通知続行せず失敗を明示し、正本へ再同期（NFR-06）。
     */
    private fun commitToStore(source: String, action: suspend (String) -> ImageOpResult<EditStore.EditStoreState>) {
        viewModelScope.launch {
            saveState.value = SaveState.SAVING
            saveFailure.value = null
            val docId = documentId
            if (docId == null || recipe.value == null) {
                saveState.value = SaveState.FAILED
                saveFailure.value = strings.get(EditorR.string.editor_msg_doc_not_loaded)
                return@launch
            }
            when (val result = action(docId)) {
                is ImageOpResult.Success -> {
                    applyStoreState(result.value)
                    saveState.value = SaveState.SAVED
                    transient.value = emptyMap()
                    if (source == "curve") transientCurve.value = null
                    scheduleRender(RenderTarget.PREVIEW, RenderRequest.Quality.PREVIEW, result.value.recipe)
                }
                is ImageOpResult.Degraded -> {
                    applyStoreState(result.value)
                    saveState.value = SaveState.SAVED
                    saveFailure.value = "degraded: ${result.code}"
                    transient.value = emptyMap()
                    if (source == "curve") transientCurve.value = null
                }
                is ImageOpResult.Failure -> {
                    saveState.value = SaveState.FAILED
                    saveFailure.value = "${result.code}: ${result.detail.orEmpty()}"
                    resyncAfterFailure()
                }
            }
        }
    }

    /** ジオメトリ確定（入力は正規化済み想定。DB 側で再正規化・不正は FAIL）。 */
    private fun commitGeometryNormalized(geometry: EditRecipe.GeometryParams) {
        commitToStore("geometry") { docId ->
            editStore.commitGeometry(docId, committedRevision, geometry)
        }
    }

    // ----- 局所マスクの内部共通経路（PR-29c）-----

    /** マスク全量の確定（スライダー/カーブ/ジオメトリと同じ失敗時契約を commitToStore に委譲）。 */
    private fun commitMasks(masks: List<EditRecipe.MaskNode>, maskId: String?) {
        commitToStore("mask") { docId ->
            editStore.commitMasks(docId, committedRevision, masks, maskId)
        }
    }

    /**
     * 対象マスク 1 件だけを差し替えて preview/commit の共通経路へ流す。
     * 基底はドラッグ中の一時値（あれば）を使い、連続操作で他の値を巻き戻さない。
     */
    private fun updateMask(
        maskId: String,
        commit: Boolean,
        transform: (EditRecipe.MaskNode) -> EditRecipe.MaskNode,
    ) {
        val base = transientMasks.value ?: recipe.value?.masks ?: return
        val index = base.indexOfFirst { it.maskId == maskId }
        if (index < 0) return
        val updated = base.toMutableList()
        updated[index] = transform(updated[index])
        if (commit) onMaskCommit(updated, maskId) else onMaskPreview(updated)
    }

    /** adjustments の表示値 → レシピ値（範囲クランプ）→ 1 マスクの map 差し替え。 */
    private fun adjustMask(maskId: String, path: String, value: Double, commit: Boolean) {
        val spec = MaskAdjustments.spec(path) ?: return
        val recipeValue = MaskAdjustments.toRecipe(spec, value) ?: return
        updateMask(maskId, commit) { node ->
            node.copy(adjustments = node.adjustments + (path to recipeValue))
        }
    }

    /** feather / angleDeg の書き込み（許可キー以外は無視・angleDeg は表示 → [0,360)）。 */
    private fun setMaskParam(maskId: String, key: String, value: Double, commit: Boolean) {
        val base = transientMasks.value ?: recipe.value?.masks ?: return
        val node = base.firstOrNull { it.maskId == maskId } ?: return
        if (key !in MaskSpecs.paramKeys(node.maskType).orEmpty()) return
        val fallback = node.parameters[key] ?: MaskSpecs.MIN_FEATHER
        val next = when (key) {
            MaskSpecs.KEY_FEATHER -> MaskSlider.feather(value, fallback)
            MaskSpecs.KEY_ANGLE_DEG -> MaskSlider.angleSaved(value)
            else -> return
        }
        updateMask(maskId, commit) { target ->
            target.copy(parameters = target.parameters + (key to next))
        }
    }

    /**
     * ジェスチャ中の brush ストロークを 1 本の pending 列へ積む。
     * 追加できなければ null（不正値・重複・上限）。
     */
    private fun accumulateBrush(
        maskId: String,
        stroke: EditRecipe.BrushStroke,
        node: EditRecipe.MaskNode,
    ): List<EditRecipe.BrushStroke>? {
        if (brushPendingMaskId != maskId) {
            brushPendingMaskId = maskId
            brushPending = emptyList()
        }
        val combined = node.brushStrokes + brushPending
        val next = MaskBrushSampler.append(combined, stroke)
        if (next.size == combined.size) return null
        brushPending = next.drop(node.brushStrokes.size)
        return next
    }

    private fun clearBrushPending() {
        brushPendingMaskId = null
        brushPending = emptyList()
    }

    /**
     * キャンバス [canvasW, canvasH] に内接する・アスペクト比 [aspect] (=w/h) の
     * 中央寄せ最大矩形（正規化座標）。PR-28b の自由クロップは本プリセットを起点に
     * [onGeometryCropNudge]/[onGeometryCropScale] で枠を動かす最小版
     * （プレビュー上のエッジドラッグは未実装・UI で明示）。
     */
    private fun centeredMaxRect(canvasW: Double, canvasH: Double, aspect: Double): List<Double> {
        val canvasAspect = canvasW / canvasH
        val rectW: Double
        val rectH: Double
        if (aspect >= canvasAspect) {
            rectW = canvasW
            rectH = canvasW / aspect
        } else {
            rectH = canvasH
            rectW = canvasH * aspect
        }
        val l = (canvasW - rectW) / 2.0 / canvasW
        val t = (canvasH - rectH) / 2.0 / canvasH
        val r = (canvasW + rectW) / 2.0 / canvasW
        val b = (canvasH + rectH) / 2.0 / canvasH
        return listOf(l, t, r, b)
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
                            transientCurve.value = null
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
        // 確定状態へ戻ったため、マスクの一時値・未確定ストロークは破棄する（undo/redo/復元含む）。
        transientMasks.value = null
        clearBrushPending()
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

    /** 一時値を仮レシピへ合成（DB には触れない）。カーブ/マスクの一時値も同様に差し替える。 */
    private fun draftRecipe(): EditRecipe {
        val base = recipe.value ?: defaultRecipe(null)
        var out = base
        transient.value.forEach { (id, v) -> out = id.apply(out, v) }
        transientCurve.value?.let { out = out.copy(develop = out.develop.copy(curve = it)) }
        transientMasks.value?.let { out = out.copy(masks = it) }
        return out
    }

    private fun defaultRecipe(documentId: String?): EditRecipe = EditRecipe(
        documentId = documentId ?: "pending-doc",
        source = EditRecipe.RecipeSource(assetId = assetId, fingerprint = "unscanned"),
    )

    /** 履歴行のラベル。スライダーは AdjustmentId、ノード事件（curve/geometry）は絶対状態から要約。 */
    private fun historyRow(item: EditStore.EditStoreHistoryItem): HistoryRow {
        val id = item.path?.let { AdjustmentId.byPath(it) }
        val opLabel = when (item.op) {
            RecipeParameterEdit.OP_APPLY -> strings.get(EditorR.string.editor_history_op_apply)
            RecipeParameterEdit.OP_UNDO -> strings.get(EditorR.string.editor_history_op_undo)
            RecipeParameterEdit.OP_REDO -> strings.get(EditorR.string.editor_history_op_redo)
            else -> item.eventType
        }
        val label: String = when {
            item.eventType == EditEventKind.CURVE.id -> {
                // カーブの編集対象チャンネルを含め要約表示（payload JSON は UI に露出しない）。
                // 点数は当該イベントの payload 状態における編集チャンネル側の制御点数
                // （ch 未記載=master 編集 / 復帰系フォールバック → rgb の点数。チャンネルが
                // 未編集化（空）に戻ったときは 0点と表示する）。
                val channelLabel = strings.get(curveChannelLabelRes(item.curveChannel))
                val points = when (item.curveChannel) {
                    null -> item.curve?.rgb?.size
                    "r" -> item.curve?.r?.size
                    "g" -> item.curve?.g?.size
                    "b" -> item.curve?.b?.size
                    else -> item.curve?.rgb?.size
                } ?: 0
                strings.get(EditorR.string.editor_history_curve, opLabel, channelLabel, points)
            }
            item.eventType == EditEventKind.GEOMETRY.id -> {
                val g = item.geometry
                if (g == null) {
                    strings.get(EditorR.string.editor_history_geometry_plain, opLabel)
                }
                else {
                    val flips = listOfNotNull(
                        if (g.mirrorX) strings.get(EditorR.string.editor_history_flip_h) else null,
                        if (g.mirrorY) strings.get(EditorR.string.editor_history_flip_v) else null,
                    ).joinToString("・")
                    val full = g.cropNorm == EditRecipe.GeometryParams().cropNorm
                    val crop = if (full) null else strings.get(EditorR.string.editor_history_crop)
                    val detail = listOf(
                        strings.get(EditorR.string.editor_history_rotation, formatAngleDeg(g.angleDeg)),
                        flips,
                        crop,
                    ).filterNotNull().joinToString("・")
                    strings.get(EditorR.string.editor_history_geometry, opLabel, detail)
                }
            }
            item.eventType == EditEventKind.LOCAL.id -> {
                // マスクは差分ではなく絶対状態（イベント時点の全マスク）を保持するため、
                // 件数で要約する（payload JSON は UI に露出しない）。
                strings.get(EditorR.string.editor_history_mask, opLabel, item.masks?.size ?: 0)
            }
            id == null -> strings.get(
                        EditorR.string.editor_history_by_path,
                        item.path ?: item.eventType,
                        opLabel,
                    )
            else -> {
                val v = item.value
                val valueText = if (v != null) id.formatValue(v) + id.unit.display else ""
                strings.get(
                        EditorR.string.editor_history_slider,
                        opLabel,
                        strings.get(AdjustmentLabels.labelRes(id)),
                        valueText,
                    )
            }
        }
        return HistoryRow(
            revisionNo = item.revisionNo,
            label = label.trim(),
            committedAt = item.committedAt,
        )
    }

    /** 角度の表示文字列（1 桁小数・整数は整数のまま・ロケール非依存）。履歴/UI の共通整形。 */
    private fun formatAngleDeg(deg: Double): String {
        val rounded = (deg * 10.0).roundToInt() / 10.0
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            String.format(java.util.Locale.ROOT, "%.1f", rounded)
        }
    }

    /** curve 履歴行のチャンネル表示（null=master。未知値は master 表示へ安全側で落とす）。 */
    private fun curveChannelLabelRes(channel: String?): Int = when (channel) {
        "r" -> EditorR.string.editor_curve_channel_r
        "g" -> EditorR.string.editor_curve_channel_g
        "b" -> EditorR.string.editor_curve_channel_b
        else -> EditorR.string.editor_curve_channel_master
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
