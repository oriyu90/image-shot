package com.imageshot.core.render

import android.graphics.Bitmap
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.RenderRequest
import com.imageshot.core.model.RenderResult

/**
 * 描画契約（02_実装定義書.md §5 PhotoRenderer / §4.2 同値契約）。
 * - フレームごとに最新 generationId だけ適用し、古い結果は捨てる（ADR-011）。
 * - 失敗時に UInt エンジンを無通知で代替させない（ADR-014）: warnings/Failure を明示。
 */
interface PhotoRenderer {
    suspend fun render(request: RenderRequest, recipe: EditRecipe): ImageOpResult<RenderResult>

    /** 文書の無効化。fromRevision 以降のキャッシュを捨てる。 */
    fun invalidate(docId: String, fromRevision: Long)

    /** 全結果バッファを解放する。ネイティブバッファの所有権契約と同じ体裁で明示呼び出し。 */
    suspend fun close(docId: String)
}
