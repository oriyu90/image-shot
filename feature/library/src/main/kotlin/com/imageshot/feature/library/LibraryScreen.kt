package com.imageshot.feature.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.imageshot.core.model.Asset
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.ImportPolicy
import java.io.File
import kotlinx.coroutines.launch

/**
 * ライブラリ画面（初期骨格）。
 * - 写真の内容を外部送信しない。INTERNET権限は使用しない（CoilはローカルURI/ファイルのみ対象）。
 * - 暗色ニュートラル基調（01_製品仕様書.md §4.3）。純黒背景は使わない。
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenAsset: (Asset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Photo Picker: 選択画像への短期アクセス。継続アクセスを必要とする場合はSAF側での許可取得
    // TODO(ADR確認): 複数選択SAF(ACTION_OPEN_DOCUMENT_TREE)の取得ポリシーはADR-009の再演算PRで
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            when (val r = viewModel.register(uri.toString(), ImportPolicy())) {
                is ImageOpResult.Success -> Unit
                else -> Unit // 失敗・警告は messages に記述済み
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    pickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                enabled = !state.importing,
            ) {
                Text(if (state.importing) "取り込み中…" else "写真を取り込む (Photo Picker)")
            }
            state.messages.takeLast(3).forEach { msg ->
                Text(msg, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (state.importing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(state.assets, key = { it.id }) { asset ->
                AssetCell(asset) { onOpenAsset(asset) }
            }
        }
    }
}

@Composable
private fun AssetCell(asset: Asset, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { onClick() }
            .background(Color(0xFF1A1A1A))
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        // ローカル URI/ファイルのみを読み込み。リモート画像は暗黙に読み込まない。
        AsyncImage(
            model = if (asset.sourceUri.startsWith("file:")) {
                File(asset.sourceUri.removePrefix("file:"))
            } else {
                asset.sourceUri
            },
            contentDescription = asset.displayName,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = "${asset.width}×${asset.height} ${asset.sniffedFormat}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.BottomCenter).padding(2.dp),
        )
    }
}
