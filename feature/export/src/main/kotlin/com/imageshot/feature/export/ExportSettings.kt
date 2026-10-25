package com.imageshot.feature.export

import com.imageshot.core.model.EditRecipe
import com.imageshot.core.database.EditRecordCodec
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * 出力先の表現。
 * - MEDIA_STORE: MediaStore（Photos/ Pictures コレクション）へ IS_PENDING=1 → 成功時 IS_PENDING=0（§8.2）。
 *   実URIは PUBLISHING 段で確定し、outputFinalUri に記録する。
 * - SAF: ACTION_CREATE_DOCUMENT でユーザーが選択した URI へストリーム書込み。
 *   SAF プロバイダーは原子的操作を保証しないため、部分出力は COMPLETE にしない（T-010）。
 */
@Serializable
data class ExportDestination(
    val kind: String,
    /** SAF のとき: CreateDocument 結果の content URI。MEDIA_STORE のときは null。 */
    val uri: String? = null,
) {
    companion object {
        const val KIND_MEDIA_STORE = "media_store"
        const val KIND_SAF = "saf"

        fun mediaStore() = ExportDestination(KIND_MEDIA_STORE)
        fun saf(uri: String) = ExportDestination(KIND_SAF, uri)
    }
}

/**
 * 書き出し要求本体（ジョブの設定正本）。
 * - DB（export_jobs）は state/progress/attempt/errorCode の正本。設定本体は
 *   ExportJobRepository の request ファイル（cacheDir配下）にJSONで保持する。
 * 【既知の限界】request ファイルは cacheDir のため、キャッシュ消去で消える。
 *   完全な永続化には export_jobs への設定カラム追加（DBスキャマ変更・別PR）が必要。報告書参照。
 */
@Serializable
data class ExportJobRequest(
    val documentId: String,
    val recipe: EditRecipe,
    val recipeRevision: Long,
    val format: String,
    /** 1..100（PNGでは意味を持たないが常時要求）。 */
    val quality: Int,
    /** 最長辺リサイズ上限px。null は原寸。 */
    val maxEdgePx: Int?,
    val destination: ExportDestination,
    /** 名称生成の基準となる元アセット表示名。 */
    val baseFileName: String? = null,
    /** PUBLISHING 成功後に記録される実出力URI（MediaStore確定URL）。 */
    val outputFinalUri: String? = null,
    /**
     * レンダラー（ExportBitmapRenderer）が SUCCESS 時に付与した issue 警告一覧
     * （ADR-014: 無音に捨てない。UI 表示経路は未実装〔既知の制限〕で、request ファイル側に
     * 記録して残す。DB schema 変更なし → export_jobs テーブルはスキーマ不変）。 */
    val issueNotes: List<String> = emptyList(),
)

/** 設定ハッシュ: 同一設定かの判定・settingsHash カラム用。純Kotlin。 */
object ExportSettingsHash {
    fun compute(
        documentId: String,
        recipeRevision: Long,
        recipe: EditRecipe,
        format: String,
        quality: Int,
        maxEdgePx: Int?,
        destinationKind: String,
    ): String {
        val recipeJson = EditRecordCodec.encodeRecipe(recipe)
        val payload = buildString {
            append("doc=").append(documentId).append(';')
            append("rev=").append(recipeRevision).append(';')
            append("recipeSha=").append(recipeJson).append(';')
            append("format=").append(format).append(';')
            append("quality=").append(quality).append(';')
            append("maxEdge=").append(maxEdgePx ?: "full").append(';')
            append("dest=").append(destinationKind).append(';')
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
    }
}
