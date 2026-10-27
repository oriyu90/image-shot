package com.imageshot.feature.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.imageshot.core.model.Asset
import com.imageshot.feature.library.R as LibraryR
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
 *
 * PR-30 の変更点:
 * - **G3**: ホスト（MainActivity）の一括 systemBarsPadding を廃止したため、この画面が
 *   [Scaffold] の `contentWindowInsets = WindowInsets.safeDrawing` で自分の inset を持つ
 *   （safeDrawing は IME を含むため、検索欄でキーボードが出ても一覧と欄が隠れない）。
 * - **G2**: 表示・再表示時に availability==ok の資産だけ URI を検査する
 *   （[LibraryViewModel.verifyVisibleAvailability]。失効はカードの「再関連付け」から復旧）。
 * - **F3**: 幅 840dp 以上では「一覧 + 選択中アセットの詳細/プレビュー」の 2 ペイン。
 *   狭い画面は従来どおり 1 ペイン（タップで編集へ）。Navigation 3 / ListDetailPaneScaffold は
 *   使わない（本プロジェクトは navigation-compose のまま）。
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

    // 2ペインの選択（UI補助のみ・DB 正本ではない）。回転/折りたたみを跨いで保持する。
    var selectedAssetId by rememberSaveable { mutableStateOf<String?>(null) }

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

    // 再関連付け導線（セル/詳細ペインの双方から同じ経路を使う）。
    val requestRelink: (Asset) -> Unit = { asset ->
        relinkTarget = asset
        safRelinkLauncher.launch(SAF_PICK_MIME_TYPES)
    }

    // 表示・再表示（編集から戻る等）のたびに走る。回転では configChanges により再実行されない。
    // サムネイル生成（PR-8）の可視化トリガも同じ順序で入れる（ViewModel内で単一ジョブに収斂・冪等）。
    LaunchedEffect(Unit) {
        viewModel.onLibraryVisible()
        // G2: 表示・再表示のたびに「availability==ok の資産だけ」を検査する
        // （全資産の常時ポーリングはしない。単一ジョブ + 下限間隔は VM 側で担保）。
        viewModel.verifyVisibleAvailability()
        viewModel.backfillPendingFingerprints()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            val twoPane = maxWidth >= WIDE_TWO_PANE_MIN_WIDTH
            val selected = state.assets.firstOrNull { it.id == selectedAssetId }
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    LibraryListPane(
                        state = state,
                        selectedId = selectedAssetId,
                        onPick = {
                            pickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onOpenFiles = { safFilesLauncher.launch(SAF_PICK_MIME_TYPES) },
                        onOpenTree = { safTreeLauncher.launch(null) },
                        onQueryChange = viewModel::setSearchQuery,
                        onSelectSort = viewModel::setSortOrder,
                        onRefreshAvailability = { scope.launch { viewModel.refreshAvailability() } },
                        // 2ペイン: 接続中セルのタップは「詳細の選択」。編集は詳細ペインのボタン。
                        onOpenAsset = { asset -> selectedAssetId = asset.id },
                        onRelink = requestRelink,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    // 区切り線（装飾のみ・タップ対象ではない）。
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF2E2E2E)))
                    AssetDetailPane(
                        asset = selected,
                        thumbnailPath = selected?.let { state.thumbnailPaths[it.id] },
                        onOpen = { selected?.let(onOpenAsset) },
                        onRelink = { selected?.let(requestRelink) },
                        onCheckAvailability = { scope.launch { viewModel.refreshAvailability() } },
                        modifier = Modifier.width(DETAIL_PANE_WIDTH).fillMaxHeight(),
                    )
                }
            } else {
                LibraryListPane(
                    state = state,
                    selectedId = null,
                    onPick = {
                        pickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onOpenFiles = { safFilesLauncher.launch(SAF_PICK_MIME_TYPES) },
                    onOpenTree = { safTreeLauncher.launch(null) },
                    onQueryChange = viewModel::setSearchQuery,
                    onSelectSort = viewModel::setSortOrder,
                    onRefreshAvailability = { scope.launch { viewModel.refreshAvailability() } },
                    onOpenAsset = onOpenAsset,
                    onRelink = requestRelink,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 一覧ペイン（ヘッダ操作 + 検索/ソート + メッセージ + グリッド）。
 * 1ペイン/2ペインで同一コンポーザブルを使い、状態と操作の意味を変えない。
 */
@Composable
private fun LibraryListPane(
    state: LibraryViewModel.UiState,
    selectedId: String?,
    onPick: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTree: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectSort: (LibraryViewModel.AssetSortOrder) -> Unit,
    onRefreshAvailability: () -> Unit,
    onOpenAsset: (Asset) -> Unit,
    onRelink: (Asset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(8.dp)) {
        ImportHeader(
            importing = state.importing,
            batchDone = state.importBatchDone,
            batchTotal = state.importBatchTotal,
            onPick = onPick,
            onOpenFiles = onOpenFiles,
            onOpenTree = onOpenTree,
        )

        SearchSortBar(
            query = state.searchQuery,
            sortOrder = state.sortOrder,
            sweeping = state.sweepingAvailability,
            onQueryChange = onQueryChange,
            onSelectSort = onSelectSort,
            onRefreshAvailability = onRefreshAvailability,
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
            // F3: 幅に応じて列数が増える（広い画面では1行の枚数が増え、2ペインでも破綻しない）。
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
                    selected = asset.id == selectedId,
                    onOpen = { onOpenAsset(asset) },
                    onRelink = { onRelink(asset) },
                )
            }
        }
    }
}

/**
 * 詳細ペイン（F3・幅 840dp 以上のときだけ表示）。
 * 選択中アセットのプレビュー・メタデータ・状態と、編集/再接続の導線を出す。
 * 失効・切断時は編集ではなく「再関連付け」を前面に出す（原画像が無いのに開かせない）。
 */
@Composable
private fun AssetDetailPane(
    asset: Asset?,
    thumbnailPath: String?,
    onOpen: () -> Unit,
    onRelink: () -> Unit,
    onCheckAvailability: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color(0xFF181818))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(LibraryR.string.library_detail_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (asset == null) {
            Text(
                text = stringResource(LibraryR.string.library_detail_placeholder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val disconnected = asset.availability == Asset.AVAILABILITY_REVOKED ||
                asset.availability == Asset.AVAILABILITY_DISCONNECTED
            AsyncImage(
                model = thumbnailPath?.let { File(it) } ?: asset.sourceUri,
                contentDescription = if (disconnected) {
                    stringResource(LibraryR.string.library_cell_content_disconnected, asset.displayName)
                } else {
                    asset.displayName
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color(0xFF1A1A1A))
                    .padding(2.dp)
                    .alpha(if (disconnected) 0.35f else 1f),
            )
            Text(
                text = asset.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    LibraryR.string.library_detail_meta,
                    asset.sniffedFormat,
                    asset.width,
                    asset.height,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDateTime(asset.dateTaken ?: asset.importedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    LibraryR.string.library_detail_availability,
                    stringResource(
                        when (asset.availability) {
                            Asset.AVAILABILITY_REVOKED -> LibraryR.string.library_cell_state_revoked
                            Asset.AVAILABILITY_DISCONNECTED -> LibraryR.string.library_cell_state_disconnected
                            else -> LibraryR.string.library_cell_state_ok
                        },
                    ),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (disconnected) Color(0xFFFFB59B) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (disconnected) {
                Text(
                    text = stringResource(LibraryR.string.library_detail_relink_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFB59B),
                )
                Button(
                    onClick = onRelink,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38566F)),
                ) {
                    Text(stringResource(LibraryR.string.library_cell_relink))
                }
            } else {
                Button(onClick = onOpen) {
                    Text(stringResource(LibraryR.string.library_detail_open))
                }
            }
            TextButton(onClick = onCheckAvailability) {
                Text(stringResource(LibraryR.string.library_availability_check))
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
                Text(if (importing) stringResource(LibraryR.string.library_import_in_progress, batchDone, batchTotal) else stringResource(LibraryR.string.library_import_pick))
            }
            OutlinedButton(onClick = onOpenFiles, enabled = !importing) {
                Text(stringResource(LibraryR.string.library_import_files))
            }
            OutlinedButton(onClick = onOpenTree, enabled = !importing) {
                Text(stringResource(LibraryR.string.library_import_tree))
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
            placeholder = { Text(stringResource(LibraryR.string.library_search_placeholder)) },
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = sortOrder == LibraryViewModel.AssetSortOrder.DATE_TAKEN_DESC,
            onClick = { onSelectSort(LibraryViewModel.AssetSortOrder.DATE_TAKEN_DESC) },
            label = { Text(stringResource(LibraryR.string.library_sort_date_taken)) },
        )
        FilterChip(
            selected = sortOrder == LibraryViewModel.AssetSortOrder.DISPLAY_NAME_ASC,
            onClick = { onSelectSort(LibraryViewModel.AssetSortOrder.DISPLAY_NAME_ASC) },
            label = { Text(stringResource(LibraryR.string.library_sort_display_name)) },
        )
        TextButton(onClick = onRefreshAvailability, enabled = !sweeping) {
            Text(stringResource(if (sweeping) LibraryR.string.library_availability_checking else LibraryR.string.library_availability_check))
        }
    }
}

@Composable
private fun AssetCell(
    asset: Asset,
    thumbnailPath: String?,
    selected: Boolean = false,
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
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        Modifier
                    },
                )
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
                    stringResource(LibraryR.string.library_cell_content_disconnected, asset.displayName)
                } else {
                    asset.displayName
                },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (disconnected) 0.35f else 1f),
            )
            if (disconnected) {
                Text(
                    text = stringResource(
                        if (asset.availability == Asset.AVAILABILITY_REVOKED) {
                            LibraryR.string.library_cell_state_revoked
                        } else {
                            LibraryR.string.library_cell_state_disconnected
                        },
                    ),
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
                    Text(stringResource(LibraryR.string.library_cell_relink), style = MaterialTheme.typography.labelSmall)
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
                if (asset.imageFingerprint == null) {
                    stringResource(LibraryR.string.library_cell_fingerprint_uncomputed)
                } else {
                    ""
                },
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

/** F3: 2ペインへ切り替える最小幅（material3 の expanded 幅クラス相当の 840dp）。 */
private val WIDE_TWO_PANE_MIN_WIDTH = 840.dp

/** F3: 2ペイン時の詳細ペイン幅（プレビューが読める最小限・一覧を圧迫しない）。 */
private val DETAIL_PANE_WIDTH = 360.dp

/**
 * SAF の MIME フィルタ。image 系 MIME だけだと ARW 等の RAW が provider 側で
 * `application/octet-stream` になり一覧に出ない（実機/エミュレータで再現）。
 * RAW を選択して「非対応候補」として明示するため、任意ファイルを選べるワイルドカードにする。
 * 非画像・非対応は取り込み時に ImportSniffer/register が拒否する（DB 非登録）。
 */
private val SAF_PICK_MIME_TYPES = arrayOf("*/*")
