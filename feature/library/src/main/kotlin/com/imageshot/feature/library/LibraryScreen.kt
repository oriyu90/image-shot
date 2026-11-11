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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.imageshot.core.model.Asset
import com.imageshot.core.ui.theme.spacing
import com.imageshot.feature.library.R as LibraryR
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * ライブラリ画面（U01 + §4.1）。
 *
 * GUI リメイク（ui-android skill / DESIGN_RULES.md）:
 * - 画面の骨格は [Scaffold] + [TopAppBar]。取り込みは上部バーの主操作 + オーバーフローに集約し、
 *   本文の先頭に巨大なボタン列を置かない（本文は写真が主役）。
 * - 幅 840dp 以上は「一覧 + 詳細」の 2 ペイン。狭い画面は 1 ペイン（タップで編集）。
 * - 値は必ずトークン（MaterialTheme.colorScheme / typography / shapes / spacing）から取る。
 *
 * 挙動は従来どおり: 取り込み（Photo Picker / SAF ファイル / SAF フォルダー）、時系列グリッド、
 * URI 失効の検出と再関連付け、編集への遷移。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenAsset: (Asset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val spacing = MaterialTheme.spacing

    var relinkTarget by remember { mutableStateOf<Asset?>(null) }
    var selectedAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK_ITEMS),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch { viewModel.importUris(uris.map { it.toString() }, usePersistPermission = true) }
        }
    }

    val safFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch { viewModel.importUris(uris.map { it.toString() }, usePersistPermission = true) }
        }
    }

    val safTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri: Uri? ->
        treeUri?.let { scope.launch { viewModel.importTree(it.toString()) } }
    }

    val safRelinkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val target = relinkTarget
        if (uri != null && target != null) {
            scope.launch { viewModel.relink(target.id, uri.toString()) }
        }
        relinkTarget = null
    }

    val requestRelink: (Asset) -> Unit = { asset ->
        relinkTarget = asset
        safRelinkLauncher.launch(SAF_PICK_MIME_TYPES)
    }

    LaunchedEffect(Unit) {
        viewModel.onLibraryVisible()
        viewModel.verifyVisibleAvailability()
        viewModel.backfillPendingFingerprints()
    }

    val pickPhotos: () -> Unit = {
        pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val openFiles: () -> Unit = { safFilesLauncher.launch(SAF_PICK_MIME_TYPES) }
    val openTree: () -> Unit = { safTreeLauncher.launch(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                title = {
                    Column {
                        Text(
                            text = stringResource(LibraryR.string.library_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = if (state.importing) {
                                stringResource(
                                    LibraryR.string.library_import_in_progress,
                                    state.importBatchDone,
                                    state.importBatchTotal,
                                )
                            } else {
                                pluralStringResource(LibraryR.plurals.library_count, state.assets.size, state.assets.size)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = pickPhotos, enabled = !state.importing) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(LibraryR.string.library_import_pick),
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(LibraryR.string.library_more_actions),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(LibraryR.string.library_import_files)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    openFiles()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(LibraryR.string.library_import_tree)) },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    openTree()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(LibraryR.string.library_availability_check)) },
                                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                enabled = !state.sweepingAvailability,
                                onClick = {
                                    menuOpen = false
                                    scope.launch { viewModel.refreshAvailability() }
                                },
                            )
                        }
                    }
                },
            )
        },
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
                        onPick = pickPhotos,
                        onOpenTree = openTree,
                        onQueryChange = viewModel::setSearchQuery,
                        onSelectSort = viewModel::setSortOrder,
                        onOpenAsset = { asset -> selectedAssetId = asset.id },
                        onRelink = requestRelink,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    Box(
                        Modifier
                            .width(spacing.xxs)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    AssetDetailPane(
                        asset = selected,
                        thumbnailPath = selected?.let { state.thumbnailPaths[it.id] },
                        onOpen = { selected?.let(onOpenAsset) },
                        onRelink = { selected?.let(requestRelink) },
                        onCheckAvailability = { scope.launch { viewModel.refreshAvailability() } },
                        modifier = Modifier.widthIn(min = DETAIL_PANE_MIN_WIDTH, max = DETAIL_PANE_MAX_WIDTH).fillMaxHeight(),
                    )
                }
            } else {
                LibraryListPane(
                    state = state,
                    selectedId = null,
                    onPick = pickPhotos,
                    onOpenTree = openTree,
                    onQueryChange = viewModel::setSearchQuery,
                    onSelectSort = viewModel::setSortOrder,
                    onOpenAsset = onOpenAsset,
                    onRelink = requestRelink,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LibraryListPane(
    state: LibraryViewModel.UiState,
    selectedId: String?,
    onPick: () -> Unit,
    onOpenTree: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectSort: (LibraryViewModel.AssetSortOrder) -> Unit,
    onOpenAsset: (Asset) -> Unit,
    onRelink: (Asset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Column(modifier = modifier) {
        if (state.importing || state.sweepingAvailability) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }

        LibraryFilterBar(
            query = state.searchQuery,
            sortOrder = state.sortOrder,
            onQueryChange = onQueryChange,
            onSelectSort = onSelectSort,
        )

        // 失敗・警告は複数同時に出うる（取り込み批次 + 可用性スイープ等）。
        // 最新1件だけにすると失敗情報が隠れるため、旧UIどおり複数件表示する（ADR-014）。
        state.messages.takeLast(3).forEach { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.xxs),
            )
        }

        if (state.assets.isEmpty() && !state.importing) {
            LibraryEmptyState(
                onPick = onPick,
                onOpenTree = onOpenTree,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = CELL_MIN_SIZE),
                contentPadding = PaddingValues(
                    start = spacing.lg,
                    end = spacing.lg,
                    top = spacing.sm,
                    bottom = spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.assets, key = { it.id }) { asset ->
                    PhotoCell(
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
}

/** 検索と並び替え。狭い画面では 2 段、広い画面では 1 段に収める。 */
@Composable
private fun LibraryFilterBar(
    query: String,
    sortOrder: LibraryViewModel.AssetSortOrder,
    onQueryChange: (String) -> Unit,
    onSelectSort: (LibraryViewModel.AssetSortOrder) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
    ) {
        val stacked = maxWidth < FILTER_STACK_MAX_WIDTH
        val search: @Composable (Modifier) -> Unit = { m ->
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = MaterialTheme.shapes.extraLarge,
                placeholder = {
                    Text(
                        text = stringResource(LibraryR.string.library_search_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                modifier = m,
            )
        }
        val chips: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
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
            }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                search(Modifier.fillMaxWidth())
                chips()
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                search(Modifier.weight(1f))
                chips()
            }
        }
    }
}

@Composable
private fun LibraryEmptyState(
    onPick: () -> Unit,
    onOpenTree: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = modifier.padding(spacing.xxxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.PhotoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(spacing.giant),
        )
        Spacer(Modifier.size(spacing.lg))
        Text(
            text = stringResource(LibraryR.string.library_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(spacing.sm))
        Text(
            text = stringResource(LibraryR.string.library_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = spacing.readingMaxWidth),
        )
        Spacer(Modifier.size(spacing.xxl))
        Button(onClick = onPick) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(spacing.xl))
            Spacer(Modifier.size(spacing.sm))
            Text(stringResource(LibraryR.string.library_empty_primary))
        }
        TextButton(onClick = onOpenTree) {
            Text(stringResource(LibraryR.string.library_empty_secondary))
        }
    }
}

@Composable
private fun PhotoCell(
    asset: Asset,
    thumbnailPath: String?,
    selected: Boolean,
    onOpen: () -> Unit,
    onRelink: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val disconnected = asset.availability == Asset.AVAILABILITY_REVOKED ||
        asset.availability == Asset.AVAILABILITY_DISCONNECTED
    val shape = MaterialTheme.shapes.medium

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(
                if (selected) {
                    Modifier.border(spacing.xxs, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .clickable { if (disconnected) onRelink() else onOpen() },
    ) {
        AsyncImage(
            model = thumbnailPath?.let { File(it) } ?: asset.sourceUri,
            contentDescription = if (disconnected) {
                stringResource(LibraryR.string.library_cell_content_disconnected, asset.displayName)
            } else {
                asset.displayName
            },
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (disconnected) 0.35f else 1f),
        )
        if (disconnected) {
            Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).size(spacing.xxxl),
            )
            Text(
                text = stringResource(
                    if (asset.availability == Asset.AVAILABILITY_REVOKED) {
                        LibraryR.string.library_cell_state_revoked
                    } else {
                        LibraryR.string.library_cell_state_disconnected
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(spacing.sm)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = spacing.sm, vertical = spacing.xxs),
            )
        }
    }
}

/**
 * 詳細ペイン（幅 840dp 以上）。写真を大きく見せ、メタデータと操作を下に置く。
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
    val spacing = MaterialTheme.spacing
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .verticalScroll(rememberScrollState())
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(
            text = stringResource(LibraryR.string.library_detail_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (asset == null) {
            Text(
                text = stringResource(LibraryR.string.library_detail_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        val disconnected = asset.availability == Asset.AVAILABILITY_REVOKED ||
            asset.availability == Asset.AVAILABILITY_DISCONNECTED

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DETAIL_PREVIEW_MIN_HEIGHT, max = DETAIL_PREVIEW_MAX_HEIGHT)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = thumbnailPath?.let { File(it) } ?: asset.sourceUri,
                contentDescription = if (disconnected) {
                    stringResource(LibraryR.string.library_cell_content_disconnected, asset.displayName)
                } else {
                    asset.displayName
                },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (disconnected) 0.35f else 1f),
            )
        }

        Text(
            text = asset.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                LibraryR.string.library_detail_meta,
                asset.sniffedFormat,
                asset.width,
                asset.height,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatDateTime(asset.dateTaken ?: asset.importedAt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (disconnected) Icons.Outlined.BrokenImage else Icons.Filled.Image,
                contentDescription = null,
                tint = if (disconnected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(spacing.xl),
            )
            Spacer(Modifier.size(spacing.sm))
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
                style = MaterialTheme.typography.bodyMedium,
                color = if (disconnected) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (disconnected) {
            Text(
                text = stringResource(LibraryR.string.library_detail_relink_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Button(
                onClick = onRelink,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(stringResource(LibraryR.string.library_cell_relink))
            }
        } else {
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(spacing.xl))
                Spacer(Modifier.size(spacing.sm))
                Text(stringResource(LibraryR.string.library_detail_open))
            }
        }
        TextButton(onClick = onCheckAvailability, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(LibraryR.string.library_availability_check))
        }
    }
}

private fun formatDateTime(epochMs: Long): String =
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))

private const val MAX_PICK_ITEMS = 20

/** 2 ペインへ切り替える最小幅（material3 の expanded 幅クラス相当）。 */
private val WIDE_TWO_PANE_MIN_WIDTH = 840.dp

private val DETAIL_PANE_MIN_WIDTH = 320.dp
private val DETAIL_PANE_MAX_WIDTH = 420.dp

/** グリッド 1 セルの最小辺。広い画面では列数が増える。 */
private val CELL_MIN_SIZE = 176.dp

/** 検索とチップを縦積みにする最大幅。 */
private val FILTER_STACK_MAX_WIDTH = 520.dp

private val DETAIL_PREVIEW_MIN_HEIGHT = 180.dp
private val DETAIL_PREVIEW_MAX_HEIGHT = 320.dp

/**
 * SAF の MIME フィルタ。image 系だけだと ARW 等の RAW が provider 側で
 * `application/octet-stream` になり一覧に出ないため、ワイルドカードにして
 * 非対応は取り込み時に明示拒否する（DB 非登録）。
 */
private val SAF_PICK_MIME_TYPES = arrayOf("*/*")
