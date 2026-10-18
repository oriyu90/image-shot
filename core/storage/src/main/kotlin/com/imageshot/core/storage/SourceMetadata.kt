package com.imageshot.core.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore

/**
 * 判明するメタデータの簡易参照（U01 画面表示項目）。
 * EXIF全面解析は core:metadata 未導入のため行わず、MediaStore列 / DocumentsProvider列の
 * 簡易参照に留める（01_製品仕様書.md の求める「判明するメタデータ」のM1最小構成）。
 *
 * - すべて runCatching で覆い、null＝不明を不明として扱う（勝手な既定値を入れない・ADR-014）。
 * - local queryのみで、画像内容・GPSの外部送信は行わない（§12）。
 */
internal data class SourceMetadata(
    /** MediaStore DATE_TAKEN（epoch ms）。DocumentProvider は lastModified で代用する。 */
    val dateTakenMs: Long?,
    val orientation: Int?,
    val width: Int?,
    val height: Int?,
)

internal object SourceMetadataReader {

    fun read(context: android.content.Context, resolver: ContentResolver, uri: Uri): SourceMetadata? = runCatching {
        when {
            isMediaStoreImageUri(uri) -> readMediaStore(resolver, uri)
            DocumentsContract.isDocumentUri(context, uri) -> readDocument(resolver, uri)
            else -> null
        }
    }.getOrNull()

    private fun isMediaStoreImageUri(uri: Uri): Boolean =
        uri.scheme == "content" &&
            (uri.authority == "media" ||
                uri.authority?.startsWith("com.android.providers.media") == true)

    /** MediaStore 列の簡易参照（U01）。DATE_TAKEN は epoch ms。 */
    private fun readMediaStore(resolver: ContentResolver, uri: Uri): SourceMetadata? {
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
        val proj = arrayOf(
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        return resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            proj,
            "${MediaStore.Images.Media._ID} = ?",
            arrayOf(id.toString()),
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return null
            SourceMetadata(
                dateTakenMs = c.longOrNull(c.indexOrNull(MediaStore.Images.Media.DATE_TAKEN)),
                orientation = c.intOrNull(c.indexOrNull(MediaStore.Images.Media.ORIENTATION)),
                width = c.intOrNull(c.indexOrNull(MediaStore.Images.Media.WIDTH)),
                height = c.intOrNull(c.indexOrNull(MediaStore.Images.Media.HEIGHT)),
            )
        }
    }

    /** SAF ドキュメント: 判明するのは lastModified のみ（撮影日ではない点を明示して使う）。 */
    private fun readDocument(resolver: ContentResolver, uri: Uri): SourceMetadata? =
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val modified = c.longOrNull(
                c.indexOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            )
            SourceMetadata(dateTakenMs = modified, orientation = null, width = null, height = null)
        }

    private fun Cursor.indexOrNull(name: String): Int = getColumnIndex(name)

    private fun Cursor.longOrNull(index: Int): Long? {
        if (index < 0 || isNull(index)) return null
        return getLong(index)
    }

    private fun Cursor.intOrNull(index: Int): Int? {
        if (index < 0 || isNull(index)) return null
        return getInt(index)
    }
}
