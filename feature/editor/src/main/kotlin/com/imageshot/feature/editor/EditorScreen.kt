package com.imageshot.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.imageshot.core.model.Asset

/**
 * 編集画面（初期骨格）。中央プレビュー/ヒストグラム/PhotoSurface は Sprint C。
 * - 編集面は低反射の暗色背景 #1A1A1A 系（純黒は使わない、01_製品仕様書.md §4.3）。
 */
@Composable
fun EditorScreen(
    asset: Asset?,
    state: EditorViewModel.EditorState,
    onPreviewChanged: (EditorViewModel.SliderValues) -> Unit,
    onCommitted: (EditorViewModel.SliderValues) -> Unit,
    onUndo: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = asset?.displayName ?: "asset not found",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(8.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(Color(0xFF1A1A1A)),
            contentAlignment = Alignment.Center,
        ) {
            // TODO(Sprint C): PhotoSurface (TextureView/SurfaceView) は S-01 spike 結果後に選定。
            Text(
                "プレビュー面 (renderer warnings: ${state.renderWarnings.size})",
                color = Color(0xFF9A9A9A),
            )
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.renderFailure?.let {
                Text("描画失敗: $it", color = MaterialTheme.colorScheme.error)
            }
            SliderRow(
                label = "露出 (EV)",
                value = state.sliders.exposureEv,
                onContentsChanged = { v -> onPreviewChanged(
                    EditorViewModel.SliderValues(v, state.sliders.contrast, state.sliders.saturation),
                ) },
                onCommit = { v -> onCommitted(
                    EditorViewModel.SliderValues(v, state.sliders.contrast, state.sliders.saturation),
                ) },
                range = -5f..5f,
            )
            SliderRow(
                label = "コントラスト",
                value = state.sliders.contrast,
                onContentsChanged = { v -> onPreviewChanged(
                    EditorViewModel.SliderValues(state.sliders.exposureEv, v, state.sliders.saturation),
                ) },
                onCommit = { v -> onCommitted(
                    EditorViewModel.SliderValues(state.sliders.exposureEv, v, state.sliders.saturation),
                ) },
                range = -1f..1f,
            )
            SliderRow(
                label = "彩度",
                value = state.sliders.saturation,
                onContentsChanged = { v -> onPreviewChanged(
                    EditorViewModel.SliderValues(state.sliders.exposureEv, state.sliders.contrast, v),
                ) },
                onCommit = { v -> onCommitted(
                    EditorViewModel.SliderValues(state.sliders.exposureEv, state.sliders.contrast, v),
                ) },
                range = -1f..1f,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onUndo, enabled = state.historyCount > 0) { Text("Undo") }
                Button(onClick = onExport) { Text("書き出し…") }
                Text("履歴 ${state.historyCount} 件", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Double,
    onContentsChanged: (Double) -> Unit,
    onCommit: (Double) -> Unit,
    range: ClosedFloatingPointRange<Float>,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text("%.2f".format(value), style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { f -> onContentsChanged(f.toDouble()) },
            onValueChangeFinished = { onCommit(value) },
            valueRange = range,
        )
    }
}
