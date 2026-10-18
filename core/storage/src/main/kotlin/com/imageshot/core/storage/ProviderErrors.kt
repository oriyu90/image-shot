package com.imageshot.core.storage

import java.io.FileNotFoundException
import java.io.IOException

/**
 * ContentProvider 層の失敗を ADR-014（音のない障害より説明可能な機能縮減）に沿って分類する。
 * モデル層 ErrorCode は 01_製品仕様書.md §11 の分類で固定されているため、純Kotlin側で
 * 例外種別→診断理由を確定し、repository が ErrorCode へ写像する。
 *
 * - REVOKED: 権限が失われた（再関連付けが必要）。
 * - GONE: 権限は有効だが実体が消えた（切断状態。原画像が存在することと誤認しない §8.1）。
 * - IO: provider 側の入出力失敗（リトライ可能なことが多い）。
 * - UNEXPECTED: 分類不能。無通知で続行しない。
 */
internal sealed class ProviderFailure(val explain: String) {
    object PermissionRevoked : ProviderFailure("persistable grant 失効")

    object ContentGone : ProviderFailure("provider 上に実体が無い（移動/削除/未同期）")

    object OutOfMemory : ProviderFailure("メモリ不足（§11 OUT_OF_MEMORY・低品質降格対象）")

    class ProviderIo(message: String?) : ProviderFailure("provider IO error: ${message.orEmpty()}")

    class Unexpected(message: String?) : ProviderFailure("unexpected: ${message.orEmpty()}")
}

internal object ProviderErrorClassifier {
    fun classify(e: Throwable): ProviderFailure = when (e) {
        is SecurityException -> ProviderFailure.PermissionRevoked
        is FileNotFoundException -> ProviderFailure.ContentGone
        is OutOfMemoryError -> ProviderFailure.OutOfMemory
        is IOException -> ProviderFailure.ProviderIo(e.message)
        else -> ProviderFailure.Unexpected(e.message)
    }
}
