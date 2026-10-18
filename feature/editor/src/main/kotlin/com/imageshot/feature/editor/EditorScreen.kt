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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.imageshot.core.model.AdjustmentId
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
                text = state.asset?.displayName ?: "写真を読み込み中…",
                style = MaterialTheme.typography.titleSmall,
                color = EditorPalette.textPrimary,
            )
            Text(
                text = "改訂 r${state.revision}",
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
        EditorViewModel.SaveState.SAVING -> "保存中…" to EditorPalette.textSecondary
        EditorViewModel.SaveState.SAVED -> "保存済み" to EditorPalette.textSecondary
        EditorViewModel.SaveState.FAILED -> "保存に失敗" to EditorPalette.error
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
                contentDescription = if (showBefore) "Before（既定値）プレビュー" else "編集プレビュー",
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
                text = "プレビューを生成中…",
                color = EditorPalette.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (showBefore && images.before != null) {
            Text(
                text = "Before（既定値）",
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
                    text = "描画に失敗しました",
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
private enum class EditorToolTab(val label: String, val hint: String?, val implemented: Boolean) {
    BASIC("基本調整", null, true),
    CURVE("カーブ", "R/G/B チャンネル別は未実装（master のみ）", true),
    HSL("HSL", null, true),
    GEOMETRY("ジオメトリ", "自由角度・自由クロップ・透視/レンズは未実装", true),
    DETAIL("ディテール", "未実装（M2 予約）", false),
    LOCAL("局部補正", "未実装（M3 予約）", false),
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
                    label = tab.label,
                    hint = tab.hint,
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
                title = "ディテール（シャープ/ノイズ低減）",
                message = "M2 予約。現在のビルドには含まれず、レシピに値があっても適用されません（警告表示）。",
            )
            EditorToolTab.LOCAL -> UnimplementedContent(
                title = "局部補正（マスク）",
                message = "M3 予約。現在のビルドには含まれず、レシピにマスクがあっても適用されません（警告表示）。",
            )
        }
        TextButton(
            onClick = {
                historyOpen = !historyOpen
            },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                text = if (historyOpen) "履歴を閉じる（${state.history.size}件）" else "履歴を表示（${state.history.size}件）",
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
                        text = "確定済みの操作はまだありません",
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
                text = "未実装",
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
            text = "基本調整",
            style = MaterialTheme.typography.titleSmall,
            color = EditorPalette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (state.dragging) {
            // §4.3: 動作の重い場合の品質切替を明示（画質劣化は保存に反映しない）。
            Text(
                text = "ドラッグ中: 低解像度 / 離す: 高解像度",
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
            text = "カーブ（master）",
            style = MaterialTheme.typography.titleSmall,
            color = EditorPalette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        // 強調色はこのタブの操作対象（リセット）のみ。
        TextButton(onClick = onCurveReset) {
            Text("中立へ戻す", color = EditorPalette.accent)
        }
    }
    Text(
        text = "ドラッグ: 点を移動 / タップ: 点を追加 / ダブルタップ: 点を削除",
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
        text = "制御点 ${displayed.size}/${EditNodeSpecs.CURVE_MAX_POINTS}（最小 ${EditNodeSpecs.CURVE_MIN_POINTS}）",
        style = MaterialTheme.typography.labelSmall,
        color = EditorPalette.textSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        text = "R/G/B チャンネル別カーブは未実装（master のみ。チャンネル指定はレシピに値があれば警告表示）",
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
    var selectedBand by remember { mutableStateOf(EditNodeSpecs.HSL_BANDS.first().first) }
    Text(
        text = "HSL カラーミキサー（標準8系統）",
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
        EditNodeSpecs.HSL_BANDS.forEach { (bandId, label) ->
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
    val bandBindings = EditNodeSpecs.HSL_PARAMS.map { (paramId, _) ->
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
        text = "色相 ±100 = ±30° / 彩度・明るさは −100..100（明るさは線形光で 2^v 増幅）",
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
        text = "ジオメトリ",
        style = MaterialTheme.typography.titleSmall,
        color = EditorPalette.textPrimary,
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { onRotate(-1) }) { Text("⟲ 90°左回転", color = EditorPalette.textPrimary) }
        TextButton(onClick = { onRotate(1) }) { Text("⟳ 90°右回転", color = EditorPalette.textPrimary) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { onFlip(true) }) {
            Text(
                text = if (state.geometry.mirrorX) "水平反転: ON" else "水平反転: OFF",
                color = if (state.geometry.mirrorX) EditorPalette.accent else EditorPalette.textPrimary,
            )
        }
        TextButton(onClick = { onFlip(false) }) {
            Text(
                text = if (state.geometry.mirrorY) "垂直反転: ON" else "垂直反転: OFF",
                color = if (state.geometry.mirrorY) EditorPalette.accent else EditorPalette.textPrimary,
            )
        }
    }
    Text(
        text = "現在: 回転${state.geometry.angleDeg.toInt()}° / クロップ ${cropSummary(state.geometry)}",
        style = MaterialTheme.typography.labelSmall,
        color = EditorPalette.textSecondary,
    )
    Text(
        text = "クロップ（中央寄せ・プリセット比。クロップ縦横比は 90/270°回転時に自動で入れ替える）",
        style = MaterialTheme.typography.bodySmall,
        color = EditorPalette.textPrimary,
        modifier = Modifier.padding(top = 8.dp),
    )
    // プリセット（原寸/1:1/3:2/4:3/16:9）。操作ごとに確定。プレビューはレンダリング経由で即反映。
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onCropReset) {
            Text("原寸", color = EditorPalette.textPrimary)
        }
        listOf("1:1" to 1.0, "3:2" to 1.5, "4:3" to 4.0 / 3.0, "16:9" to 16.0 / 9.0).forEach { (label, aspect) ->
            TextButton(onClick = { onCrop(aspect) }) {
                Text(label, color = EditorPalette.textPrimary)
            }
        }
    }
    Text(
        text = "自由角度回転・自由クロップ（エッジドラッグ）・透視補正/レンズ補正は未実装（警告表示のまま）",
        style = MaterialTheme.typography.labelSmall,
        color = EditorPalette.textSecondary,
    )
}

private fun cropSummary(g: EditRecipe.GeometryParams): String =
    if (g.cropNorm == EditRecipe.GeometryParams().cropNorm) {
        "なし（原寸）"
    } else {
        g.cropNorm.joinToString(", ") { (it * 100).roundToInt().toString() + "%" }
    }

// --------------------------------------------------------------- 未実装タブ表示

/** タブ自体が未実装の場合の明示表示（ADR-014: 無通知の既定値反映を禁止）。 */
@Composable
private fun UnimplementedContent(title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = EditorPalette.textPrimary,
        )
        Text(
            text = "未実装",
            style = MaterialTheme.typography.labelMedium,
            color = EditorPalette.accent,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = message,
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
        title = { Text(text = "${id.label} を数値で指定", color = EditorPalette.textPrimary) },
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
                    text = "許容範囲: ${id.min.toInt()} .. ${id.max.toInt()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = EditorPalette.textSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = text.trim().toDoubleOrNull()
                    if (parsed == null) {
                        errorText = "数値を入力してください"
                    } else {
                        val clamped = id.validClamped(parsed)
                        if (clamped == null) {
                            errorText = "値が不正です"
                        } else {
                            onConfirm(clamped)
                        }
                    }
                },
            ) { Text("確定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

/** スライダーの表示ラベル。単位はスキーマから（UI側の定義重複はしない）。 */
private fun sliderLabel(id: AdjustmentId): String =
    when (id.unit) {
        AdjustmentId.Unit.EV -> "${id.label} (EV)"
        AdjustmentId.Unit.TEMP_RELATIVE -> "${id.label} −100..100"
        else -> id.label
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
            Text("取り消す", color = if (state.canUndo) EditorPalette.textPrimary else EditorPalette.textSecondary)
        }
        TextButton(onClick = onRedo, enabled = state.canRedo && !state.dragging) {
            Text("やり直す", color = if (state.canRedo) EditorPalette.textPrimary else EditorPalette.textSecondary)
        }
        Text(
            text = "履歴 ${state.history.size}件",
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
            Text("Before/After", color = EditorPalette.textPrimary)
        }
        Button(
            onClick = { onExport(state.assetId) },
            colors = ButtonDefaults.buttonColors(
                containerColor = EditorPalette.accent,
                contentColor = EditorPalette.bg,
            ),
        ) {
            Text("書き出し…")
        }
    }
}
