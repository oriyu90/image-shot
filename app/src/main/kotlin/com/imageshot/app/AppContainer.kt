package com.imageshot.app

import android.app.Application
import com.imageshot.core.database.ImageShotDatabase
import com.imageshot.core.render.PhotoRenderer
import com.imageshot.core.render.SimpleBitmapPhotoRenderer
import com.imageshot.core.storage.ContentUriImportRepository
import com.imageshot.core.storage.ImportRepository
import android.net.Uri
import android.content.Context

/**
 * 手動 DI コンテナ（Hilt不採用）。プロセス内で単一のDB/リポジトリ/レンダラーを保持する。
 * - DBはRoom (WAL)。GBA再作成でDBが影響を受けない（02_実装定義書.md §2）。
 * - renderer.close() はプロセス終了境界で明示呼出（所有権契約）。
 */
class AppContainer(context: Context) {
    val database: ImageShotDatabase = ImageShotDatabase.build(context)
    val assetDao = database.assetDao()
    val exportJobDao = database.exportJobDao()

    val importRepository: ImportRepository =
        ContentUriImportRepository(context, context.contentResolver, assetDao)

    /**
     * レンダラー。documentId → 原本URI の解決は AssetDao を正本とする。
     * TODO(ADR確認): URI失効時の availability 更新と disconnect 状態表示は次PRで統合。
     */
    val renderer: PhotoRenderer = SimpleBitmapPhotoRenderer(context) { documentId ->
        assetDao.get(documentId)?.let { entity ->
            if (entity.availability == com.imageshot.core.model.Asset.AVAILABILITY_OK) {
                Uri.parse(entity.sourceUri)
            } else null
        }
    }
}

/** プロセス単位のコンテナ保持。 */
class ImageShotApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
