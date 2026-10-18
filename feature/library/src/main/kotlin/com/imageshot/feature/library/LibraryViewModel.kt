package com.imageshot.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imageshot.core.database.AssetDao
import com.imageshot.core.database.toModel
import com.imageshot.core.model.Asset
import com.imageshot.core.model.ErrorCode
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.ImportPolicy
import com.imageshot.core.model.ImportProbe
import com.imageshot.core.storage.ImportRepository
import com.imageshot.core.storage.UriAccessibility
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ライブラリViewModel。
 * - DB（Room WAL）を正本に、UI状態は単一の state flow から派生させる（02_実装定義書.md §10）。
 * - 取り込み中は明示的な状態表示とし、失敗・部分対応を黙って成功扱いにしない（ADR-014）。
 * - sha-256 ファンガープリントは遅延実行（U01）で、登録応答を待たせない。
 */
class LibraryViewModel(
    private val assetDao: AssetDao,
    private val importRepository: ImportRepository,
) : ViewModel() {

    /** ローカル検索・ソートのMVP範囲（撮影日順/表示名順のみ。M2: メタデータ検索）。 */
    enum class AssetSortOrder { DATE_TAKEN_DESC, DISPLAY_NAME_ASC }

    /** 再関連付けの結果（U01 切断状態からの導線）。 */
    sealed interface RelinkResult {
        data class Relinked(val assetId: String, val warnings: List<String>) : RelinkResult

        /** 指紋照合不一致: 別ファイルへの関連付けは黙って受けない。 */
        data class FingerprintMismatch(val detail: String) : RelinkResult

        data class Failed(val code: ErrorCode, val detail: String?) : RelinkResult
    }

    data class UiState(
        val assets: List<Asset> = emptyList(),
        val importing: Boolean = false,
        /** バッチ取り込みの進捗（処理済み / 合計）。単発は (0,0)。 */
        val importBatchDone: Int = 0,
        val importBatchTotal: Int = 0,
        /** 検査・取り込み・可用性操作の結果ログ。ユーザーに見せる状態そのまま。 */
        val messages: List<String> = emptyList(),
        val searchQuery: String = "",
        val sortOrder: AssetSortOrder = AssetSortOrder.DATE_TAKEN_DESC,
        val sweepingAvailability: Boolean = false,
    )

    private val importing = MutableStateFlow(false)
    private val batch = MutableStateFlow(0 to 0)
    private val messages = MutableStateFlow<List<String>>(emptyList())
    private val sortOrder = MutableStateFlow(AssetSortOrder.DATE_TAKEN_DESC)
    private val searchQuery = MutableStateFlow("")
    private val sweeping = MutableStateFlow(false)

    /** ソート/検索（表示名・撮影日）に沿った asset flow。Room DAO で完結させる。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val observedAssets = combine(sortOrder, searchQuery) { order, query ->
        order to escapeLike(query)
    }.flatMapLatest { (order, query) ->
        when (order) {
            AssetSortOrder.DATE_TAKEN_DESC -> assetDao.observeByDateTaken(query)
            AssetSortOrder.DISPLAY_NAME_ASC -> assetDao.observeByDisplayName(query)
        }.map { entities -> entities.map { it.toModel() } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState> = combine(
        combine(sortOrder, searchQuery, sweeping) { order, query, isSweeping ->
            Triple(order, query, isSweeping)
        },
        observedAssets,
        importing.asStateFlow(),
        batch.asStateFlow(),
        messages.asStateFlow(),
    ) { meta, assets, isImporting, batchProgress, msgs ->
        UiState(
            assets = assets,
            importing = isImporting,
            importBatchDone = batchProgress.first,
            importBatchTotal = batchProgress.second,
            messages = msgs,
            searchQuery = meta.second,
            sortOrder = meta.first,
            sweepingAvailability = meta.third,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState(),
    )

    /** 取り込み前の対応判定（U01: 非対応は明示）。ネットワークアクセスなし。 */
    suspend fun inspect(uri: String): ImportProbe? =
        when (val r = importRepository.inspect(android.net.Uri.parse(uri))) {
        is ImageOpResult.Success -> r.value
        is ImageOpResult.Failure -> {
            appendMessage("検査失敗: ${r.code}")
            null
        }
        is ImageOpResult.Degraded -> {
            appendMessage("検査警告: ${r.code}")
            r.value
        }
    }

    /** 単発登録（原本複製なし、ADR-009）。SAF/Picker の永続権限要求は policy に従う。 */
    suspend fun register(uri: String, policy: ImportPolicy): ImageOpResult<String> =
        submitOne(uri, policy)

    /**
     * 複数URIの一括取り込み（U01）。1件ごとに inspect→register→遅延指紋計画を回し、
     * 非対応は「候補」として明示的に伝える。UIは進捗（done/total）を表示する。
     * @return 登録できた件数
     */
    suspend fun importUris(uris: List<String>, usePersistPermission: Boolean): Int {
        if (uris.isEmpty()) return 0
        importing.value = true
        batch.value = 0 to uris.size
        var ok = 0
        try {
            for (uri in uris) {
                val result = submitOne(uri, ImportPolicy(persistUriPermission = usePersistPermission))
                if (result is ImageOpResult.Success || result is ImageOpResult.Degraded) ok++
                batch.value = (batch.value.first + 1) to uris.size
            }
        } finally {
            importing.value = false
        }
        appendMessage("取り込み完了: $ok / ${uris.size} 件（非対応候補・失敗は上記に明示）")
        return ok
    }

    /**
     * フォルダー取り込み（SAF ACTION_OPEN_DOCUMENT_TREE）。ルートの persistable grant を
     * 1回取得し、mime が image で始まる children を一括登録する。
     */
    suspend fun importTree(treeUri: String) {
        val tree = Uri.parse(treeUri)
        val grant = importRepository.takePersistableReadGrant(tree)
        if (grant is ImageOpResult.Failure) {
            appendMessage("フォルダー権限の永続化に失敗: ${grant.code}（このセッションのみ有効）")
        }
        when (val listing = importRepository.listImagesInTree(tree)) {
            is ImageOpResult.Failure -> appendMessage(
                "フォルダー走査失敗: ${listing.code} ${listing.detail.orEmpty()}",
            )
            is ImageOpResult.Degraded -> appendMessage("フォルダー走査警告: ${listing.warnings}")
            is ImageOpResult.Success -> {
                if (listing.value.isEmpty()) {
                    appendMessage("対象画像がフォルダー直下に見つかりません（サブフォルダーは未走査）")
                } else {
                    // 子documentは tree grant で読めるため、個別の persist 取得は行わない。
                    importUris(listing.value, usePersistPermission = false)
                }
            }
        }
    }

    /** URI可用性の再検査（U01: 失効→切断状態表示→再関連付け導線）。原本を全量読まない。 */
    suspend fun refreshAvailability() {
        sweeping.value = true
        try {
            val assets = assetDao.getAllOnce()
            var revoked = 0
            var disconnected = 0
            var recovered = 0
            for (entity in assets) {
                val probed = importRepository.probeAvailability(Uri.parse(entity.sourceUri))
                val next = when (probed) {
                    UriAccessibility.OK -> Asset.AVAILABILITY_OK
                    UriAccessibility.REVOKED -> Asset.AVAILABILITY_REVOKED
                    UriAccessibility.DISCONNECTED -> Asset.AVAILABILITY_DISCONNECTED
                }
                if (next != entity.availability) {
                    assetDao.updateAvailability(entity.id, next)
                    if (probed == UriAccessibility.OK) recovered++
                }
                when (probed) {
                    UriAccessibility.REVOKED -> revoked++
                    UriAccessibility.DISCONNECTED -> disconnected++
                    UriAccessibility.OK -> Unit
                }
            }
            if (revoked > 0 || disconnected > 0) {
                appendMessage("URI失効: 権限失効 ${revoked}件 / 切断 ${disconnected}件 → セルの「再関連付け」から復旧")
            } else {
                appendMessage("可用性確認: ${assets.size}件 すべて接続（回復 ${recovered}件）")
            }
        } finally {
            sweeping.value = false
        }
    }

    /**
     * 再関連付け（切断・失効 asset に対するSAF再選択）。
     * - 旧指紋が判明している場合、新URIの指紋と一致するときだけ接続し直す。
     * - 不一致は黙って受けない（ADR-014）。
     * - 旧指紋が未計算（遅延中）の場合は警告付きで受け入れる。
     */
    suspend fun relink(assetId: String, newUri: String, persistPermission: Boolean = true): RelinkResult {
        val entity = assetDao.get(assetId)
            ?: return RelinkResult.Failed(ErrorCode.URI_REVOKED, "asset not found: $assetId").also {
                appendMessage("再関連付け失敗: 対象が見つかりません")
            }

        val warnings = mutableListOf<String>()
        val fingerprint = when (val fp = importRepository.computeFingerprint(Uri.parse(newUri))) {
            is ImageOpResult.Success -> fp.value
            is ImageOpResult.Failure -> return RelinkResult.Failed(fp.code, fp.detail).also {
                appendMessage("再関連付け失敗: ${fp.code} ${fp.detail.orEmpty()}")
            }
            is ImageOpResult.Degraded -> fp.value
        }

        val stored = entity.imageFingerprint
        if (stored != null && stored != fingerprint) {
            appendMessage("再関連付け失敗: 選択ファイルが原本と一致しません（sha-256 照合不一致）")
            return RelinkResult.FingerprintMismatch(
                "stored=$stored chosen=$fingerprint",
            )
        }
        if (stored == null) {
            warnings.add("旧指紋が未計算のため同じ写真か照合できませんでした（登録時に遅延計画）")
        }

        var grantOk = true
        if (persistPermission) {
            grantOk = importRepository.takePersistableReadGrant(Uri.parse(newUri)) is ImageOpResult.Success
            if (!grantOk) {
                warnings.add("persistable permission が取得できません（再起動後は再度関連付けが必要）")
            }
        }
        assetDao.updateSourceUri(assetId, newUri, Asset.AVAILABILITY_OK)
        assetDao.updateFingerprint(assetId, stored ?: fingerprint)

        appendMessage("再関連付け完了: ${entity.displayName}")
        return RelinkResult.Relinked(assetId, warnings)
    }

    /**
     * 遅延 sha-256 の一括バックフィル（アプリ起動後・アイドル時を想定）。
     * 失敗したassetは次回も対象に残る（null＝不明として扱う）。
     */
    fun backfillPendingFingerprints() {
        viewModelScope.launch {
            for (asset in assetDao.findPendingFingerprint()) {
                when (val r = importRepository.ensureFingerprint(asset.id)) {
                    is ImageOpResult.Failure -> appendMessage(
                        "指紋計算に失敗: ${asset.displayName} (${r.code})",
                    )
                    else -> Unit
                }
            }
        }
    }

    fun setSortOrder(order: AssetSortOrder) {
        if (sortOrder.value != order) sortOrder.value = order
    }

    fun setSearchQuery(query: String) {
        if (searchQuery.value != query) searchQuery.value = query
    }

    /** 1件の inspect→register と、成功時の遅延指紋計画。 */
    private suspend fun submitOne(uri: String, policy: ImportPolicy): ImageOpResult<String> {
        val result = importRepository.register(Uri.parse(uri), policy)
        when (result) {
            is ImageOpResult.Success -> {
                appendMessage("取り込みOK: ${labelOr(uri)}")
                scheduleFingerprintBackfill(result.value)
            }
            is ImageOpResult.Failure -> appendMessage(
                "取り込み失敗: ${labelOr(uri)} → ${result.code} ${result.detail.orEmpty()}",
            )
            is ImageOpResult.Degraded -> {
                appendMessage("取り込み警告: ${labelOr(uri)} → ${result.warnings.joinToString(" / ")}")
                scheduleFingerprintBackfill(result.value)
            }
        }
        return result
    }

    /** 遅延計画: 応答を待たせずバックグラウンドで sha-256 を確定させる。 */
    private fun scheduleFingerprintBackfill(assetId: String) {
        viewModelScope.launch {
            when (val r = importRepository.ensureFingerprint(assetId)) {
                is ImageOpResult.Failure -> appendMessage(
                    "指紋の遅延計算に失敗: ${r.code} ${r.detail.orEmpty()}",
                )
                else -> Unit
            }
        }
    }

    private fun appendMessage(text: String) {
        messages.value = (messages.value + text).takeLast(MESSAGES_LIMIT)
    }

    private fun escapeLike(query: String): String =
        query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun labelOr(uri: String): String = Uri.parse(uri).lastPathSegment ?: uri

    private companion object {
        const val MESSAGES_LIMIT = 12
    }
}
