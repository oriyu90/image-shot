package com.imageshot.feature.export

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import com.imageshot.core.model.ErrorCode
import com.imageshot.core.model.ImageOpResult
import java.io.File
import java.security.MessageDigest

/**
 * 出力先への公開（§8.2 PUBLISHING 段・T-010「部分出力を完了扱いしない」）。
 * 本ファイルは Android 実依存（ContentResolver/MediaStore/DocumentsContract）を含むため
 * JVM 単体テスト対象外。端末（実機 TB710FU）での動作検証を必須項目とする（報告書参照）。
 */

/** MediaStore による PUBLISHING。IS_PENDING=1 で書込み → 全体検証 → 成功時のみ IS_PENDING=0。 */
class MediaStorePublisher(
    private val resolver: ContentResolver,
) {

    /** 既存表示名を収集（重複回避の基盤）。クエリ失敗時は空集合＝suffix生成しない（限界として報告）。 */
    fun collectExistingNames(): Set<String> = runCatching {
        val out = mutableSetOf<String>()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$PUBLISH_RELATIVE_PATH%"),
            null,
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (c.moveToNext()) {
                c.getString(idx)?.takeIf { it.isNotBlank() }?.let { out += it }
            }
        }
        out.toSet()
    }.getOrDefault(emptySet())

    /** 行確保（IS_PENDING=1）。名前重複は collectExistingNames + ExportNaming.composeUniqueFileName で回避。 */
    fun allocatePending(displayName: String, mime: String): ImageOpResult<Uri> = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, PUBLISH_RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: return ImageOpResult.Failure(ErrorCode.EXPORT_INTERRUPTED, "MediaStore insert returned null")
        ImageOpResult.Success(uri)
    }.getOrElse { e ->
        when (e) {
            is SecurityException -> ImageOpResult.Failure(ErrorCode.URI_REVOKED, e.message)
            is java.io.IOException ->
                ImageOpResult.Failure(ErrorCode.INSUFFICIENT_STORAGE, "MediaStore insert failed: ${e.message}")
            else -> ImageOpResult.Failure(ErrorCode.EXPORT_INTERRUPTED, e.message)
        }
    }

    /**
     * 書込み（staged → pending URI）→ フル検証（SHA-256 全体照合）。
     * 検証不一致は成功扱いせず pending 行を破棄する（§8.2・T-010）。
     */
    fun publish(pendingUri: Uri, stagedFile: File, expectedSha256: String): ImageOpResult<Uri> {
        // 1) 書込み
        val copied = runCatching {
            resolver.openOutputStream(pendingUri, "w")?.use { out ->
                stagedFile.inputStream().use { input -> input.copyTo(out) }
                out.flush()
            } != null
        }.getOrDefault(false)
        if (!copied) {
            discard(pendingUri)
            return ImageOpResult.Failure(ErrorCode.EXPORT_INTERRUPTED, "pending stream write failed")
        }

        // 2) フル検証（IS_PENDING=1 のまま読み戻し・全バイト ハッシュ照合）
        val verified = runCatching { readBackSha256(resolver, pendingUri) == expectedSha256 }.getOrDefault(false)
        if (!verified) {
            discard(pendingUri)
            return ImageOpResult.Failure(
                ErrorCode.EXPORT_INTERRUPTED,
                "pending write hash mismatch (partial/failed write, pending row discarded)",
            )
        }

        // 3) 成功時のみ IS_PENDING=0（部分公開の抑止）
        val finalized = runCatching {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(pendingUri, values, null, null) > 0
        }.getOrDefault(false)
        if (!finalized) {
            discard(pendingUri)
            return ImageOpResult.Failure(ErrorCode.EXPORT_INTERRUPTED, "IS_PENDING=0 update failed, pending row discarded")
        }
        return ImageOpResult.Success(pendingUri)
    }

    /** pending 行の破棄（半端物の公開抑止）。失敗しても例外を伝播させない（実機検証項目）。 */
    fun discard(pendingUri: Uri) {
        runCatching { resolver.delete(pendingUri, null, null) }
    }

    private companion object {
        const val PUBLISH_RELATIVE_PATH = "Pictures/ImageShot/"
    }
}

/**
 * SAF（ACTION_CREATE_DOCUMENT 結果URI）へのストリーム書込み。
 * プロバイダーは原子的操作を保証しない（§8.2明示）。検証失敗時は best-effort で削除を試み、
 * 取り消せない場合も「部分出力を COMPLETE にしない」。残存する半端物は UI側で明示する。
 */
class SafDocumentPublisher(
    private val resolver: ContentResolver,
) {

    fun publish(target: Uri, stagedFile: File, expectedSha256: String): ImageOpResult<Uri> {
        // 1) 書込み
        val opened = runCatching {
            resolver.openOutputStream(target, "w")?.use { out ->
                stagedFile.inputStream().use { input -> input.copyTo(out) }
                out.flush()
            } != null
        }.getOrDefault(false)
        if (!opened) {
            return ImageOpResult.Failure(ErrorCode.URI_REVOKED, "SAF output stream unavailable (grant revoked?)")
        }

        // 2) フル検証（書き込み直後の全byte読み戻し）
        val readBack = runCatching { readBackSha256(resolver, target) }
        val verified = readBack.getOrNull()?.let { it == expectedSha256 } == true
        if (!verified) {
            // 部分出力の抑止。削除できる provider でのみ成功する（できなければ残存を UI で明示）。
            val discarded = discardPartial(target)
            val suffix = if (discarded) "discarded partial output" else "partial output may remain (provider does not support delete)"
            return ImageOpResult.Failure(ErrorCode.EXPORT_INTERRUPTED, "SAF write hash mismatch ($suffix)")
        }
        return ImageOpResult.Success(target)
    }

    /** provider が対応する場合のみ、半端物の削除を試行。削除不可の provider は false（UIで明示）。 */
    private fun discardPartial(target: Uri): Boolean = runCatching {
        resolver.delete(target, null, null) > 0
    }.getOrDefault(false)
}

/** 検証用の共通読み戻し（MediaStore側でも利用）。 */
internal fun readBackSha256(resolver: ContentResolver, uri: Uri): String? =
    runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } >= 0) digest.update(buf, 0, read)
            digest.digest().joinToString(separator = "") { "%02x".format(it) }
        }
    }.getOrNull()
