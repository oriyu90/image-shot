package com.imageshot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.imageshot.core.model.ImageOpResult
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
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ImageShotApplication).container

        setContent {
            ImageShotTheme {
                // Android 16 の強制 edge-to-edge（targetSdk 36）に対応（edge-to-edge skill）。
                // 各feature画面がsystemBars分を考慮していないため、ホスト側で一括適用する。
                // TODO(M2): 各featureのScaffold自己是inset管理へ移行。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                ) {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "library") {
                    composable("library") {
                        val vm: LibraryViewModel =
                            viewModel { LibraryViewModel(container.assetDao, container.importRepository) }
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
                                )
                            }
                        val editorState by vm.state().collectAsState()
                        val editorImages by vm.images().collectAsState()
                        EditorScreen(
                            state = editorState,
                            images = editorImages,
                            onSliderPreview = { id, value -> vm.onSliderPreview(id, value) },
                            onSliderCommit = { id, value -> vm.onSliderCommit(id, value) },
                            onCurvePreview = { points -> vm.onCurvePreview(points) },
                            onCurveCommit = { points -> vm.onCurveCommit(points) },
                            onCurveReset = vm::onCurveReset,
                            onGeometryRotate = vm::onGeometryRotate,
                            onGeometryFlip = vm::onGeometryFlip,
                            onGeometryCrop = vm::onGeometryCrop,
                            onGeometryResetCrop = vm::onGeometryResetCrop,
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
}
