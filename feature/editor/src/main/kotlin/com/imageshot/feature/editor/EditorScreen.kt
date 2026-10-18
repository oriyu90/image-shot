package com.imageshot.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.imageshot.core.model.AdjustmentId

/**
 * 編集画面（01_製品仕様書.md §4.3 視覚仕様 / §10 U02 U03）。
 *
 * - 基調は暗色ニュートラル（純黒でない低反射背景）、強調色は操作対象1箇所のみ。
 * - 中央プレビュー: pinch ズーム（1x..8x）+ 二本指パン。長押し Before/After は下部の
 *   専用ホールドボタンで行う（ピンチ/パンとタップ長押しのジェスチャ競合を避ける）。
 * - 下部ツールタブ: 「基本調整」のみ有効。カーブ/HSL/局部補正は未実装として明示表示。
 * - スライダー操作はスキーマ（AdjustmentId）から生成。ドラッグ反映は即時、
 *   離した時点で1件確定（onDragEnd → EditStore.commit）。
 * - Undo/Redo/履歴一覧: DB 正本（EditStoreState）の表示。
 *
 * 配線（AppContainer / MainActivity は app モジュール担当で未適用 — 別PRで適用すること）:
 *   AppContainer:
 *     val editStore = EditStore(RoomEditStoreBackend(database))
 *     val renderBitmaps = RenderBitmapStore()
 *     val renderer = SimpleBitmapPhotoRenderer(context, resolver, renderBitmaps)
 *   MainActivity (editor/{assetId}):
 *     EditorViewModel(assetId, container.assetDao, container.renderer,
 *                     container.renderBitmaps, container.editStore)
 *     EditorScreen(editorState, editorImages, vm::onSliderPreview, vm::onSliderCommit,
 *                  vm::undo, vm::redo, vm::setBeforeVisible, onExport)
 */
@Composable
fun EditorScreen(
    state: EditorViewModel.EditorState,
    images: EditorViewModel.EditorImages,
    onSliderPreview: (AdjustmentId, Double) -> Unit,
    onSliderCommit: (AdjustmentId, Double) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBeforeChanged: (Boolean) -> Unit,
    onExport: () -> Unit,
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
        BasicAdjustmentsPanel(
            state = state,
            onSliderPreview = onSliderPreview,
            onSliderCommit = onSliderCommit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 340.dp)
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

// --------------------------------------------------------------- 調整パネル

@Composable
private fun BasicAdjustmentsPanel(
    state: EditorViewModel.EditorState,
    onSliderPreview: (AdjustmentId, Double) -> Unit,
    onSliderCommit: (AdjustmentId, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var historyOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
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
        ToolTabRow()
        state.sliders.forEach { bind ->
            SliderRow(
                id = bind.id,
                value = bind.value,
                onPreview = onSliderPreview,
                onCommit = onSliderCommit,
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
private fun ToolTabRow() {
    // カーブ/HSL/局部補正は未実装（§5.2/§5.3 は後続 Sprint）。成功装いの表示はしない。
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        ToolTab(label = "基本調整", enabled = true, selected = true)
        ToolTab(label = "カーブ", enabled = false, selected = false)
        ToolTab(label = "HSL", enabled = false, selected = false)
        ToolTab(label = "局部補正", enabled = false, selected = false)
    }
}

@Composable
private fun ToolTab(label: String, enabled: Boolean, selected: Boolean) {
    val bg = when {
        selected -> EditorPalette.panelRaised
        enabled -> EditorPalette.panel
        else -> EditorPalette.panel
    }
    Column(
        modifier = Modifier
            .background(bg)
            .border(1.dp, EditorPalette.line)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled || selected) EditorPalette.textPrimary else EditorPalette.textSecondary,
        )
        if (!enabled) {
            Text(
                text = "未実装",
                style = MaterialTheme.typography.labelSmall,
                color = EditorPalette.textSecondary,
            )
        }
    }
}

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

// --------------------------------------------------------------- 下部バー

/** スライダーの表示ラベル。単位はスキーマから（UI側の定義重複はしない）。 */
private fun sliderLabel(id: AdjustmentId): String =
    when (id.unit) {
        AdjustmentId.Unit.EV -> "${id.label} (EV)"
        AdjustmentId.Unit.TEMP_RELATIVE -> "${id.label} −100..100"
        else -> id.label
    }

@Composable
private fun EditorBottomBar(
    state: EditorViewModel.EditorState,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBeforeChanged: (Boolean) -> Unit,
    onExport: () -> Unit,
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
            onClick = onExport,
            colors = ButtonDefaults.buttonColors(
                containerColor = EditorPalette.accent,
                contentColor = EditorPalette.bg,
            ),
        ) {
            Text("書き出し…")
        }
    }
}
