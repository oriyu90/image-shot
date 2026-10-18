package com.imageshot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.imageshot.feature.editor.EditorScreen
import com.imageshot.feature.editor.EditorViewModel
import com.imageshot.feature.export.ExportScreen
import com.imageshot.feature.export.ExportViewModel
import com.imageshot.feature.library.LibraryScreen
import com.imageshot.feature.library.LibraryViewModel

/**
 * MainActivity。UIスレッドには パーサ/ハッシュ/デコード/プロファイル変換を一切置かない（§10）。
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
                                EditorViewModel(assetId, container.assetDao, container.renderer)
                            }
                        val editorState by vm.state().collectAsState()
                        EditorScreen(
                            asset = editorState.asset,
                            state = editorState,
                            onPreviewChanged = { values -> vm.onSliderPreview(values) },
                            onCommitted = { values -> vm.onSliderCommitted(values) },
                            onUndo = { vm.undoLast() },
                            onExport = { navController.navigate("export") },
                        )
                    }
                    composable("export") {
                        val vm: ExportViewModel =
                            viewModel { ExportViewModel(container.exportJobDao) }
                        val exportState by vm.observeJobs().collectAsState()
                        ExportScreen(
                            jobs = exportState.jobs,
                            // デモ登録は未配線（成功装いをしないため、ここでは何も登録しない）。
                            // TODO(ADR-010確認): WorkManager 実働 + 出力先URI(SAF/MediaStore)選択は Sprint C。
                            onEnqueueDemo = {},
                        )
                    }
                }
            }
        }
    }
}
