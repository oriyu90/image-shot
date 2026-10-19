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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.imageshot.core.model.Asset
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * ライブラリ画面（U01 + §4.1 ライブラリのM1最小構成）。
 * - 先頭のインポート操作群: Photo Picker（複数選択）、SAF ファイル複数、SAF フォルダー。
 * - 時系列グリッド（Coil 3.x。セルの model は ViewModel が持つサムネイル行（PR-8 で遅延生成）
 *   の絶対パスが在ればそれを、無ければこれまでどおりソースURIを使う。挙動の実測は未測定）。
 * - 表示項目: 日時・判明するメタデータ（sniff形式・寸法・MediaStore簡易参照の撮影日）。
 * - URI失効検出（可用性再検査）→ 切断/失効状態表示 → 再関連付け導線ボタン。
 * - 写真の内容を外部送信しない。INTERNET権限は使用しない（CoilはローカルURIのみ対象）。
 * - 暗色ニュートラル基調（§4.3）。純黒背景は使わない。タッチターゲット48dp以上を基本とする。
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenAsset: (Asset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 再関連付け対象（切断/失効セルの「再関連付け」でSAF再選択を促す）。
    var relinkTarget by remember { mutableStateOf<Asset?>(null) }

    // Photo Picker: ACTION_PICK_IMAGES 相当の複数選択。取得URIは persistable grant を要求。
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK_ITEMS),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch { viewModel.importUris(uris.map { it.toString() }, usePersistPermission = true) }
        }
    }

    // SAF ファイル複数選択（ACTION_OPEN_DOCUMENT）。persistable grant を要求。
    val safFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch { viewModel.importUris(uris.map { it.toString() }, usePersistPermission = true) }
        }
    }

    // SAF フォルダー選択（ACTION_OPEN_DOCUMENT_TREE）。
    val safTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri: Uri? ->
        treeUri?.let { scope.launch { viewModel.importTree(it.toString()) } }
    }

    // 再関連付け用の単一ファイル再選択。
    val safRelinkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val target = relinkTarget
        if (uri != null && target != null) {
            scope.launch { viewModel.relink(target.id, uri.toString()) }
        }
        relinkTarget = null
    }

    // 入室時に1回、URI可用性を検査（失効/切断はセルに切断状態表示）。回転等で再実行しない。
    // サムネイル生成（PR-8）の可視化トリガも同じ順序で入れる（ViewModel内で単一ジョブに収斂・冪等）。
    LaunchedEffect(Unit) {
        viewModel.onLibraryVisible()
        viewModel.refreshAvailability()
        viewModel.backfillPendingFingerprints()
    }

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        ImportHeader(
            importing = state.importing,
            batchDone = state.importBatchDone,
            batchTotal = state.importBatchTotal,
            onPick = {
                pickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onOpenFiles = { safFilesLauncher.launch(arrayOf("image/*")) },
            onOpenTree = { safTreeLauncher.launch(null) },
        )

        SearchSortBar(
            query = state.searchQuery,
            sortOrder = state.sortOrder,
            sweeping = state.sweepingAvailability,
            onQueryChange = viewModel::setSearchQuery,
            onSelectSort = viewModel::setSortOrder,
            onRefreshAvailability = { scope.launch { viewModel.refreshAvailability() } },
        )

        if (state.importing || state.sweepingAvailability) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }

        state.messages.takeLast(4).forEach { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f).padding(top = 4.dp),
        ) {
            items(state.assets, key = { it.id }) { asset ->
                AssetCell(
                    asset = asset,
                    thumbnailPath = state.thumbnailPaths[asset.id],
                    onOpen = { onOpenAsset(asset) },
                    onRelink = {
                        relinkTarget = asset
                        safRelinkLauncher.launch(arrayOf("image/*"))
                    },
                )
            }
        }
    }
}

@Composable
private fun ImportHeader(
    importing: Boolean,
    batchDone: Int,
    batchTotal: Int,
    onPick: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTree: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = onPick,
                enabled = !importing,
            ) {
                Text(if (importing) "取り込み中… $batchDone/$batchTotal" else "写真を取り込む（複数選択）")
            }
            OutlinedButton(onClick = onOpenFiles, enabled = !importing) {
                Text("ファイル（SAF）")
            }
            OutlinedButton(onClick = onOpenTree, enabled = !importing) {
                Text("フォルダー（SAF）")
            }
        }
    }
}

@Composable
private fun SearchSortBar(
    query: String,
    sortOrder: LibraryViewModel.AssetSortOrder,
    sweeping: Boolean,
    onQueryChange: (String) -> Unit,
    onSelectSort: (LibraryViewModel.AssetSortOrder) -> Unit,
    onRefreshAvailability: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("表示名で検索") },
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = sortOrder == LibraryViewModel.AssetSortOrder.DATE_TAKEN_DESC,
            onClick = { onSelectSort(LibraryViewModel.AssetSortOrder.DATE_TAKEN_DESC) },
            label = { Text("撮影日") },
        )
        FilterChip(
            selected = sortOrder == LibraryViewModel.AssetSortOrder.DISPLAY_NAME_ASC,
            onClick = { onSelectSort(LibraryViewModel.AssetSortOrder.DISPLAY_NAME_ASC) },
            label = { Text("表示名") },
        )
        TextButton(onClick = onRefreshAvailability, enabled = !sweeping) {
            Text(if (sweeping) "確認中…" else "可用性確認")
        }
    }
}

@Composable
private fun AssetCell(
    asset: Asset,
    thumbnailPath: String?,
    onOpen: () -> Unit,
    onRelink: () -> Unit,
) {
    val disconnected = asset.availability == Asset.AVAILABILITY_REVOKED ||
        asset.availability == Asset.AVAILABILITY_DISCONNECTED
    val dateMs = asset.dateTaken ?: asset.importedAt
    val dateLabel = remember(dateMs) { formatDateTime(dateMs) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .clickable { if (disconnected) onRelink() else onOpen() }
                .background(Color(0xFF1A1A1A))
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            // model 選択のみの変更（PR-8）: サムネイル行の実体ファイルがあればそれを、
            // 無ければこれまでどおりソースURI（全量デコード相当）。
            // ローカルのみを読む（失効URIはCoil側でエラー表示になる。黙って成功しない）。
            AsyncImage(
                model = thumbnailPath?.let { File(it) } ?: asset.sourceUri,
                contentDescription = if (disconnected) {
                    "${asset.displayName}（切断: 再関連付けが必要）"
                } else {
                    asset.displayName
                },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (disconnected) 0.35f else 1f),
            )
            if (disconnected) {
                Text(
                    text = if (asset.availability == Asset.AVAILABILITY_REVOKED) "権限失効" else "切断",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFFFB59B),
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                )
                Button(
                    onClick = onRelink,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38566F)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp),
                ) {
                    Text("再関連付け", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            text = dateLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = "${asset.sniffedFormat} · ${asset.width}×${asset.height}" +
                if (asset.imageFingerprint == null) " · 指紋未計算" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private fun formatDateTime(epochMs: Long): String =
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))

private const val MAX_PICK_ITEMS = 20
