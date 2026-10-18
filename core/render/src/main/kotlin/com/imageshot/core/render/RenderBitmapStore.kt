package com.imageshot.core.render

import android.graphics.Bitmap

/**
 * レンダリング結果 Bitmap の所有権管理（ネイティブバッファの dispose/RAII 契約に相当する
 * Kotlin/Bitmap 版）。key は `"<documentId>/<generationId>"`。
 *
 * - 文書ごとに最大 [maxPerDocument] 件の最新結果を保持し、超過分は recycle() で解放する。
 *   Compose 側は StateFlow 差し替え後すぐに旧 ImageBitmap を参照しないため、
 *   直近数件の recycle は実運用上安全を条件とする（クリア契約は明示呼出しのみ）。
 * - recycle() は所有者（本クラス）だけが呼ぶ。UI は get() の Bitmap を再利用描画のみに使う。
 */
class RenderBitmapStore(private val maxPerDocument: Int = DEFAULT_MAX_PER_DOCUMENT) {

    private val lock = Any()
    private val perDocument = LinkedHashMap<String, ArrayDeque<Bitmap>>()

    fun put(key: String, bitmap: Bitmap) {
        check(key.contains(KEY_SEPARATOR)) { "bitmap key must be \"documentId$KEY_SEPARATOR generationId\"" }
        synchronized(lock) {
            val docKey = documentKeyOf(key)
            val queue = perDocument.getOrPut(docKey) { ArrayDeque() }
            queue.addLast(bitmap)
            while (queue.size > maxPerDocument) {
                // 古い結果は参照切替後に解放。ガベージ前の二重参照期間は最大4フレーム相当。
                val evicted = queue.removeFirst()
                if (evicted !== bitmap && !evicted.isRecycled) evicted.recycle()
            }
        }
    }

    fun get(key: String): Bitmap? = synchronized(lock) {
        perDocument[documentKeyOf(key)]?.lastOrNull { !it.isRecycled }
    }

    /** 文書単位の解放（明示dispose契約。レンダラー close() と対で呼ぶ）。 */
    fun close(documentId: String) {
        synchronized(lock) {
            perDocument.remove(documentId)?.forEach { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            perDocument.values.forEach { queue ->
                queue.forEach { bmp -> if (!bmp.isRecycled) bmp.recycle() }
            }
            perDocument.clear()
        }
    }

    private fun documentKeyOf(key: String): String = key.substringBefore(KEY_SEPARATOR)

    companion object {
        const val KEY_SEPARATOR = "/"
        const val DEFAULT_MAX_PER_DOCUMENT = 3

        fun keyFor(documentId: String, generationId: Long): String =
            "$documentId$KEY_SEPARATOR$generationId"
    }
}
