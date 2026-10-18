package com.imageshot.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * ImageShot 雑誌 DB（Room, WAL）。
 * - WAL を既定とし、書き込みはトランザクションでのみ実施する（ADR-007）。
 * - スキーマはschemas/へ書き出し、移行テスト対象とする。
 */
@Database(
    entities = [
        AssetEntity::class,
        EditDocumentEntity::class,
        EditRevisionEntity::class,
        EditSnapshotEntity::class,
        ExportJobEntity::class,
        ThumbnailEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ImageShotDatabase : RoomDatabase() {
    abstract fun assetDao(): AssetDao
    abstract fun editDocumentDao(): EditDocumentDao
    abstract fun editRevisionDao(): EditRevisionDao
    abstract fun editSnapshotDao(): EditSnapshotDao
    abstract fun exportJobDao(): ExportJobDao
    abstract fun thumbnailDao(): ThumbnailDao

    companion object {
        const val DATABASE_NAME = "imageshot.db"

        /**
         * 手動DI用のビルダー。
         * TODO(ADR確認): SQLiteDriver(BundledSQLiteDriver) を Room 2.8 の推奨経路として
         *  採用するかは別PRで検証してから差し替える。
         */
        fun build(context: Context): ImageShotDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ImageShotDatabase::class.java,
                DATABASE_NAME,
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
