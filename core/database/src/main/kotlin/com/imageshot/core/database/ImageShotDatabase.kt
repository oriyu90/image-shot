package com.imageshot.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * ImageShot 標準 DB（Room, WAL）。
 * - WAL を既定とし、書き込みはトランザクションでのみ実施する（ADR-007）。
 * - SQLiteDriver（sqlite-bundled）は ADR-021 により採用見送り: Room 2.8.5 の Android
 *   実装で EditStore の db.withTransaction が SupportSQLiteOpenHelper.Factory を要求し、
 *   setDriver 版は実機でクラッシュ（2026-10-18 実証）。既定の framework 経路を使用する。
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
         * 手動DI用のビルダー（本番経路, ADR-021）。
         * - 既定経路（framework道家の SupportSQLiteOpenHelperFactory）を使用する。
         * - ADR-021: SQLiteDriver(sqlite-bundled) への setDriver は採用見送り。
         *   理由: Room 2.8.5 の Android 実装で EditStore が使う db.withTransaction が
         *   SupportSQLiteOpenHelper(.Factory) を要求し、setDriver 版は
         *   「Cannot return a SupportSQLiteOpenHelper」で実機クラッシュ（2026-10-18 実証）。
         * - WAL 要求は従来どおり WRITE_AHEAD_LOGGING。
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
