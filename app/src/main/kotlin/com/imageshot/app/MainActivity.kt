package com.imageshot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.work.WorkManager
import com.imageshot.core.model.ExportRequest
import com.imageshot.core.model.ImageOpResult
import com.imageshot.feature.editor.EditorScreen
import com.imageshot.feature.editor.EditorViewModel
import com.imageshot.feature.export.ExportScreen
import com.imageshot.feature.export.ExportViewModel
import com.imageshot.feature.export.WorkManagerExportJobScheduler
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
                            onUndo = vm::undo,
                            onRedo = vm::redo,
                            onBeforeChanged = vm::setBeforeVisible,
                            onExport = { navController.navigate("export") },
                        )
                    }
                    composable("export") {
                        val scope = rememberCoroutineScope()
                        val vm: ExportViewModel =
                            viewModel { ExportViewModel(container.exportJobDao) }
                        val exportState by vm.observeJobs().collectAsState()
                        ExportScreen(
                            jobs = exportState.jobs,
                            // キュー登録のみ。実処理は ExportWorker・WorkManager が担う（ADR-010）。
                            // TODO(M2): 書き出し先URI(SAF/MediaStore)ダイアログと editor からの遷移引数。
                            onEnqueueDemo = {
                                scope.launch {
                                    val enqueued = vm.enqueue(
                                        ExportRequest(
                                            documentId = "demo",
                                            recipeRevision = 0L,
                                            outputUri = "",
                                            format = "JPEG",
                                            quality = 90,
                                            settingsHash = "demo",
                                        ),
                                    )
                                    if (enqueued is ImageOpResult.Success) {
                                        WorkManagerExportJobScheduler(
                                            WorkManager.getInstance(applicationContext),
                                        ).schedule(enqueued.value)
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
