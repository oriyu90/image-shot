package com.imageshot.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.imageshot.core.model.AdjustmentId
import com.imageshot.feature.editor.R as EditorR
import com.imageshot.core.model.EditEventKind
import com.imageshot.core.model.EditNodeSpecs
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.MaskSpecs
import com.imageshot.core.render.RecipeCurve
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 編集画面（01_製品仕様書.md §4.3 視覚仕様 / §10 U02 U03）。
 *
 * - 基調は暗色ニュートラル（純黒でない低反射背景）、強調色は操作対象1箇所のみ。
 * - 中央プレビュー: pinch ズーム（1x..8x）+ 二本指パン。長押し Before/After は下部の
 *   専用ホールドボタンで行う（ピンチ/パンとタップ長押しのジェスチャ競合を避ける）。
 * - 下部ツールタブ: 基本調整 / カーブ（master + R/G/B チャンネル・PR-28a） / HSL（8系統） /
 *   ジオメトリ（90°+微調整の回転・反転・プリセット比+移動/サイズのクロップ・PR-28b） /
 *   ディテール（シャープ・輝度NR・PR-28c） / 局部補正（マスク・PR-29c）は実装。
 *   プレビュー上のエッジドラッグ（クロップ）・透視/レンズ補正は未実装（各タブ内で明示）。
 * - スライダー操作はスキーマ（AdjustmentId）から生成。ドラッグ反映は即時、
 *   離した時点で1件確定（onDragEnd → EditStore.commit）。カーブは指を離した時点で
 *   絶対状態イベント確定、ジオメトリ/マスクは操作ごとに1件確定。
 * - 局部補正（PR-29c）: パネルでマスクの追加/選択/削除・feather/角度/ブラシ半径・
 *   adjustments を編集し、プレビュー上に overlay（linear の A/B・radial の中心/rx/ry・brush の
 *   軌跡）を重ねる。overlay は画像と同じ graphicsLayer（scale/offset）と `ContentScale.Fit` の
 *   実表示矩形で写像するため、ズーム/パン中も座標が一致する。
 * - Undo/Redo/履歴一覧: DB 正本（EditStoreState）の表示。
 * - PR-30: G3（画面ルートが Scaffold で inset を自己管理・ホストの一括 padding を廃止）と
 *   F3（幅 840dp 以上でプレビューとツールパネルを横並び。ロジックは不変・配置のみ）を反映。
 */
@Composable
fun EditorScreen(
    state: EditorViewModel.EditorState,
    images: EditorViewModel.EditorImages,
    onSliderPreview: (AdjustmentId, Double) -> Unit,
    onSliderCommit: (AdjustmentId, Double) -> Unit,
    onCurvePreview: (List<List<Double>>) -> Unit,
    onCurveCommit: (List<List<Double>>) -> Unit,
    onCurveReset: () -> Unit,
    onCurveChannelSelect: (EditorViewModel.CurveChannel) -> Unit,
    onGeometryRotate: (Int) -> Unit,
    onGeometryFlip: (Boolean) -> Unit,
    onGeometryCrop: (Double?) -> Unit,
    onGeometryResetCrop: () -> Unit,
    onGeometryAngle: (Double) -> Unit,
    onGeometryAngleReset: () -> Unit,
    onGeometryCropNudge: (Double, Double) -> Unit,
    onGeometryCropScale: (Double) -> Unit,
    /**
     * 局部補正（マスク・PR-29c）の配線一式。個々の判断は VM が持ち、画面は
     * 「表示＋操作の転送」だけを行う（[EditorMaskActions] の KDoc 参照）。
     */
    maskActions: EditorMaskActions,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBeforeChanged: (Boolean) -> Unit,
    /** 書き出し画面へ遷移。引数は assetId（documentId は EditStore が assetId から解決）。 */
    onExport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // G3（PR-30）: ホストの一括 systemBarsPadding を廃止したため、この画面が Scaffold で
    // 自分の inset を持つ（safeDrawing = systemBars + cutout + IME。safeDrawing に IME が
    // 含まれるので imePadding を重ねない＝二重掛けしない）。
    // 数値入力ダイアログは別ウィンドウのため、この Scaffold の inset 対象外（Compose の
    // Dialog 実装の既定に従う・実機確認項目）。
    // F3: 幅 840dp 以上ではプレビュー（左・可変）とツールパネル（右・固定幅）を横並びにする。
    //     ロジック（スライダー/カーブ/ジオメトリの意味）は変更せず、配置だけを切り替える。
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = EditorPalette.bg,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .background(EditorPalette.bg),
        ) {
            // 選択中タブはレイアウト分岐の外側で保持する（幅の変化＝回転/折りたたみで
            // ツールパネルが別スロットへ移っても、ユーザーのタブ選択を失わない）。
            var toolTab by remember { mutableStateOf(EditorToolTab.BASIC) }
            // ツールパネルの呼び出し（引数が多いため1箇所に集約し、配置だけを差し替える）。
            val toolPanel: @Composable (Modifier) -> Unit = { panelModifier ->
                ToolPanel(
                    state = state,
                    selectedTab = toolTab,
                    onSelectTab = { toolTab = it },
                    onSliderPreview = onSliderPreview,
                    onSliderCommit = onSliderCommit,
                    onCurvePreview = onCurvePreview,
                    onCurveCommit = onCurveCommit,
                    onCurveReset = onCurveReset,
                    onCurveChannelSelect = onCurveChannelSelect,
                    onGeometryRotate = onGeometryRotate,
                    onGeometryFlip = onGeometryFlip,
                    onGeometryCrop = onGeometryCrop,
                    onGeometryResetCrop = onGeometryResetCrop,
                    onGeometryAngle = onGeometryAngle,
                    onGeometryAngleReset = onGeometryAngleReset,
                    onGeometryCropNudge = onGeometryCropNudge,
                    onGeometryCropScale = onGeometryCropScale,
                    maskActions = maskActions,
                    modifier = panelModifier.background(EditorPalette.panel),
                )
            }

            if (maxWidth >= WIDE_LAYOUT_MIN_WIDTH) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        EditorTopBar(state)
                        EditorPreviewArea(
                            state = state,
                            images = images,
                            maskActions = maskActions,
                            overlayEnabled = toolTab == EditorToolTab.LOCAL,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        EditorBottomBar(
                            state = state,
                            onUndo = onUndo,
                            onRedo = onRedo,
                            onBeforeChanged = onBeforeChanged,
                            onExport = onExport,
                            modifier = Modifier.fillMaxWidth().background(EditorPalette.panel),
                        )
                    }
                    // 区切り線（装飾のみ）。
                    Box(Modifier.width(1.dp).fillMaxHeight().background(EditorPalette.line))
                    toolPanel(Modifier.width(TOOL_PANEL_WIDE_WIDTH).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    EditorTopBar(state)
                    EditorPreviewArea(
                        state = state,
                        images = images,
                        maskActions = maskActions,
                        overlayEnabled = toolTab == EditorToolTab.LOCAL,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    toolPanel(Modifier.fillMaxWidth().heightIn(max = 360.dp))
                    EditorBottomBar(
                        state = state,
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onBeforeChanged = onBeforeChanged,
                        onExport = onExport,
                        modifier = Modifier.fillMaxWidth().background(EditorPalette.panel),
                    )
                }
            }
        }
    }
}

/**
 * プレビュー領域（プレビュー + 降格警告）。F3 の 2 レイアウトで同一の内容を共有する。
 * [overlayEnabled] は「局部補正タブが選択されている」ことを示し、マスクの overlay 表示を
 * そのタブにだけ限定する（他タブの操作をハンドルが邪魔しない）。
 */
@Composable
private fun EditorPreviewArea(
    state: EditorViewModel.EditorState,
    images: EditorViewModel.EditorImages,
    maskActions: EditorMaskActions,
    overlayEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        PreviewPane(
            images = images,
            showBefore = state.showBefore,
            renderFailure = state.renderFailure,
            masks = state.masks,
            selectedMaskId = state.selectedMaskId,
            brushRadius = state.brushRadius,
            overlayEnabled = overlayEnabled,
            maskActions = maskActions,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        if (state.renderWarnings.contains(com.imageshot.core.render.RecipeDetailApplier.DEGRADED_WARNING)) {
            // PR-28c: 性能契約（自動降格）の明示。ADR-014 の UI 警告経路の初結線（限定）。
            Text(
                text = stringResource(EditorR.string.editor_detail_degraded_notice),
                style = MaterialTheme.typography.labelSmall,
                color = EditorPalette.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(EditorPalette.panelRaised)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

// --------------------------------------------------------------- トークン（暗色ニュートラル）

private object EditorPalette {
    val bg = Color(0xFF141414) // 純黒でない低反射背景（§4.3）
    val panel = Color(0xFF1E1E1E)
    val panelRaised = Color(0xFF252525)
    val line = Color(0xFF353535)
    val textPrimary = Color(0xFFE8E8E8)
    val textSecondary = Color(0xFF9A9A9A)
    /** 強調色は操作対象1箇所に限る（§4.3）。スライダートラックと確定ボタンのみ使用。 */
    val accent = Color(0xFFC98355)
    val error = Color(0xFFE28181)
    val imageWell = Color(0xFF101010)
    /** タブ領域の未選択テキスト・補助線（カーブグリッド等）。 */
    val curveGrid = Color(0xFF2A2A2A)
    val curveIdentity = Color(0xFF4A4A4A)
}

// --------------------------------------------------------------- 上部バー

@Composable
private fun EditorTopBar(state: EditorViewModel.EditorState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(EditorPalette.panel)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = state.asset?.displayName ?: stringResource(EditorR.string.editor_loading_title),
                style = MaterialTheme.typography.titleSmall,
                color = EditorPalette.textPrimary,
            )
            Text(
                text = stringResource(EditorR.string.editor_revision, state.revision),
                style = MaterialTheme.typography.labelSmall,
                color = EditorPalette.textSecondary,
            )
        }
        SaveStateBadge(state.saveState, state.saveFailure)
    }
}

@Composable
private fun SaveStateBadge(saveState: EditorViewModel.SaveState, failure: String?) {
    val (text, color) = when (saveState) {
        EditorViewModel.SaveState.SAVING -> stringResource(EditorR.string.editor_save_saving) to EditorPalette.textSecondary
        EditorViewModel.SaveState.SAVED -> stringResource(EditorR.string.editor_save_saved) to EditorPalette.textSecondary
        EditorViewModel.SaveState.FAILED -> stringResource(EditorR.string.editor_save_failed) to EditorPalette.error
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
        if (failure != null) {
            Text(
                text = failure,
                style = MaterialTheme.typography.labelSmall,
                color = EditorPalette.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// --------------------------------------------------------------- プレビュー

@Composable
private fun PreviewPane(
    images: EditorViewModel.EditorImages,
    showBefore: Boolean,
    renderFailure: String?,
    masks: List<EditRecipe.MaskNode>,
    selectedMaskId: String?,
    brushRadius: Double,
    /** 局部補正タブ選択中のみ true（overlay の表示/操作を限定する）。 */
    overlayEnabled: Boolean,
    maskActions: EditorMaskActions,
    modifier: Modifier = Modifier,
) {
    // §4.3: ピンチズーム 1:1/フィット/拡大 と二本指パン（M1 は 1x..8x の連続域）。
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 8f)
        offset += panChange
    }

    Box(
        modifier = modifier
            .background(EditorPalette.imageWell)
            .transformable(transformableState)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val shown = if (showBefore) images.before else images.preview
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = stringResource(if (showBefore) EditorR.string.editor_cd_before_preview else EditorR.string.editor_cd_preview),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            // マスク overlay（PR-29c）。画像と同一の graphicsLayer を掛けるため、
            // ズーム/パン中も正規化座標↔画面座標の写像がずれない。Before 表示中は
            // 既定値レシピ（マスク無し）なので overlay も出さない。
            if (overlayEnabled && !showBefore) {
                MaskOverlay(
                    imageWidth = shown.width,
                    imageHeight = shown.height,
                    masks = masks,
                    selectedMaskId = selectedMaskId,
                    brushRadius = brushRadius,
                    scale = scale,
                    offset = offset,
                    actions = maskActions,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Text(
                text = stringResource(EditorR.string.editor_generating_preview),
                color = EditorPalette.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (showBefore && images.before != null) {
            Text(
                text = stringResource(EditorR.string.editor_badge_before),
                style = MaterialTheme.typography.labelMedium,
                color = EditorPalette.textPrimary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(EditorPalette.panel)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        // ADR-014: 描画失敗は無通知に代替画像へ切り替えない。
        renderFailure?.let { failure ->
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(EditorPalette.panel.copy(alpha = 0.9f))
                    .padding(8.dp),
            ) {
                Text(
                    text = stringResource(EditorR.string.editor_render_failed_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = EditorPalette.error,
                )
                Text(
                    text = failure,
                    style = MaterialTheme.typography.labelSmall,
                    color = EditorPalette.textSecondary,
                )
            }
        }
    }
}

// --------------------------------------------------------------- マスク overlay（PR-29c）

/** ハンドルの当たり判定（48dp 相当 = 半径 24dp）。 */
private const val MASK_HANDLE_TOUCH_DP = 48

/** ハンドルの描画半径。 */
private val MASK_HANDLE_RADIUS = 7.dp

/** 形状線・ハンドル枠の線幅。 */
private val MASK_STROKE_WIDTH = 1.5.dp

/**
 * プレビュー上のマスク overlay（PR-29c）。
 *
 * 座標写像:
 * - 画像は `ContentScale.Fit` で描かれるため、実表示矩形を
 *   [MaskOverlayGeometry.fitRect]（画像寸法と Canvas 寸法から算出）で求める。
 * - この Canvas には Image と **同じ scale/offset の graphicsLayer** を掛ける。したがって
 *   写像は「Canvas ローカル座標」で完結し、ズーム/パン中も正規化座標と一致する
 *   （graphicsLayer の内側に pointerInput を置くため、ドラッグ座標も逆変換済みのローカル値）。
 * - 画像外（レターボックス）へドラッグした場合は [MaskOverlayGeometry.toNormalized] が
 *   0..1 へクランプする（端で止まる）。
 *
 * 操作:
 * - linear/radial: 選択マスクのハンドルをドラッグ（当たり判定 48dp・選択マスクのみ表示）。
 *   ドラッグ中は `onPreview`、離した時点で `onCommit`（1 操作 = 1 履歴イベント）。
 * - brush: 指の軌跡を半径間隔で間引いてストロークを追加（1 ジェスチャ = 1 commit・上限 256）。
 *   2 本指のピンチとは競合しうる（実機確認項目）。
 */
@Composable
private fun MaskOverlay(
    imageWidth: Int,
    imageHeight: Int,
    masks: List<EditRecipe.MaskNode>,
    selectedMaskId: String?,
    brushRadius: Double,
    scale: Float,
    offset: Offset,
    actions: EditorMaskActions,
    modifier: Modifier = Modifier,
) {
    val touchRadiusPx = with(LocalDensity.current) { (MASK_HANDLE_TOUCH_DP / 2).dp.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val rect = remember(canvasSize, imageWidth, imageHeight) {
        MaskOverlayGeometry.fitRect(canvasSize.width, canvasSize.height, imageWidth, imageHeight)
    }
    // ジェスチャ中に最新の状態を読む（pointerInput のコルーチンはキー変更まで再起動しない）。
    val latestMasks by rememberUpdatedState(masks)
    val latestSelected by rememberUpdatedState(selectedMaskId)
    val latestBrushRadius by rememberUpdatedState(brushRadius)
    var dragKind by remember { mutableStateOf<MaskHandles.Kind?>(null) }
    var brushLast by remember { mutableStateOf<EditRecipe.BrushStroke?>(null) }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(rect, selectedMaskId, brushRadius) {
                val fit = rect ?: return@pointerInput
                fun node(): EditRecipe.MaskNode? =
                    latestMasks.firstOrNull { it.maskId == latestSelected }

                detectDragGestures(
                    onDragStart = { position ->
                        val target = node() ?: return@detectDragGestures
                        if (target.maskType == MaskSpecs.TYPE_BRUSH) {
                            val point = MaskOverlayGeometry.toNormalized(fit, position.x, position.y)
                                ?: return@detectDragGestures
                            val stroke = EditRecipe.BrushStroke(
                                x = point.first,
                                y = point.second,
                                radius = latestBrushRadius,
                                opacity = 1.0,
                            )
                            brushLast = stroke
                            actions.onBrushAddPreview(target.maskId, stroke)
                        } else {
                            dragKind = MaskOverlayGeometry.nearestHandle(
                                node = target,
                                rect = fit,
                                screenX = position.x,
                                screenY = position.y,
                                tolerancePx = touchRadiusPx,
                            )
                        }
                    },
                    onDrag = { change, _ ->
                        val target = node() ?: return@detectDragGestures
                        if (target.maskType == MaskSpecs.TYPE_BRUSH) {
                            change.consume()
                            val point = MaskOverlayGeometry.toNormalized(fit, change.position.x, change.position.y)
                                ?: return@detectDragGestures
                            if (!MaskBrushSampler.shouldAppend(
                                    last = brushLast,
                                    x = point.first,
                                    y = point.second,
                                    rect = fit,
                                    radius = latestBrushRadius,
                                )
                            ) {
                                return@detectDragGestures
                            }
                            val stroke = EditRecipe.BrushStroke(
                                x = point.first,
                                y = point.second,
                                radius = latestBrushRadius,
                                opacity = 1.0,
                            )
                            brushLast = stroke
                            actions.onBrushAddPreview(target.maskId, stroke)
                        } else {
                            val kind = dragKind ?: return@detectDragGestures
                            change.consume()
                            val point = MaskOverlayGeometry.toNormalized(fit, change.position.x, change.position.y)
                                ?: return@detectDragGestures
                            val updated = latestMasks.map { item ->
                                if (item.maskId == target.maskId) {
                                    MaskHandles.move(item, kind, point.first, point.second)
                                } else {
                                    item
                                }
                            }
                            actions.onPreview(updated)
                        }
                    },
                    onDragEnd = {
                        val target = node()
                        if (target != null && target.maskType == MaskSpecs.TYPE_BRUSH) {
                            brushLast?.let { stroke -> actions.onBrushAddCommit(target.maskId, stroke) }
                        } else if (target != null && dragKind != null) {
                            actions.onCommit(latestMasks, target.maskId)
                        }
                        dragKind = null
                        brushLast = null
                    },
                    onDragCancel = {
                        // キャンセル（2本指ジェスチャに奪われた等）でも、表示済みのプレビューを
                        // 未確定のまま残さない: 終了時と同じ確定経路へ流す（実質無変化なら
                        // VM がイベントを作らない）。
                        val target = node()
                        if (target != null && target.maskType == MaskSpecs.TYPE_BRUSH) {
                            brushLast?.let { stroke -> actions.onBrushAddCommit(target.maskId, stroke) }
                        } else if (target != null && dragKind != null) {
                            actions.onCommit(latestMasks, target.maskId)
                        }
                        dragKind = null
                        brushLast = null
                    },
                )
            },
    ) {
        val fit = MaskOverlayGeometry.fitRect(
            containerWidth = size.width.toInt(),
            containerHeight = size.height.toInt(),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        ) ?: return@Canvas
        val strokeWidth = MASK_STROKE_WIDTH.toPx()
        val handleRadius = MASK_HANDLE_RADIUS.toPx()
        masks.forEach { raw ->
            // レシピ由来の未正規化マスク（破損/将来形式）で Canvas に NaN を渡さない。
            // 適用側（RecipeMaskApplier）と同じく、正規化できないノードは描画もしない。
            val node = MaskSpecs.normalize(raw) ?: return@forEach
            val selected = node.maskId == selectedMaskId
            val color = if (selected) EditorPalette.accent else EditorPalette.textSecondary.copy(alpha = 0.45f)
            when (node.maskType) {
                MaskSpecs.TYPE_LINEAR -> drawLinearMask(fit, node, color, strokeWidth)
                MaskSpecs.TYPE_RADIAL -> drawRadialMask(fit, node, color, selected, strokeWidth)
                MaskSpecs.TYPE_BRUSH -> drawBrushMask(fit, node, color, strokeWidth)
            }
            if (selected) {
                MaskHandles.handlesOf(node).forEach { handle ->
                    drawMaskHandle(fit, handle, handleRadius, strokeWidth)
                }
            }
        }
    }
}

/** linear: A/B 線＋feather 帯（境界線に直交・幅 = feather×|AB|）＋中点の境界線。 */
private fun DrawScope.drawLinearMask(
    rect: FitRect,
    node: EditRecipe.MaskNode,
    color: Color,
    strokeWidth: Float,
) {
    val p = node.parameters
    val x0 = p[MaskSpecs.KEY_X0] ?: return
    val y0 = p[MaskSpecs.KEY_Y0] ?: return
    val x1 = p[MaskSpecs.KEY_X1] ?: return
    val y1 = p[MaskSpecs.KEY_Y1] ?: return
    val a = MaskOverlayGeometry.toScreen(rect, x0, y0)
    val b = MaskOverlayGeometry.toScreen(rect, x1, y1)
    val feather = MaskSlider.feather(
        p[MaskSpecs.KEY_FEATHER] ?: MaskSpecs.DEFAULT_LINEAR_FEATHER,
        MaskSpecs.DEFAULT_LINEAR_FEATHER,
    )
    val bandWidth = MaskOverlayGeometry.linearBandWidthPx(rect, x0, y0, x1, y1, feather)
    val midX = (a.x + b.x) / 2f
    val midY = (a.y + b.y) / 2f
    val dx = b.x - a.x
    val dy = b.y - a.y
    val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
    val nx = -dy / length
    val ny = dx / length
    // 画面全体を横切る長さ（画像外まで伸ばして帯を切らない）。
    val extent = size.width + size.height
    val start = Offset(midX - nx * extent, midY - ny * extent)
    val end = Offset(midX + nx * extent, midY + ny * extent)
    if (bandWidth > 0.5f) {
        drawLine(color = color.copy(alpha = 0.12f), start = start, end = end, strokeWidth = bandWidth)
    }
    drawLine(color = color, start = start, end = end, strokeWidth = strokeWidth)
    drawLine(
        color = color.copy(alpha = 0.55f),
        start = Offset(a.x, a.y),
        end = Offset(b.x, b.y),
        strokeWidth = strokeWidth,
    )
}

/** radial: 楕円（angleDeg は「画面で時計回り」＝ core の順写像と一致）＋feather リング。 */
private fun DrawScope.drawRadialMask(
    rect: FitRect,
    node: EditRecipe.MaskNode,
    color: Color,
    selected: Boolean,
    strokeWidth: Float,
) {
    val p = node.parameters
    val cx = p[MaskSpecs.KEY_CX] ?: return
    val cy = p[MaskSpecs.KEY_CY] ?: return
    val rx = p[MaskSpecs.KEY_RX] ?: return
    val ry = p[MaskSpecs.KEY_RY] ?: return
    val center = MaskOverlayGeometry.toScreen(rect, cx, cy)
    val (rxPx, ryPx) = MaskOverlayGeometry.ellipseRadiiPx(rect, rx, ry)
    if (rxPx <= 0f || ryPx <= 0f) return
    val angleDeg = p[MaskSpecs.KEY_ANGLE_DEG] ?: MaskSpecs.DEFAULT_ANGLE_DEG
    val feather = MaskSlider.feather(
        p[MaskSpecs.KEY_FEATHER] ?: MaskSpecs.DEFAULT_RADIAL_FEATHER,
        MaskSpecs.DEFAULT_RADIAL_FEATHER,
    )
    // 楕円は **正規化座標空間** で回転させる（core の MaskWeight と同じ定義）。
    // 画面 px で rotate すると縦横比の分だけシアーし、角度付きマスクの見た目と
    // 実際の適用範囲・ハンドル位置が一致しない（4:3 等で顕著）。
    val theta = Math.toRadians(angleDeg)
    val c = cos(theta)
    val sn = sin(theta)
    fun ellipsePath(scale: Double): Path {
        val path = Path()
        val steps = 96
        for (i in 0..steps) {
            val phi = 2.0 * PI * i / steps
            val lx = rx * scale * cos(phi)
            val ly = ry * scale * sin(phi)
            val nx = cx + lx * c - ly * sn
            val ny = cy + lx * sn + ly * c
            val point = MaskOverlayGeometry.toScreen(rect, nx, ny)
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()
        return path
    }
    if (selected && feather > 0.0) {
        val innerScale = (1.0 - feather).coerceIn(0.0, 1.0)
        if (innerScale > 1e-4) {
            val ring = Path().apply {
                fillType = PathFillType.EvenOdd
                addPath(ellipsePath(1.0))
                addPath(ellipsePath(innerScale))
            }
            drawPath(ring, color = color.copy(alpha = 0.18f))
        }
    }
    drawPath(ellipsePath(1.0), color = color, style = Stroke(width = strokeWidth))
}

/** brush: 各ストロークを半透明の円で表示（半径は短辺基準＝ core と同一）。 */
private fun DrawScope.drawBrushMask(
    rect: FitRect,
    node: EditRecipe.MaskNode,
    color: Color,
    strokeWidth: Float,
) {
    node.brushStrokes.forEach { stroke ->
        if (!stroke.x.isFinite() || !stroke.y.isFinite() || !stroke.radius.isFinite()) return@forEach
        val center = MaskOverlayGeometry.toScreen(rect, stroke.x, stroke.y)
        val radiusPx = MaskOverlayGeometry.brushRadiusPx(rect, stroke.radius)
        val opacity = stroke.opacity.toFloat().coerceIn(0f, 1f)
        drawCircle(
            color = color.copy(alpha = 0.10f * opacity),
            radius = radiusPx,
            center = Offset(center.x, center.y),
        )
        drawCircle(
            color = color.copy(alpha = 0.55f),
            radius = radiusPx,
            center = Offset(center.x, center.y),
            style = Stroke(width = strokeWidth),
        )
    }
}

/** ハンドル（白丸＋強調色の枠・背景に暗い縁を付けて画像上でも視認できるようにする）。 */
private fun DrawScope.drawMaskHandle(
    rect: FitRect,
    handle: MaskHandles.Handle,
    radiusPx: Float,
    strokeWidth: Float,
) {
    val point = MaskOverlayGeometry.toScreen(rect, handle.x, handle.y)
    val center = Offset(point.x, point.y)
    drawCircle(color = EditorPalette.bg.copy(alpha = 0.85f), radius = radiusPx + strokeWidth, center = center)
    drawCircle(color = EditorPalette.textPrimary, radius = radiusPx, center = center)
    drawCircle(color = EditorPalette.accent, radius = radiusPx, center = center, style = Stroke(width = strokeWidth))
}

// --------------------------------------------------------------- 調整パネル（ツールタブ）

/** ツールタブ。全タブ実装済み（未実装時の明示経路は [ToolTab] に残す）。 */
private enum class EditorToolTab(val labelRes: Int, val hintRes: Int?, val implemented: Boolean) {
    BASIC(EditorR.string.editor_tab_basic, null, true),
    CURVE(EditorR.string.editor_tab_curve, null, true),
    HSL(EditorR.string.editor_tab_hsl, null, true),
    GEOMETRY(EditorR.string.editor_tab_geometry, null, true),
    DETAIL(EditorR.string.editor_tab_detail, null, true),
    /** 局部補正（マスク・PR-29c）。overlay 編集はプレビュー側の [MaskOverlay] が担う。 */
    LOCAL(EditorR.string.editor_tab_local, null, true),
}

@Composable
private fun ToolPanel(
    state: EditorViewModel.EditorState,
    /** 選択中タブ（レイアウト分岐で消えないよう EditorScreen 側が保持する）。 */
    selectedTab: EditorToolTab,
    onSelectTab: (EditorToolTab) -> Unit,
    onSliderPreview: (AdjustmentId, Double) -> Unit,
    onSliderCommit: (AdjustmentId, Double) -> Unit,
    onCurvePreview: (List<List<Double>>) -> Unit,
    onCurveCommit: (List<List<Double>>) -> Unit,
    onCurveReset: () -> Unit,
    onCurveChannelSelect: (EditorViewModel.CurveChannel) -> Unit,
    onGeometryRotate: (Int) -> Unit,
    onGeometryFlip: (Boolean) -> Unit,
    onGeometryCrop: (Double?) -> Unit,
    onGeometryResetCrop: () -> Unit,
    onGeometryAngle: (Double) -> Unit,
    onGeometryAngleReset: () -> Unit,
    onGeometryCropNudge: (Double, Double) -> Unit,
    onGeometryCropScale: (Double) -> Unit,
    maskActions: EditorMaskActions,
    modifier: Modifier = Modifier,
) {
    var historyOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // タブ行（未実装タブも選択可能 — 「未実装」と明示表示するため。成功装いの表示はしない）。
        // PR-30: 2ペイン時のツールパネルは幅 360dp のため、タブ 6 個が横に収まらない。
        // 折返しではなく横スクロールにして、全タブ（局部補正の未実装表示を含む）へ到達可能にする。
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            EditorToolTab.entries.forEach { tab ->
                ToolTab(
                    label = stringResource(tab.labelRes),
                    hint = tab.hintRes?.let { stringResource(it) },
                    implemented = tab.implemented,
                    selected = selectedTab == tab,
                    onClick = { onSelectTab(tab) },
                )
            }
        }
        when (selectedTab) {
            EditorToolTab.BASIC -> BasicAdjustmentsContent(state, onSliderPreview, onSliderCommit)
            EditorToolTab.CURVE -> MasterCurveContent(
                state = state,
                onCurvePreview = onCurvePreview,
                onCurveCommit = onCurveCommit,
                onCurveReset = onCurveReset,
                onCurveChannelSelect = onCurveChannelSelect,
            )
            EditorToolTab.HSL -> HslContent(state, onSliderPreview, onSliderCommit)
            EditorToolTab.GEOMETRY -> GeometryContent(
                state = state,
                onRotate = onGeometryRotate,
                onFlip = onGeometryFlip,
                onCrop = onGeometryCrop,
                onCropReset = onGeometryResetCrop,
                onAngle = onGeometryAngle,
                onAngleReset = onGeometryAngleReset,
                onCropNudge = onGeometryCropNudge,
                onCropScale = onGeometryCropScale,
            )
            EditorToolTab.DETAIL -> DetailContent(state, onSliderPreview, onSliderCommit)
            EditorToolTab.LOCAL -> LocalContent(state, maskActions)
        }
        TextButton(
            onClick = {
                historyOpen = !historyOpen
            },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                text = stringResource(
                    if (historyOpen) EditorR.string.editor_history_open else EditorR.string.editor_history_closed,
                    state.history.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = EditorPalette.textSecondary,
            )
        }
        if (historyOpen) {
            // 履歴は件数が増えると親パネルの固定高で潰れる（実機 20 件で高さ約 8px・
            // スクロール不能を観測）。上限高+内部スクロールで全件到達可能にする。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 168.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                state.history.asReversed().forEach { row ->
                    Text(
                        text = "r${row.revisionNo}  ${row.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = EditorPalette.textPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(EditorPalette.panelRaised)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                if (state.history.isEmpty()) {
                    Text(
                        text = stringResource(EditorR.string.editor_history_empty),
                        style = MaterialTheme.typography.labelSmall,
                        color = EditorPalette.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolTab(
    label: String,
    hint: String?,
    implemented: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) EditorPalette.panelRaised else EditorPalette.panel
    Column(
        modifier = Modifier
            .background(bg)
            .border(1.dp, EditorPalette.line)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = EditorPalette.textPrimary,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = EditorPalette.textSecondary,
            )
        }
        if (!implemented) {
            Text(
                text = stringResource(EditorR.string.editor_not_implemented),
                style = MaterialTheme.typography.labelSmall,
                color = EditorPalette.accent,
            )
        }
    }
}

// --------------------------------------------------------------- 基本調整タブ

@Composable
private fun BasicAdjustmentsContent(
    state: EditorViewModel.EditorState,
    onSliderPreview: (AdjustmentId, Double) -> Unit,
    onSliderCommit: (AdjustmentId, Double) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Text(
            text = stringResource(EditorR.string.editor_tab_basic),
            style = MaterialTheme.typography.titleSmall,
            color = EditorPalette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (state.dragging) {
            // §4.3: 動作の重い場合の品質切替を明示（画質劣化は保存に反映しない）。
            Text(
                text = stringResource(EditorR.string.editor_drag_quality),
                style = MaterialTheme.typography.labelSmall,
                color = EditorPalette.textSecondary,
            )
        }
    }
    val adjustables = state.sliders.filter { bind ->
        when (bind.id.kind) {
            EditEventKind.TONE, EditEventKind.WHITE_BALANCE, EditEventKind.COLOR -> true
            else -> false
        }
    }
    adjustables.forEach { bind ->
        SliderRow(
            id = bind.id,
            value = bind.value,
            onPreview = onSliderPreview,
            onCommit = onSliderCommit,
        )
    }
}

// --------------------------------------------------------------- ディテールタブ

/**
 * ディテール（PR-28c）: シャープ / 輝度ノイズ低減の 2 スライダー。
 * - 値は AdjustmentId（0..100）で、レシピには 0..1 に正規化して保存する。
 * - 空間 stage は develop 後・リサイズ前（preview/export 同一・[RecipeDetailApplier] が正本）。
 * - 画素数が閾値超過のときは軽量経路へ自動降格し、プレビュー上部に明示する（[EditorScreen]）。
 */
@Composable
private fun DetailContent(
    state: EditorViewModel.EditorState,
    onSliderPreview: (AdjustmentId, Double) -> Unit,
    onSliderCommit: (AdjustmentId, Double) -> Unit,
) {
    Text(
        text = stringResource(EditorR.string.editor_detail_title),
        style = MaterialTheme.typography.titleSmall,
        color = EditorPalette.textPrimary,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = stringResource(EditorR.string.editor_detail_notice),
        style = MaterialTheme.typography.labelSmall,
        color = EditorPalette.textSecondary,
    )
    state.sliders.filter { bind -> bind.id.kind == EditEventKind.DETAIL }.forEach { bind ->
        SliderRow(
            id = bind.id,
            value = bind.value,
            onPreview = onSliderPreview,
            onCommit = onSliderCommit,
        )
    }
    Text(
        text = stringResource(EditorR.string.editor_detail_help),
        style = MaterialTheme.typography.labelSmall,
        color = EditorPalette.textSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
    val asset = state.asset
    if (asset != null &&
        asset.width.toLong() * asset.height.toLong() >
        com.imageshot.core.render.RecipeDetailApplier.DEGRADE_PIXEL_COUNT
    ) {
        // 自動降格は preview ではなく export（画素数）で決まるため、事前に明示する。
        Text(
            text = stringResource(EditorR.string.editor_detail_light_path_notice),
            style = MaterialTheme.typography.labelSmall,
            color = EditorPalette.accent,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// --------------------------------------------------------------- カーブタブ（master + R/G/B）

/**
 * トーンカーブの操作面（§5.2 の M1 実装範囲・PR-28a でチャンネル別を追加）。
 * - 対象チャンネルは chips（マスター / R / G / B）で選ぶ。点列操作はチャンネル共通。
 * - ドラッグ: 制御点移動、タップ: 追加（最大 16 点）、ダブルタップ: 削除（最小 2 点）。
 * - 指を離した時点で正規化+絶対状態確定（EditStore.commitCurve・カーブ全量）。
 * - 未編集（空）のチャンネルは恒等（中立 2 点）を表示の前提にする（追加/ドラッグで非空化）。
 */
@Composable
private fun MasterCurveContent(
    state: EditorViewModel.EditorState,
    onCurvePreview: (List<List<Double>>) -> Unit,
    onCurveCommit: (List<List<Double>>) -> Unit,
    onCurveReset: () -> Unit,
    onCurveChannelSelect: (EditorViewModel.CurveChannel) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Text(
            text = stringResource(EditorR.string.editor_curve_title),
            style = MaterialTheme.typography.titleSmall,
            color = EditorPalette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        // 強調色はこのタブの操作対象（選択チャンネルのリセット）のみ。
        TextButton(onClick = onCurveReset) {
            Text(stringResource(EditorR.string.editor_curve_reset), color = EditorPalette.accent)
        }
    }
    // 対象チャンネル chips（マスター / R / G / B）。横に並べる（幅上限での折返しはスクロールに委ねない）。
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        EditorViewModel.CurveChannel.entries.forEach { channel ->
            CurveChannelChip(
                label = stringResource(channel.labelRes),
                selected = channel == state.curveChannel,
                onClick = { onCurveChannelSelect(channel) },
            )
        }
    }
    Text(
        text = stringResource(EditorR.string.editor_curve_help),
        style = MaterialTheme.typography.labelSmall,
        color = EditorPalette.textSecondary,
    )

    // 表示する点列 = 選択チャンネルの確定状態（未編集は恒等 2 点を表示する）。
    val channelPoints: List<List<Double>> = when (state.curveChannel) {
        EditorViewModel.CurveChannel.MASTER -> state.curve.rgb
        EditorViewModel.CurveChannel.RED -> state.curve.r
        EditorViewModel.CurveChannel.GREEN -> state.curve.g
        EditorViewModel.CurveChannel.BLUE -> state.curve.b
    }
    // ドラッグ中はローカルの点列を表示の権原とする（正規化による並べ替えで
    // 選択点が入れ替わる見た目のジャンプを避ける）。確定は正規化済みで行う。
    var dragPoints by remember { mutableStateOf<List<List<Double>>?>(null) }
    var dragIndex by remember { mutableStateOf(-1) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val displayed: List<List<Double>> = dragPoints ?: channelPoints.ifEmpty { EditNodeSpecs.CURVE_NEUTRAL_RGB }

    Canvas(
        modifier = Modifier
            .padding(top = 8.dp)
            .size(280.dp)
            .background(EditorPalette.imageWell)
            .border(1.dp, EditorPalette.line)
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                // タップ: 追加 / ダブルタップ: 削除（表示中の点列を基準に操作）。
                detectTapGestures(
                    onTap = { offset ->
                        val w = canvasSize.width.coerceAtLeast(1)
                        val h = canvasSize.height.coerceAtLeast(1)
                        if (displayed.size >= EditNodeSpecs.CURVE_MAX_POINTS) return@detectTapGestures
                        val x = (offset.x / w.toDouble()).coerceIn(0.0, 1.0)
                        val y = (1.0 - offset.y / h.toDouble()).coerceIn(0.0, 1.0)
                        onCurveCommit(displayed + listOf(listOf(x, y)))
                    },
                    onDoubleTap = { offset ->
                        val w = canvasSize.width.coerceAtLeast(1)
                        val h = canvasSize.height.coerceAtLeast(1)
                        if (displayed.size <= EditNodeSpecs.CURVE_MIN_POINTS) return@detectTapGestures
                        val target = nearestIndex(displayed, offset, w, h, tolerance = 44.0)
                        if (target != null) {
                            onCurveCommit(displayed.filterIndexed { index, _ -> index != target })
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // ドラッグ: 最寄り制御点を掴んで移動（画面内 0..1 にクランプ）。
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = canvasSize.width.coerceAtLeast(1)
                        val h = canvasSize.height.coerceAtLeast(1)
                        val idx = nearestIndex(displayed, offset, w, h, tolerance = 44.0)
                        dragIndex = idx ?: -1
                        dragPoints = if (idx != null) displayed else null
                    },
                    onDrag = { change, _ ->
                        val idx = dragIndex
                        val points = dragPoints
                        val w = canvasSize.width.coerceAtLeast(1)
                        val h = canvasSize.height.coerceAtLeast(1)
                        if (idx >= 0 && points != null && idx in points.indices) {
                            change.consume()
                            val x = (change.position.x / w.toDouble()).coerceIn(0.0, 1.0)
                            val y = (1.0 - change.position.y / h.toDouble()).coerceIn(0.0, 1.0)
                            val next = points.mapIndexed { i, p ->
                                if (i == idx) listOf<Double>(x, y) else p
                            }
                            dragPoints = next
                            onCurvePreview(next)
                        }
                    },
                    onDragEnd = {
                        val points = dragPoints
                        if (points != null) onCurveCommit(points)
                        dragPoints = null
                        dragIndex = -1
                    },
                    onDragCancel = {
                        dragPoints = null
                        dragIndex = -1
                    },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        // 方眼（0.25 刻み）+ 恒等線（y=x）。表示座標は y 反転。
        val gridColor = EditorPalette.curveGrid
        for (i in 1..3) {
            val fx = w * i / 4f
            drawLine(gridColor, Offset(fx, 0f), Offset(fx, h), 1f)
            val fy = h * i / 4f
            drawLine(gridColor, Offset(0f, fy), Offset(w, fy), 1f)
        }
        drawLine(EditorPalette.curveIdentity, Offset(0f, h), Offset(w, 0f), 1f)

        // 曲線本体（RecipeCurve.evaluate の単調エルミート補間をサンプリング）。視認性のため描画は0..1で行う。
        val stroke = 2.dp.toPx()
        if (displayed.size >= 2) {
            val path = Path()
            val firstX = displayed.first()[0]
            val lastX = displayed.last()[0]
            val steps = 64
            var started = false
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                val x = lastX * t + firstX * (1.0 - t)
                val y = RecipeCurve.evaluate(displayed, x)
                val px = (x * w).toFloat()
                val py = ((1.0 - y) * h).toFloat()
                if (!started) {
                    path.moveTo(px, py)
                    started = true
                } else {
                    path.lineTo(px, py)
                }
            }
            drawPath(path, color = EditorPalette.accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
            // 制御点。
            displayed.forEach { p ->
                drawCircle(
                    color = EditorPalette.textPrimary,
                    radius = stroke,
                    center = Offset((p[0] * w).toFloat(), ((1.0 - p[1]) * h).toFloat()),
                )
            }
        }
    }
    Text(
        text = stringResource(
            EditorR.string.editor_curve_points,
            displayed.size,
            EditNodeSpecs.CURVE_MAX_POINTS,
            EditNodeSpecs.CURVE_MIN_POINTS,
        ),
        style = MaterialTheme.typography.labelSmall,
        color = EditorPalette.textSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** カーブ対象チャンネルの chip（選択中のみ強調）。タブヒントではなくカーブ内の操作対象切替。 */
@Composable
private fun CurveChannelChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) EditorPalette.textPrimary else EditorPalette.textSecondary,
        modifier = Modifier
            .background(if (selected) EditorPalette.panelRaised else EditorPalette.panel)
            .border(1.dp, EditorPalette.line)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 表示座標系（y 反転済み px）で最も近い制御点のインデックス。許容距離内に無ければ null。 */
private fun nearestIndex(
    points: List<List<Double>>,
    offset: Offset,
    widthPx: Int,
    heightPx: Int,
    tolerance: Double,
): Int? {
    if (points.isEmpty()) return null
    var best = -1
    var bestDist = Double.MAX_VALUE
    points.forEachIndexed { index, p ->
        val dx = p[0] * widthPx - offset.x
        val dy = (1.0 - p[1]) * heightPx - offset.y
        val d = (dx * dx + dy * dy)
        if (d < bestDist) {
            bestDist = d
            best = index
        }
    }
    return if (bestDist <= tolerance * tolerance) best else null
}

// --------------------------------------------------------------- HSL タブ

/**
 * HSL 8系統（§5.2 標準8系統×色相/彩度/輝度）。バンド選択チップ + 3スライダー。
 * 値はスキーマ（AdjustmentId / ±100 スライダー = hue ±30°）経由で正本確定している。
 */
@Composable
private fun HslContent(
    state: EditorViewModel.EditorState,
    onSliderPreview: (AdjustmentId, Double) -> Unit,
    onSliderCommit: (AdjustmentId, Double) -> Unit,
) {
    var selectedBand by remember { mutableStateOf(EditNodeSpecs.HSL_BANDS.first()) }
    Text(
        text = stringResource(EditorR.string.editor_hsl_title),
        style = MaterialTheme.typography.titleSmall,
        color = EditorPalette.textPrimary,
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        EditNodeSpecs.HSL_BANDS.forEach { bandId ->
            val label = stringResource(HslBandLabels.bandRes(bandId))
            val selected = selectedBand == bandId
            Column(
                modifier = Modifier
                    .background(if (selected) EditorPalette.panelRaised else EditorPalette.panel)
                    .border(1.dp, EditorPalette.line)
                    .clickable { selectedBand = bandId }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) EditorPalette.textPrimary else EditorPalette.textSecondary,
                )
            }
        }
    }
    val bandBindings = EditNodeSpecs.HSL_PARAMS.map { paramId ->
        state.sliders.firstOrNull { bind ->
            bind.id.recipePath == "develop.color.hsl.$selectedBand.$paramId"
        }
    }
    bandBindings.forEach { bind ->
        if (bind != null) {
            SliderRow(
                id = bind.id,
                value = bind.value,
                onPreview = onSliderPreview,
                onCommit = onSliderCommit,
            )
        }
    }
    Text(
        text = stringResource(EditorR.string.editor_hsl_legend),
        style = MaterialTheme.typography.labelSmall,
        color = EditorPalette.textSecondary,
    )
}

// --------------------------------------------------------------- ジオメトリタブ

/**
 * ジオメトリ（§5.2・PR-28b で自由角度/自由クロップの最小版を追加）:
 * - 回転: 90°ボタン（現在角へ加算）+ 微調整スライダー（±45°・中心角は 90°刻み基準を保持）。
 *   任意角は黒縁付きの外接矩形キャンバスへ展開する（塗り仕様はユーザー確定 2026-10-26）。
 * - 反転: 水平/垂直トグル。
 * - クロップ: アスペクト比プリセット + 枠の平行移動（←→↑↓）+ 枠サイズ（小さく/大きく）。
 *   プレビュー上のエッジドラッグと透視/レンズ補正は未実装（UI で明示）。
 * - 操作はすべて絶対状態の1イベントとして確定し、undo/redo の対象になる。
 */
@Composable
private fun GeometryContent(
    state: EditorViewModel.EditorState,
    onRotate: (Int) -> Unit,
    onFlip: (Boolean) -> Unit,
    onCrop: (Double?) -> Unit,
    onCropReset: () -> Unit,
    onAngle: (Double) -> Unit,
    onAngleReset: () -> Unit,
    onCropNudge: (Double, Double) -> Unit,
    onCropScale: (Double) -> Unit,
) {
    Text(
        text = stringResource(EditorR.string.editor_geometry_title),
        style = MaterialTheme.typography.titleSmall,
        color = EditorPalette.textPrimary,
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { onRotate(-1) }) { Text(stringResource(EditorR.string.editor_rotate_left), color = EditorPalette.textPrimary) }
        TextButton(onClick = { onRotate(1) }) { Text(stringResource(EditorR.string.editor_rotate_right), color = EditorPalette.textPrimary) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { onFlip(true) }) {
            Text(
                text = stringResource(
                    if (state.geometry.mirrorX) EditorR.string.editor_flip_h_on else EditorR.string.editor_flip_h_off,
                ),
                color = if (state.geometry.mirrorX) EditorPalette.accent else EditorPalette.textPrimary,
            )
        }
        TextButton(onClick = { onFlip(false) }) {
            Text(
                text = stringResource(
                    if (state.geometry.mirrorY) EditorR.string.editor_flip_v_on else EditorR.string.editor_flip_v_off,
                ),
                color = if (state.geometry.mirrorY) EditorPalette.accent else EditorPalette.textPrimary,
            )
        }
    }
    Text(
        text = stringResource(
            EditorR.string.editor_geometry_status,
            formatAngleText(state.geometry.angleDeg),
            cropSummary(state.geometry, stringResource(EditorR.string.editor_crop_none)),
        ),
        style = MaterialTheme.typography.labelSmall,
        color = EditorPalette.textSecondary,
    )

    // ---- 角度の微調整（±45°・90°刻み基準は保持）----
    var fineAngle by remember(state.geometry.angleDeg) {
        mutableStateOf(EditNodeSpecs.fineAngleDeg(state.geometry.angleDeg))
    }
    var angleDialogOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(EditorR.string.editor_geometry_angle_title),
            style = MaterialTheme.typography.bodySmall,
            color = EditorPalette.textPrimary,
            modifier = Modifier.padding(end = 8.dp),
        )
        TextButton(
            onClick = { angleDialogOpen = true },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        ) {
            Text(
                text = formatAngleText(fineAngle) + "°",
                style = MaterialTheme.typography.bodySmall,
                color = EditorPalette.textSecondary,
            )
        }
    }
    Slider(
        value = fineAngle.toFloat(),
        onValueChange = { fineAngle = it.toDouble() },
        onValueChangeFinished = { onAngle(fineAngle) },
        valueRange = -45f..45f,
        colors = SliderDefaults.colors(
            thumbColor = EditorPalette.accent,
            activeTrackColor = EditorPalette.accent,
            inactiveTrackColor = EditorPalette.line,
        ),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { fineAngle = 0.0; onAngleReset() }) {
            Text(stringResource(EditorR.string.editor_geometry_angle_reset), color = EditorPalette.textPrimary)
        }
    }
    if (angleDialogOpen) {
        GeometryAngleDialog(
            current = fineAngle,
            onDismiss = { angleDialogOpen = false },
            onConfirm = { parsed ->
                angleDialogOpen = false
                fineAngle = parsed
                onAngle(parsed)
            },
        )
    }

    Text(
        text = stringResource(EditorR.string.editor_crop_header),
        style = MaterialTheme.typography.bodySmall,
        color = EditorPalette.textPrimary,
        modifier = Modifier.padding(top = 8.dp),
    )
    // プリセット（原寸/1:1/3:2/4:3/16:9）。操作ごとに確定。プレビューはレンダリング経由で即反映。
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onCropReset) {
            Text(stringResource(EditorR.string.editor_crop_original), color = EditorPalette.textPrimary)
        }
        listOf("1:1" to 1.0, "3:2" to 1.5, "4:3" to 4.0 / 3.0, "16:9" to 16.0 / 9.0).forEach { (label, aspect) ->
            TextButton(onClick = { onCrop(aspect) }) {
                Text(label, color = EditorPalette.textPrimary)
            }
        }
    }
    // 自由クロップ最小版: 枠の平行移動（キャンバスの 2%）と中心固定のサイズ変更。
    Text(
        text = stringResource(EditorR.string.editor_crop_move_header),
        style = MaterialTheme.typography.labelSmall,
        color = EditorPalette.textSecondary,
        modifier = Modifier.padding(top = 6.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { onCropNudge(-CROP_NUDGE, 0.0) }) {
            Text(stringResource(EditorR.string.editor_crop_move_left), color = EditorPalette.textPrimary)
        }
        TextButton(onClick = { onCropNudge(CROP_NUDGE, 0.0) }) {
            Text(stringResource(EditorR.string.editor_crop_move_right), color = EditorPalette.textPrimary)
        }
        TextButton(onClick = { onCropNudge(0.0, -CROP_NUDGE) }) {
            Text(stringResource(EditorR.string.editor_crop_move_up), color = EditorPalette.textPrimary)
        }
        TextButton(onClick = { onCropNudge(0.0, CROP_NUDGE) }) {
            Text(stringResource(EditorR.string.editor_crop_move_down), color = EditorPalette.textPrimary)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { onCropScale(0.9) }) {
            Text(stringResource(EditorR.string.editor_crop_shrink), color = EditorPalette.textPrimary)
        }
        TextButton(onClick = { onCropScale(1.1) }) {
            Text(stringResource(EditorR.string.editor_crop_grow), color = EditorPalette.textPrimary)
        }
    }
}

/** クロップ移動の1操作あたりの正規化増分（キャンバス比 2%）。 */
private const val CROP_NUDGE = 0.02

/** 角度の表示文字列（1桁小数・整数は整数・ロケール非依存）。 */
private fun formatAngleText(deg: Double): String {
    val rounded = (deg * 10.0).roundToInt() / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format(java.util.Locale.ROOT, "%.1f", rounded)
    }
}

private fun cropSummary(g: EditRecipe.GeometryParams, noneLabel: String): String =
    if (g.cropNorm == EditRecipe.GeometryParams().cropNorm) {
        noneLabel
    } else {
        g.cropNorm.joinToString(", ") { (it * 100).roundToInt().toString() + "%" }
    }

/** 角度（微調整）の数値入力ダイアログ。範囲は -45..45。 */
@Composable
private fun GeometryAngleDialog(
    current: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(formatAngleText(current)) }
    var errorText by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(EditorR.string.editor_geometry_angle_title),
                color = EditorPalette.textPrimary,
            )
        },
        text = {
            Column {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (errorText != null) {
                    Text(
                        text = errorText!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = EditorPalette.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    text = stringResource(EditorR.string.editor_number_hint, -45, 45),
                    style = MaterialTheme.typography.labelSmall,
                    color = EditorPalette.textSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            val msgEmpty = stringResource(EditorR.string.editor_number_error_empty)
            TextButton(
                onClick = {
                    val parsed = text.trim().toDoubleOrNull()
                    if (parsed == null || !parsed.isFinite()) {
                        errorText = msgEmpty
                    } else {
                        onConfirm(parsed.coerceIn(-45.0, 45.0))
                    }
                },
            ) { Text(stringResource(EditorR.string.editor_button_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(EditorR.string.editor_button_cancel)) }
        },
    )
}

// --------------------------------------------------------------- 局部補正タブ（マスク・PR-29c）

/**
 * 局部補正（マスク）パネル。overlay 編集（ハンドル/ブラシ）はプレビュー側の [MaskOverlay]。
 *
 * - 追加は 3 種（直線/円形/ブラシ）で上限 [MaskSpecs.MAX_MASKS]。追加・削除は
 *   それぞれ 1 履歴イベント（VM が確定）。
 * - 選択中マスクの feather、radial は角度、brush は半径（ツール設定）とストローク数を表示。
 * - adjustments は 5 種（露出/コントラスト/彩度/温度/色合い）。表示値は -100..100 系・
 *   温度は絶対 K で、レシピ値への変換は [MaskAdjustments]（VM 側で同じ換算）。
 */
@Composable
private fun LocalContent(
    state: EditorViewModel.EditorState,
    actions: EditorMaskActions,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Text(
            text = stringResource(EditorR.string.editor_local_title),
            style = MaterialTheme.typography.titleSmall,
            color = EditorPalette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(EditorR.string.editor_mask_list_header, state.masks.size, MaskSpecs.MAX_MASKS),
            style = MaterialTheme.typography.labelSmall,
            color = EditorPalette.textSecondary,
        )
    }
    Text(
        text = stringResource(EditorR.string.editor_local_help),
        style = MaterialTheme.typography.labelSmall,
        color = EditorPalette.textSecondary,
    )

    val canAdd = state.masks.size < MaskSpecs.MAX_MASKS
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        TextButton(onClick = { actions.onAdd(MaskSpecs.TYPE_LINEAR) }, enabled = canAdd) {
            Text(
                text = stringResource(EditorR.string.editor_mask_add_linear),
                color = if (canAdd) EditorPalette.textPrimary else EditorPalette.textSecondary,
            )
        }
        TextButton(onClick = { actions.onAdd(MaskSpecs.TYPE_RADIAL) }, enabled = canAdd) {
            Text(
                text = stringResource(EditorR.string.editor_mask_add_radial),
                color = if (canAdd) EditorPalette.textPrimary else EditorPalette.textSecondary,
            )
        }
        TextButton(onClick = { actions.onAdd(MaskSpecs.TYPE_BRUSH) }, enabled = canAdd) {
            Text(
                text = stringResource(EditorR.string.editor_mask_add_brush),
                color = if (canAdd) EditorPalette.textPrimary else EditorPalette.textSecondary,
            )
        }
    }
    if (!canAdd) {
        Text(
            text = stringResource(EditorR.string.editor_mask_full, MaskSpecs.MAX_MASKS),
            style = MaterialTheme.typography.labelSmall,
            color = EditorPalette.accent,
        )
    }

    if (state.masks.isEmpty()) {
        Text(
            text = stringResource(EditorR.string.editor_mask_empty),
            style = MaterialTheme.typography.labelSmall,
            color = EditorPalette.textSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
        return
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        state.masks.forEachIndexed { index, node ->
            val selected = node.maskId == state.selectedMaskId
            Text(
                text = stringResource(EditorR.string.editor_mask_chip, maskTypeLabel(node.maskType), index + 1),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) EditorPalette.textPrimary else EditorPalette.textSecondary,
                modifier = Modifier
                    .background(if (selected) EditorPalette.panelRaised else EditorPalette.panel)
                    .border(1.dp, EditorPalette.line)
                    .clickable { actions.onSelect(node.maskId) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }

    val selected = state.masks.firstOrNull { it.maskId == state.selectedMaskId }
    if (selected == null) {
        Text(
            text = stringResource(EditorR.string.editor_mask_none_selected),
            style = MaterialTheme.typography.labelSmall,
            color = EditorPalette.textSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
        return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Text(
            text = stringResource(EditorR.string.editor_mask_selected, maskTypeLabel(selected.maskType)),
            style = MaterialTheme.typography.bodySmall,
            color = EditorPalette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { actions.onRemove(selected.maskId) }) {
            Text(stringResource(EditorR.string.editor_mask_delete), color = EditorPalette.accent)
        }
    }

    // ---- 形状パラメータ（feather / 角度 / ブラシ半径）----
    val featherDefault = MaskSpecs.defaultParams(selected.maskType)?.get(MaskSpecs.KEY_FEATHER)
        ?: MaskSpecs.MIN_FEATHER
    val feather = MaskSlider.feather(
        selected.parameters[MaskSpecs.KEY_FEATHER] ?: featherDefault,
        featherDefault,
    )
    MaskSliderRow(
        label = stringResource(EditorR.string.editor_mask_feather),
        value = feather,
        valueText = formatFixed(feather, 2),
        range = MaskSpecs.MIN_FEATHER..MaskSpecs.MAX_FEATHER,
        onPreview = { actions.onParamPreview(selected.maskId, MaskSpecs.KEY_FEATHER, it) },
        onCommit = { actions.onParamCommit(selected.maskId, MaskSpecs.KEY_FEATHER, it) },
    )
    if (selected.maskType == MaskSpecs.TYPE_RADIAL) {
        val angleDisplay = MaskSlider.angleDisplay(
            selected.parameters[MaskSpecs.KEY_ANGLE_DEG] ?: MaskSpecs.DEFAULT_ANGLE_DEG,
        )
        MaskSliderRow(
            label = stringResource(EditorR.string.editor_mask_angle),
            value = angleDisplay,
            valueText = stringResource(EditorR.string.editor_mask_degrees, formatFixed(angleDisplay, 1)),
            range = -180.0..180.0,
            onPreview = { actions.onParamPreview(selected.maskId, MaskSpecs.KEY_ANGLE_DEG, it) },
            onCommit = { actions.onParamCommit(selected.maskId, MaskSpecs.KEY_ANGLE_DEG, it) },
        )
    }
    if (selected.maskType == MaskSpecs.TYPE_BRUSH) {
        MaskSliderRow(
            label = stringResource(EditorR.string.editor_mask_brush_radius),
            value = state.brushRadius,
            valueText = formatFixed(state.brushRadius, 2),
            range = MaskDraft.MIN_BRUSH_RADIUS..MaskSpecs.MAX_BRUSH_RADIUS,
            onPreview = actions.onBrushRadius,
            onCommit = actions.onBrushRadius,
        )
        Text(
            text = stringResource(
                EditorR.string.editor_mask_strokes,
                selected.brushStrokes.size,
                MaskSpecs.MAX_BRUSH_STROKES,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = EditorPalette.textSecondary,
        )
    }

    // ---- adjustments（マスク内の補正のみ）----
    Text(
        text = stringResource(EditorR.string.editor_mask_adjust_header),
        style = MaterialTheme.typography.bodySmall,
        color = EditorPalette.textPrimary,
        modifier = Modifier.padding(top = 8.dp),
    )
    MaskAdjustments.SPECS.forEach { spec ->
        val recipeValue = selected.adjustments[spec.path]
        val display = recipeValue?.let { MaskAdjustments.toDisplay(spec, it) } ?: spec.neutralDisplay
        MaskSliderRow(
            label = maskAdjustmentLabel(spec.path),
            value = display,
            valueText = if (recipeValue == null) {
                stringResource(EditorR.string.editor_mask_value_unset)
            } else {
                MaskAdjustments.format(spec, display)
            },
            range = spec.displayMin..spec.displayMax,
            onPreview = { actions.onAdjustPreview(selected.maskId, spec.path, it) },
            onCommit = { actions.onAdjustCommit(selected.maskId, spec.path, it) },
        )
    }
    TextButton(onClick = { actions.onResetAdjustments(selected.maskId) }) {
        Text(stringResource(EditorR.string.editor_mask_reset), color = EditorPalette.accent)
    }
}

/** マスク種別の表示ラベル（res 正本）。未知種別は生の型 ID（永続形式の安定 ID）を出す。 */
@Composable
private fun maskTypeLabel(maskType: String): String = when (maskType) {
    MaskSpecs.TYPE_LINEAR -> stringResource(EditorR.string.editor_mask_type_linear)
    MaskSpecs.TYPE_RADIAL -> stringResource(EditorR.string.editor_mask_type_radial)
    MaskSpecs.TYPE_BRUSH -> stringResource(EditorR.string.editor_mask_type_brush)
    else -> maskType
}

/** adjustments の表示ラベル。既存 `editor_adjust_*`（[AdjustmentLabels]）を可能な限り再利用する。 */
@Composable
private fun maskAdjustmentLabel(path: String): String {
    if (path == MaskAdjustments.PATH_TEMPERATURE) {
        return stringResource(EditorR.string.editor_mask_adjust_temperature)
    }
    val id = AdjustmentId.byPath(path)
    val label = if (id != null) stringResource(AdjustmentLabels.labelRes(id)) else path
    return if (id?.unit == AdjustmentId.Unit.EV) {
        stringResource(EditorR.string.editor_slider_ev, label)
    } else {
        label
    }
}

/** マスク用スライダー行（ラベル・表示値・範囲・preview/commit の 2 経路）。 */
@Composable
private fun MaskSliderRow(
    label: String,
    value: Double,
    valueText: String,
    range: ClosedFloatingPointRange<Double>,
    onPreview: (Double) -> Unit,
    onCommit: (Double) -> Unit,
) {
    val clamped = value.coerceIn(range.start, range.endInclusive)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = EditorPalette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                color = EditorPalette.textSecondary,
            )
        }
        Slider(
            value = clamped.toFloat(),
            onValueChange = { f -> onPreview(f.toDouble()) },
            onValueChangeFinished = { onCommit(clamped) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = EditorPalette.accent,
                activeTrackColor = EditorPalette.accent,
                inactiveTrackColor = EditorPalette.line,
            ),
        )
    }
}

// --------------------------------------------------------------- スライダー行・数値入力

@Composable
private fun SliderRow(
    id: AdjustmentId,
    value: Double,
    onPreview: (AdjustmentId, Double) -> Unit,
    onCommit: (AdjustmentId, Double) -> Unit,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = sliderLabel(id),
                style = MaterialTheme.typography.bodySmall,
                color = EditorPalette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { dialogOpen = true },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp,
                    vertical = 0.dp,
                ),
            ) {
                Text(
                    text = id.formatValue(value),
                    style = MaterialTheme.typography.bodySmall,
                    color = EditorPalette.textSecondary,
                )
            }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { f -> onPreview(id, f.toDouble()) },
            onValueChangeFinished = { onCommit(id, value) },
            valueRange = id.min.toFloat()..id.max.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = EditorPalette.accent,
                activeTrackColor = EditorPalette.accent,
                inactiveTrackColor = EditorPalette.line,
            ),
        )
    }
    if (dialogOpen) {
        AdjustmentNumberDialog(
            id = id,
            current = value,
            onDismiss = { dialogOpen = false },
            onConfirm = { parsed ->
                dialogOpen = false
                onCommit(id, parsed)
            },
        )
    }
}

/** 数値入力ボックスダイアログ（§4.3: スライダーは正確な数値編集も提供する）。 */
@Composable
private fun AdjustmentNumberDialog(
    id: AdjustmentId,
    current: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(id.formatValue(current)) }
    var errorText by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    EditorR.string.editor_number_dialog_title,
                    stringResource(AdjustmentLabels.labelRes(id)),
                ),
                color = EditorPalette.textPrimary,
            )
        },
        text = {
            Column {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (errorText != null) {
                    Text(
                        text = errorText!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = EditorPalette.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    text = stringResource(EditorR.string.editor_number_hint, id.min.toInt(), id.max.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = EditorPalette.textSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            // 文案は compose コンテキストで先に解決してから onClick へ渡す（ラムダ内は非 composabe）。
            val msgEmpty = stringResource(EditorR.string.editor_number_error_empty)
            val msgInvalid = stringResource(EditorR.string.editor_number_error_invalid)
            TextButton(
                onClick = {
                    val parsed = text.trim().toDoubleOrNull()
                    if (parsed == null) {
                        errorText = msgEmpty
                    } else {
                        val clamped = id.validClamped(parsed)
                        if (clamped == null) {
                            errorText = msgInvalid
                        } else {
                            onConfirm(clamped)
                        }
                    }
                },
            ) { Text(stringResource(EditorR.string.editor_button_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(EditorR.string.editor_button_cancel)) }
        },
    )
}

/** スライダーの表示ラベル（文言は res 正本・単位接尾のみスキーマ Unit 列挙に由来）。 */
@Composable
private fun sliderLabel(id: AdjustmentId): String {
    val label = stringResource(AdjustmentLabels.labelRes(id))
    return when (id.unit) {
        AdjustmentId.Unit.EV -> stringResource(EditorR.string.editor_slider_ev, label)
        AdjustmentId.Unit.TEMP_RELATIVE -> stringResource(EditorR.string.editor_slider_temp_relative, label)
        else -> label
    }
}

// --------------------------------------------------------------- 下部バー

@Composable
private fun EditorBottomBar(
    state: EditorViewModel.EditorState,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBeforeChanged: (Boolean) -> Unit,
    /** 書き出し遷移（assetId を渡す）。 */
    onExport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onUndo, enabled = state.canUndo && !state.dragging) {
            Text(
                stringResource(EditorR.string.editor_undo),
                color = if (state.canUndo) EditorPalette.textPrimary else EditorPalette.textSecondary,
            )
        }
        TextButton(onClick = onRedo, enabled = state.canRedo && !state.dragging) {
            Text(
                stringResource(EditorR.string.editor_redo),
                color = if (state.canRedo) EditorPalette.textPrimary else EditorPalette.textSecondary,
            )
        }
        Text(
            text = stringResource(EditorR.string.editor_history_count, state.history.size),
            style = MaterialTheme.typography.labelSmall,
            color = EditorPalette.textSecondary,
            modifier = Modifier.weight(1f),
        )
        // 長押し Before/After（§4.3）。押下中のみ既定値プレビューに切替える。
        TextButton(
            onClick = {},
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onBeforeChanged(true)
                        tryAwaitRelease()
                        onBeforeChanged(false)
                    },
                )
            },
        ) {
            Text(stringResource(EditorR.string.editor_button_before_after), color = EditorPalette.textPrimary)
        }
        Button(
            onClick = { onExport(state.assetId) },
            colors = ButtonDefaults.buttonColors(
                containerColor = EditorPalette.accent,
                contentColor = EditorPalette.bg,
            ),
        ) {
            Text(stringResource(EditorR.string.editor_export))
        }
    }
}

// --------------------------------------------------------------- 適応レイアウト（F3）

/** F3（PR-30）: プレビューとツールパネルを横並びにする最小幅（material3 の expanded 相当）。 */
private val WIDE_LAYOUT_MIN_WIDTH = 840.dp

/** F3（PR-30）: 横並び時のツールパネル幅（スライダー名+値が折り返さず収まる最小幅）。 */
private val TOOL_PANEL_WIDE_WIDTH = 360.dp
