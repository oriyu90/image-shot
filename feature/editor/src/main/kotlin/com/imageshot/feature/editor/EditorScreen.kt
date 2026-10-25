package com.imageshot.feature.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import com.imageshot.core.render.RecipeCurve
import kotlin.math.roundToInt

/**
 * 編集画面（01_製品仕様書.md §4.3 視覚仕様 / §10 U02 U03）。
 *
 * - 基調は暗色ニュートラル（純黒でない低反射背景）、強調色は操作対象1箇所のみ。
 * - 中央プレビュー: pinch ズーム（1x..8x）+ 二本指パン。長押し Before/After は下部の
 *   専用ホールドボタンで行う（ピンチ/パンとタップ長押しのジェスチャ競合を避ける）。
 * - 下部ツールタブ: 基本調整 / カーブ（master のみ） / HSL（8系統） / ジオメトリ
 *   （90°回転・反転・プリセット比クロップ）は実装。ディテール/局部補正は未実装として明示表示。
 *   R/G/B チャンネル別カーブ・自由角度回転・自由クロップ・透視/レンズ補正は未実装（各タブ内で明示）。
 * - スライダー操作はスキーマ（AdjustmentId）から生成。ドラッグ反映は即時、
 *   離した時点で1件確定（onDragEnd → EditStore.commit）。カーブは指を離した時点で
 *   絶対状態イベント確定、ジオメトリはボタン操作ごとに1件確定。
 * - Undo/Redo/履歴一覧: DB 正本（EditStoreState）の表示。
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
    onGeometryRotate: (Int) -> Unit,
    onGeometryFlip: (Boolean) -> Unit,
    onGeometryCrop: (Double?) -> Unit,
    onGeometryResetCrop: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBeforeChanged: (Boolean) -> Unit,
    /** 書き出し画面へ遷移。引数は assetId（documentId は EditStore が assetId から解決）。 */
    onExport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(EditorPalette.bg),
    ) {
        EditorTopBar(state)
        PreviewPane(
            images = images,
            showBefore = state.showBefore,
            renderFailure = state.renderFailure,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        ToolPanel(
            state = state,
            onSliderPreview = onSliderPreview,
            onSliderCommit = onSliderCommit,
            onCurvePreview = onCurvePreview,
            onCurveCommit = onCurveCommit,
            onCurveReset = onCurveReset,
            onGeometryRotate = onGeometryRotate,
            onGeometryFlip = onGeometryFlip,
            onGeometryCrop = onGeometryCrop,
            onGeometryResetCrop = onGeometryResetCrop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .background(EditorPalette.panel),
        )
        EditorBottomBar(
            state = state,
            onUndo = onUndo,
            onRedo = onRedo,
            onBeforeChanged = onBeforeChanged,
            onExport = onExport,
            modifier = Modifier
                .fillMaxWidth()
                .background(EditorPalette.panel),
        )
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

// --------------------------------------------------------------- 調整パネル（ツールタブ）

/** ツールタブ。実装済みは [BASIC] [CURVE] [HSL] [GEOMETRY] のみ（ADR-014 に基づく明示）。 */
private enum class EditorToolTab(val labelRes: Int, val hintRes: Int?, val implemented: Boolean) {
    BASIC(EditorR.string.editor_tab_basic, null, true),
    CURVE(EditorR.string.editor_tab_curve, EditorR.string.editor_tab_hint_curve, true),
    HSL(EditorR.string.editor_tab_hsl, null, true),
    GEOMETRY(EditorR.string.editor_tab_geometry, EditorR.string.editor_tab_hint_geometry, true),
    DETAIL(EditorR.string.editor_tab_detail, EditorR.string.editor_tab_hint_detail, false),
    LOCAL(EditorR.string.editor_tab_local, EditorR.string.editor_tab_hint_local, false),
}

@Composable
private fun ToolPanel(
    state: EditorViewModel.EditorState,
    onSliderPreview: (AdjustmentId, Double) -> Unit,
    onSliderCommit: (AdjustmentId, Double) -> Unit,
    onCurvePreview: (List<List<Double>>) -> Unit,
    onCurveCommit: (List<List<Double>>) -> Unit,
    onCurveReset: () -> Unit,
    onGeometryRotate: (Int) -> Unit,
    onGeometryFlip: (Boolean) -> Unit,
    onGeometryCrop: (Double?) -> Unit,
    onGeometryResetCrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(EditorToolTab.BASIC) }
    var historyOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // タブ行（未実装タブも選択可能 — 「未実装」と明示表示するため。成功装いの表示はしない）。
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            EditorToolTab.entries.forEach { tab ->
                ToolTab(
                    label = stringResource(tab.labelRes),
                    hint = tab.hintRes?.let { stringResource(it) },
                    implemented = tab.implemented,
                    selected = selected == tab,
                    onClick = { selected = tab },
                )
            }
        }
        when (selected) {
            EditorToolTab.BASIC -> BasicAdjustmentsContent(state, onSliderPreview, onSliderCommit)
            EditorToolTab.CURVE -> MasterCurveContent(state, onCurvePreview, onCurveCommit, onCurveReset)
            EditorToolTab.HSL -> HslContent(state, onSliderPreview, onSliderCommit)
            EditorToolTab.GEOMETRY -> GeometryContent(state, onGeometryRotate, onGeometryFlip, onGeometryCrop, onGeometryResetCrop)
            EditorToolTab.DETAIL -> UnimplementedContent(
                titleRes = EditorR.string.editor_detail_title,
                messageRes = EditorR.string.editor_detail_notice,
            )
            EditorToolTab.LOCAL -> UnimplementedContent(
                titleRes = EditorR.string.editor_local_title,
                messageRes = EditorR.string.editor_local_notice,
            )
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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

// --------------------------------------------------------------- カーブタブ（master のみ）

/**
 * master（RGB 共通）トーンカーブの操作面（§5.2 の M1 実装範囲）。
 * - ドラッグ: 制御点移動、タップ: 追加（最大 16 点）、ダブルタップ: 削除（最小 2 点）。
 * - 指を離した時点で正規化+絶対状態確定（EditStore.commitCurve）。
 * - R/G/B チャンネル別カーブは UI 未実装（レンダリングも未適用 → 警告表示）。
 */
@Composable
private fun MasterCurveContent(
    state: EditorViewModel.EditorState,
    onCurvePreview: (List<List<Double>>) -> Unit,
    onCurveCommit: (List<List<Double>>) -> Unit,
    onCurveReset: () -> Unit,
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
        // 強調色はこのタブの操作対象（リセット）のみ。
        TextButton(onClick = onCurveReset) {
            Text(stringResource(EditorR.string.editor_curve_reset), color = EditorPalette.accent)
        }
    }
    Text(
        text = stringResource(EditorR.string.editor_curve_help),
        style = MaterialTheme.typography.labelSmall,
        color = EditorPalette.textSecondary,
    ) 

    // ドラッグ中はローカルの点列を表示の権原とする（正規化による並べ替えで
    // 選択点が入れ替わる見た目のジャンプを避ける）。確定は正規化済みで行う。
    var dragPoints by remember { mutableStateOf<List<List<Double>>?>(null) }
    var dragIndex by remember { mutableStateOf(-1) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val displayed: List<List<Double>> = dragPoints ?: state.curvePoints

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
    Text(
        text = stringResource(EditorR.string.editor_curve_channels_hint),
        style = MaterialTheme.typography.labelSmall,
        color = EditorPalette.textSecondary,
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
 * ジオメトリ（§5.2 M1 範囲）: 90°単位回転 / 水平・垂直反転 / アスペクト比プリセットの
 * 中央寄せ最大矩形クロップ。ボタン操作ごとに絶対状態を1件確定。自由角度・自由クロップ
 * （エッジドラッグ）・透視/レンズ補正は未実装として明示。
 */
@Composable
private fun GeometryContent(
    state: EditorViewModel.EditorState,
    onRotate: (Int) -> Unit,
    onFlip: (Boolean) -> Unit,
    onCrop: (Double?) -> Unit,
    onCropReset: () -> Unit,
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
            state.geometry.angleDeg.toInt(),
            cropSummary(state.geometry, stringResource(EditorR.string.editor_crop_none)),
        ),
        style = MaterialTheme.typography.labelSmall,
        color = EditorPalette.textSecondary,
    )
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
    Text(
        text = stringResource(EditorR.string.editor_crop_unimplemented),
        style = MaterialTheme.typography.labelSmall,
        color = EditorPalette.textSecondary,
    )
}

private fun cropSummary(g: EditRecipe.GeometryParams, noneLabel: String): String =
    if (g.cropNorm == EditRecipe.GeometryParams().cropNorm) {
        noneLabel
    } else {
        g.cropNorm.joinToString(", ") { (it * 100).roundToInt().toString() + "%" }
    }

// --------------------------------------------------------------- 未実装タブ表示

/** タブ自体が未実装の場合の明示表示（ADR-014: 無通知の既定値反映を禁止）。 */
@Composable
private fun UnimplementedContent(@StringRes titleRes: Int, @StringRes messageRes: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = EditorPalette.textPrimary,
        )
        Text(
            text = stringResource(EditorR.string.editor_not_implemented),
            style = MaterialTheme.typography.labelMedium,
            color = EditorPalette.accent,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.labelSmall,
            color = EditorPalette.textSecondary,
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
