package com.imageshot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.imageshot.core.model.ImageOpResult
import com.imageshot.feature.editor.EditorMaskActions
import com.imageshot.feature.editor.EditorScreen
import com.imageshot.feature.editor.EditorViewModel
import com.imageshot.feature.export.ExportScreen
import com.imageshot.feature.export.ExportViewModel
import com.imageshot.feature.library.LibraryScreen
import com.imageshot.feature.library.LibraryViewModel
import kotlinx.coroutines.launch

/**
 * MainActivity。UIスレッドには パーサ/ハッシュ/デコード/プロファイル変換を一切置かない（§10）。
 * 画面再作成（回転/折りたたみ）でも DB上の編集文書が正本（ADR-007）。SavedStateHandleはUI補助のみ。
 *
 * PR-30 G3: ホスト側の一括 `systemBarsPadding()` は廃止した。edge-to-edge は
 * [enableEdgeToEdge] で有効化し、inset は各 feature 画面の [androidx.compose.material3.Scaffold]
 * （`contentWindowInsets = WindowInsets.safeDrawing`）が自分で持つ。ホストと feature で
 * 二重に padding しないため、画面回転・折りたたみ・IME 表示でも余白が積み上がらない。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 16 の強制 edge-to-edge（targetSdk 36）に対応。ステータス/ナビゲーションバーの
        // アイコン色はこの API が自動で面の明暗に追従させる（WindowCompat 版は使わない）。
        enableEdgeToEdge()
        val container = (application as ImageShotApplication).container

        setContent {
            ImageShotTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "library") {
                    composable("library") {
                        val vm: LibraryViewModel =
                            viewModel {
                                LibraryViewModel(
                                    assetDao = container.assetDao,
                                    importRepository = container.importRepository,
                                    thumbnails = container.thumbnailGenerator,
                                    // PR-27 G1: VM 文言は res 参照（application context 結線）。
                                    strings = container.strings,
                                )
                            }
                        LibraryScreen(
                            viewModel = vm,
                            onOpenAsset = { asset ->
                                navController.navigate("editor/${asset.id}")
                            },
                        )
                    }
                    composable("editor/{assetId}") { entry ->
                        val assetId = entry.arguments?.getString("assetId").orEmpty()
                        val vm: EditorViewModel =
                            viewModel(key = assetId) {
                                EditorViewModel(
                                    assetId = assetId,
                                    assetDao = container.assetDao,
                                    renderer = container.renderer,
                                    bitmaps = container.renderBitmaps,
                                    editStore = container.editStore,
                                    strings = container.strings,
                                )
                            }
                        val editorState by vm.state().collectAsState()
                        val editorImages by vm.images().collectAsState()
                        // PR-29c: 局部補正の配線（13 個のコールバックを 1 つの束にまとめる）。
                        // VM のメソッド参照は安定なので vm 単位で remember する。
                        val maskActions = remember(vm) {
                            EditorMaskActions(
                                onAdd = vm::onMaskAdd,
                                onSelect = vm::onMaskSelect,
                                onRemove = vm::onMaskRemove,
                                onPreview = vm::onMaskPreview,
                                onCommit = vm::onMaskCommit,
                                onAdjustPreview = vm::onMaskAdjustPreview,
                                onAdjustCommit = vm::onMaskAdjustCommit,
                                onResetAdjustments = vm::onMaskResetAdjustments,
                                onParamPreview = vm::onMaskParamPreview,
                                onParamCommit = vm::onMaskParamCommit,
                                onBrushAddPreview = vm::onMaskBrushAddPreview,
                                onBrushAddCommit = vm::onMaskBrushAddCommit,
                                onBrushRadius = vm::onMaskBrushRadius,
                            )
                        }
                        EditorScreen(
                            state = editorState,
                            images = editorImages,
                            onSliderPreview = { id, value -> vm.onSliderPreview(id, value) },
                            onSliderCommit = { id, value -> vm.onSliderCommit(id, value) },
                            onCurvePreview = { points -> vm.onCurvePreview(points) },
                            onCurveCommit = { points -> vm.onCurveCommit(points) },
                            onCurveReset = vm::onCurveReset,
                            onCurveChannelSelect = vm::onCurveChannelSelect,
                            onGeometryRotate = vm::onGeometryRotate,
                            onGeometryFlip = vm::onGeometryFlip,
                            onGeometryCrop = vm::onGeometryCrop,
                            onGeometryResetCrop = vm::onGeometryResetCrop,
                            onGeometryAngle = vm::onGeometryFineAngle,
                            onGeometryAngleReset = vm::onGeometryAngleReset,
                            onGeometryCropNudge = vm::onGeometryCropNudge,
                            onGeometryCropScale = vm::onGeometryCropScale,
                            maskActions = maskActions,
                            onUndo = vm::undo,
                            onRedo = vm::redo,
                            onBeforeChanged = vm::setBeforeVisible,
                            // route arg 名は documentId だが実際に渡すのは assetId
                            // （正本 documentId は ExportViewModel が EditStore.ensureDocument で解決）。
                            onExport = { navController.navigate("export/$it") },
                        )
                    }
                    composable("export/{documentId}") { entry ->
                        val assetIdArg = entry.arguments?.getString("documentId").orEmpty()
                        val scope = rememberCoroutineScope()
                        val vm: ExportViewModel =
                            viewModel(key = assetIdArg) {
                                ExportViewModel(
                                    assetId = assetIdArg,
                                    repository = container.exportJobRepository,
                                    editStore = container.editStore,
                                    assetDao = container.assetDao,
                                    strings = container.strings,
                                )
                            }
                        val exportState by vm.observeJobs().collectAsState()
                        val settings by vm.settings().collectAsState()
                        ExportScreen(
                            jobs = exportState.jobs,
                            settings = settings,
                            onFormatSelected = vm::chooseFormat,
                            onQualityChanged = vm::setQuality,
                            onDestinationPicked = vm::noteDestination,
                            onEnqueue = {
                                // キュー登録は正本経路（repository+request ファイル）。WorkManager 割当は
                                // app 層が VM 帰りの jobId で行う（ADR-010）。実処理は ExportWorker。
                                scope.launch {
                                    val enqueued = vm.enqueue()
                                    if (enqueued is ImageOpResult.Success) {
                                        container.exportScheduler.schedule(enqueued.value)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
