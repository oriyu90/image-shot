package com.imageshot.feature.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imageshot.core.database.AssetDao
import com.imageshot.core.database.EditStore
import com.imageshot.feature.export.R as ExportR
import com.imageshot.core.database.toModel
import com.imageshot.core.model.AdjustmentId
import com.imageshot.core.model.Asset
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.ErrorCode
import com.imageshot.core.model.ExportProgress
import com.imageshot.core.model.Strings
import com.imageshot.core.model.ImageOpResult
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 書き出しViewModel（ADR-010・02_実装定義書.md §8.2 二段階コミット）。
 *
 * 正本構造:
 * - エンキューは [ExportJobRepository.enqueue]（設定正本 ExportJobRequest の request ファイル
 *   生成を含む正本経路）のみ。旧デモ経路（DB 直書き・request 無しで PREPARING 必敗）は廃止。
 * - 対象文書の解決は [EditStore.ensureDocument]（エディタと同一の生成規約:
 *   既存文書はロード、無ければ既定レシピ・fingerprint="unscanned" フォールバックで作成）。
 * - WorkManager への割当（ExportJobScheduler.schedule）は app 層が VM 帰りの id で行う
 *   （VM は scheduler に依存しない・ADR-010）。
 * - M1 の出力先 UI は SAF CreateDocument のみ。CreateDocument は OS 側で常に新しい文書を
 *   作成するため既存の上書きは起こらない（§8.2「上書き選択を別の明示承認にする」:
 *   承認経路は M1 未提供）。MediaStore 公開は Worker が対応済みだが M1 では UI 未提供。
 * - 設定値の事前検査は [ExportFormatValidator]（専用 ErrorCode は core:model 未整備のため
 *   UNSUPPORTED_CODEC 分類を共有する・ExportFormatValidator KDoc 参照）。
 */
class ExportViewModel(
    /** 画面引数は assetId。正本 documentId は EditStore.ensureDocument が assetId から解決する。 */
    private val assetId: String,
    private val repository: ExportJobRepository,
    private val editStore: EditStore,
    private val assetDao: AssetDao,
    /** 文言取得（PR-27 G1）。app 層が Context.getString を結線する。VM 内に日本語リテラルは置かない。 */
    private val strings: Strings,
) : ViewModel() {

    /** 対象文書の表示/エンキューに必要な投影（読み取り専用。ここからレシピを書換えない）。 */
    data class DocumentInfo(
        val documentId: String,
        val assetId: String,
        val recipe: EditRecipe,
        val recipeRevision: Long,
        val sourceAsset: Asset?,
        /** 既定値から変化しているスライダー数（簡易サマリ。UI 表示用）。 */
        val nonDefaultSliderCount: Int,
        /** トーンカーブが既定でない（未実装警告の根拠と同じ判定）。 */
        val hasCurve: Boolean,
    )

    data class ExportSettingsUi(
        val document: DocumentInfo? = null,
        val documentError: String? = null,
        val format: ExportFormat = ExportFormat.JPEG,
        val quality: Int = DEFAULT_QUALITY,
        /** CreateDocument 結果の content URI（SAF）。null は未選択。 */
        val destinationUri: String? = null,
        val enqueuing: Boolean = false,
    )

    data class ExportUiState(
        val jobs: List<ExportProgress> = emptyList(),
    )

    private val document = MutableStateFlow<DocumentInfo?>(null)
    private val documentError = MutableStateFlow<String?>(null)
    private val formatFlow = MutableStateFlow(ExportFormat.JPEG)
    private val qualityFlow = MutableStateFlow(DEFAULT_QUALITY)
    private val destinationUri = MutableStateFlow<String?>(null)
    private val enqueuing = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            // エディタ（EditorViewModel.init）と同一の解決経路。DB 読み書きは viewModelScope。
            val asset = assetDao.get(assetId)?.toModel()
            when (val result =
                editStore.ensureDocument(assetId, asset?.imageFingerprint ?: "unscanned")
            ) {
                is ImageOpResult.Success -> {
                    documentError.value = null
                    document.value = toInfo(result.value, asset)
                }
                is ImageOpResult.Failure -> {
                    // ADR-014: 正本が作れない場合、代替入力で無通知に続行しない。
                    // code/detail は技術正本（そのまま表示）で文言テンプレートのみ res 参照。
                    documentError.value = strings.get(
                        ExportR.string.export_document_error_detail,
                        result.code.name,
                        result.detail.orEmpty(),
                    )
                    document.value = null
                }
                else -> {
                    documentError.value = "unexpected result from EditStore" // 非CJK・開発者向け正本（台帳記録）
                    document.value = null
                }
            }
        }
    }

    // ----- 公開API -----

    /** ジョブ一覧の観測（DB 正本 → ExportProgress への射影は repository が担う）。 */
    fun observeJobs(): StateFlow<ExportUiState> = repository.observeAll()
        .map { jobs -> ExportUiState(jobs = jobs) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ExportUiState(),
        )

    fun settings(): StateFlow<ExportSettingsUi> = combine(
        document,
        documentError,
        combine(formatFlow, qualityFlow) { f, q -> f to q },
        combine(destinationUri, enqueuing) { uri, busy -> uri to busy },
    ) { doc, error, fq, dest ->
        ExportSettingsUi(
            document = doc,
            documentError = error,
            format = fq.first,
            quality = fq.second,
            destinationUri = dest.first,
            enqueuing = dest.second,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ExportSettingsUi(),
    )

    fun chooseFormat(format: ExportFormat) {
        formatFlow.value = format
    }

    /** 画質スライダー（JPEG のみ無意味化せず 1..100 にクランプ。PNG では UI 側で N/A 表示）。 */
    fun setQuality(raw: Int) {
        qualityFlow.value = raw.coerceIn(ExportFormatValidator.MIN_QUALITY, ExportFormatValidator.MAX_QUALITY)
    }

    /** CreateDocument の結果受け取り（blank は破棄・戸籍上の選択として扱わない）。 */
    fun noteDestination(uri: String?) {
        destinationUri.value = uri?.takeIf { it.isNotBlank() }
    }

    /**
     * キュー投入（正本経路: repository.enqueue → request ファイル + DB 行）。
     * 成功で jobId を返す。WorkManager への割当は app 層が行う（呼出側責務・ADR-010）。
     */
    suspend fun enqueue(): ImageOpResult<String> {
        if (enqueuing.value) {
            return ImageOpResult.Failure(ErrorCode.EXPORT_INTERRUPTED, "enqueue already in progress")
        }
        val doc = document.value
            ?: return ImageOpResult.Failure(ErrorCode.MALFORMED_METADATA, "edit document not loaded")
        val uri = destinationUri.value
            ?: return ImageOpResult.Failure(ErrorCode.URI_REVOKED, "export destination not selected")
        val fmt = formatFlow.value
        val q = qualityFlow.value
        when (val v = ExportFormatValidator.validate(fmt.name, q, maxEdgePx = null)) {
            is ImageOpResult.Failure -> return v
            else -> {}
        }
        // 元ファイル名から拡張子を除いたものを基準名へ（Worker 側でも再度 sanitize される）。
        val baseFileName = ExportNaming.sanitizeBaseName(
            doc.sourceAsset?.displayName?.substringBeforeLast('.') ?: "photo",
        )
        enqueuing.value = true
        try {
            return repository.enqueue(
                ExportJobRequest(
                    documentId = doc.documentId,
                    recipe = doc.recipe,
                    recipeRevision = doc.recipeRevision,
                    format = fmt.name,
                    quality = q,
                    // M1: リサイズUIを表に出さない（原寸書き出し）。
                    maxEdgePx = null,
                    destination = ExportDestination.saf(uri),
                    baseFileName = baseFileName,
                ),
            )
        } finally {
            enqueuing.value = false
        }
    }

    // ----- 内部 -----

    private fun toInfo(state: EditStore.EditStoreState, asset: Asset?): DocumentInfo {
        val curve = state.recipe.develop.curve
        return DocumentInfo(
            documentId = state.documentId,
            assetId = state.assetId,
            recipe = state.recipe,
            recipeRevision = state.currentRevision,
            sourceAsset = asset,
            nonDefaultSliderCount = AdjustmentId.entries.count { id ->
                abs(id.sliderValueOf(state.recipe) - id.default) > 1e-9
            },
            hasCurve = curve.rgb != listOf(listOf(0.0, 0.0), listOf(1.0, 1.0)) ||
                curve.r.isNotEmpty() || curve.g.isNotEmpty() || curve.b.isNotEmpty(),
        )
    }

    companion object {
        /** PNG は可逆で画質パラメータが意味を持たない（§6 形式マトリクス）。UI 既定は JPEG。 */
        const val DEFAULT_QUALITY = 90
    }
}
