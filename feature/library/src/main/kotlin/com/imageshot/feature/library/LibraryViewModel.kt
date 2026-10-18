package com.imageshot.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imageshot.core.database.AssetDao
import com.imageshot.core.database.toModel
import com.imageshot.core.model.Asset
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.ImportPolicy
import com.imageshot.core.model.ImportProbe
import com.imageshot.core.storage.ImportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ライブラリViewModel。
 * - DB（Room WAL）を正本に、UI状態は単一の state flow から派生させる（02_実装定義書.md §10）。
 * - 取り込み中は明示的な状態表示とし、失敗・部分対応を黙って成功扱いにしない（ADR-014）。
 */
class LibraryViewModel(
    private val assetDao: AssetDao,
    private val importRepository: ImportRepository,
) : ViewModel() {

    data class UiState(
        val assets: List<Asset> = emptyList(),
        val importing: Boolean = false,
        /** 検査・取り込み操作の結果ログ。ユーザーに見せる状態そのまま。 */
        val messages: List<String> = emptyList(),
    )

    private val importing = MutableStateFlow(false)
    private val messages = MutableStateFlow<List<String>>(emptyList())

    val state: StateFlow<UiState> = combine(
        assetDao.observeAll(),
        importing.asStateFlow(),
        messages.asStateFlow(),
    ) { entities, isImporting, msgs ->
        UiState(
            assets = entities.map { it.toModel() },
            importing = isImporting,
            messages = msgs,
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

    /** 登録（原本複製なし、ADR-009）。SAFでの永続権限リクエストは呼び出し側の方針に従う。 */
    suspend fun register(uri: String, policy: ImportPolicy): ImageOpResult<String> {
        importing.value = true
        try {
            return when (val r = importRepository.register(android.net.Uri.parse(uri), policy)) {
                is ImageOpResult.Success -> {
                    appendMessage("取り込みOK: ${r.value.take(8)}…")
                    r
                }
                is ImageOpResult.Failure -> {
                    appendMessage("取り込み失敗: ${r.code} ${r.detail.orEmpty()}")
                    r
                }
                is ImageOpResult.Degraded -> {
                    appendMessage("取り込み警告: ${r.code}")
                    r
                }
            }
        } finally {
            importing.value = false
        }
    }

    private fun appendMessage(text: String) {
        messages.value = messages.value + text
    }
}
