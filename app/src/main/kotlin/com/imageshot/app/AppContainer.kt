package com.imageshot.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.WorkManager
import com.imageshot.core.database.EditStore
import com.imageshot.core.database.ImageShotDatabase
import com.imageshot.core.database.RoomEditStoreBackend
import com.imageshot.core.model.Asset
import com.imageshot.core.render.ExportBitmapRenderer
import com.imageshot.core.render.PhotoRenderer
import com.imageshot.core.render.RenderBitmapStore
import com.imageshot.core.render.SimpleBitmapPhotoRenderer
import com.imageshot.core.storage.ContentUriImportRepository
import com.imageshot.core.storage.ImportRepository
import com.imageshot.core.storage.ThumbnailGenerator
import com.imageshot.feature.export.ExportJobRepository
import com.imageshot.feature.export.ExportWorkerDeps
import com.imageshot.feature.export.WorkManagerExportJobScheduler
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手動 DI コンテナ（Hilt不採用・ADR-018）。プロセス内で単一のDB/リポジトリ/レンダラーを保持する。
 * - DBはRoom (WAL)。画面再作成/Window 再生成でDBが影響を受けない（02_実装定義書.md §10）。
 * - renderer閉鎖はプロセス終了境界で明示（所有権契約 §4.2）。UIスレッドで重処理を実行しない。
 */
class AppContainer(context: Context) {
    val database: ImageShotDatabase = ImageShotDatabase.build(context)
    val assetDao = database.assetDao()
    val editDocumentDao = database.editDocumentDao()
    val exportJobDao = database.exportJobDao()
    val editRevisionDao = database.editRevisionDao()

    val importRepository: ImportRepository =
        ContentUriImportRepository(context, context.contentResolver, assetDao)

    /** 非破壊履歴（ADR-007）。確定イベントは Room トランザクション1件で永続化。 */
    val editStore: EditStore = EditStore(RoomEditStoreBackend(database))

    /** RenderResult の所有権管理（RAII: 直近世代のみ保持、旧世代は明示解放）。 */
    val renderBitmaps: RenderBitmapStore = RenderBitmapStore()

    /**
     * プレビュー/書き出し共通の描画契約（§4.2）。解決キーは assetId（正本は AssetDao）。
     * TODO(ADR確認): URI失効時の availability 更新と disconnect 状態表示は library 担当と統合。
     */
    val renderer: PhotoRenderer = SimpleBitmapPhotoRenderer(context, { assetId ->
        assetDao.get(assetId)?.takeIf { it.availability == Asset.AVAILABILITY_OK }
            ?.let { Uri.parse(it.sourceUri) }
    }, renderBitmaps)

    /** フル解像度の書き出しレンダラー（ExportRenderer.kt・core/render 新規担当ファイル）。 */
    val exportRenderer: ExportBitmapRenderer = ExportBitmapRenderer(context)

    /**
     * ライブラリ表示用サムネイルの遅延生成（PR-8）。filesDir/thumbnails 配下に書き出す。
     * ここではインスタンス化のみで自動生成は行わない（生成はライブラリ表示時に
     * LibraryViewModel が行が無い資産にのみ指示する）。
     */
    val thumbnailGenerator: ThumbnailGenerator =
        ThumbnailGenerator(context, database.thumbnailDao())

    /** 書き出しジョブ（ADR-010 二段階コミット）。staging は内部 cache 領域のみ。 */
    val exportJobRepository: ExportJobRepository =
        ExportJobRepository(exportJobDao, File(context.cacheDir, "export_staging"))

    /** WorkManager 実働。ジョブ永続化は WorkManager 自身の制約（ADR-010）。 */
    val exportScheduler = WorkManagerExportJobScheduler(WorkManager.getInstance(context))

    /** プロセス生存中の非UI処理用スコープ（起動復旧走査）。画面側からは結合しない。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Workerへの依存注入。未結線のまま Worker が走ることを禁止（ADR-014: 音なき失敗の禁止）。
        ExportWorkerDeps.install(exportJobRepository, assetDao, exportRenderer, context.contentResolver)
        // §8.2 / ADR-010: kill・再起動で busy（PREPARING..PUBLISHING）のまま残ったジョブの
        // 起動時走査（必須）。recoverInterrupted() は request ファイルの有無で
        // FAILED_RETRYABLE / FAILED_PERMANENT に分類し、staging 半端物のみ廃棄する
        // （それ以上のファイル削除は行わない）。
        // 失敗してもアプリ起動を落とさない（結果は DB 正本と Log のみ）。
        appScope.launch {
            runCatching { exportJobRepository.recoverInterrupted() }
                .onSuccess { recovered ->
                    if (recovered.isNotEmpty()) {
                        Log.i(LOG_TAG, "export recovery scan: ${recovered.size} job(s) resolved from interruption")
                    }
                }
                .onFailure { Log.w(LOG_TAG, "export recovery scan failed", it) }
        }
    }

    private companion object {
        const val LOG_TAG = "AppContainer"
    }
}

/** プロセス単位のコンテナ保持。 */
class ImageShotApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
