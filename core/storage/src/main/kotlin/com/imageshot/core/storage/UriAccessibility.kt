package com.imageshot.core.storage

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException

/**
 * URI 可用性の3状態（ADR-009 / 02_実装定義書.md §8.1）。
 * 「revoked（権限失効）」と「disconnected（実体消失・未同期）」を混同しない。
 */
enum class UriAccessibility {
    OK,
    /** persistable grant / picker grant が失われた。再関連付け（再選択）が必要。 */
    REVOKED,
    /** 権限は残っているが実体が消えた。切断状態として表示し、原画像の存在を誤認させない。 */
    DISCONNECTED,
}

/**
 * 開いて1バイト参照するだけの軽量probe。画像本体の全量展開は行わない（U01 AC: 巨大画像で
 * 即時フル展開しない）。DataSourceの例外種別を revoked / disconnected に確定させる。
 */
internal object UriAccessibilityProbe {

    fun probe(resolver: ContentResolver, uri: Uri): UriAccessibility = try {
        val stream = resolver.openInputStream(uri)
            ?: return UriAccessibility.DISCONNECTED
        stream.use { s -> runCatching { s.read() } }
        UriAccessibility.OK
    } catch (e: SecurityException) {
        UriAccessibility.REVOKED
    } catch (e: FileNotFoundException) {
        UriAccessibility.DISCONNECTED
    } catch (e: IOException) {
        // provider 到達不能（USB未接続・クラウド未同期等）は切断扱い。
        UriAccessibility.DISCONNECTED
    }
}
