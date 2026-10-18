package com.imageshot.feature.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imageshot.core.database.ExportJobDao
import com.imageshot.core.model.ExportProgress
import com.imageshot.core.model.ErrorCode
import com.imageshot.core.model.ExportJob
import com.imageshot.core.model.ExportRequest
import com.imageshot.core.model.ImageOpResult
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 書き出しViewModel（ADR-010の最小骨格）。
 * - キューは DB 永続 ExportJob。状態遷移は §8.2 の2段階コミットに従う。
 * - WorkWriter 実体（WorkManager）は Sprint C で導入。この雛形は QUEUED の登録と観測のみ。
 *   TODO(ADR-010確認): RENDERING/ENCODING/VERIFYING/PUBLISHING の実働は WorkManager + staged output。
 */
class ExportViewModel(
    private val exportJobDao: ExportJobDao,
) : ViewModel() {

    data class ExportUiState(
        val jobs: List<ExportProgress> = emptyList(),
    )

    /** ジョブ一覧の観測。単一 StateFlow から派生させる（§10）。 */
    fun observeJobs(): StateFlow<ExportUiState> = exportJobDao.observeAll()
        .map { entities ->
            ExportUiState(
                jobs = entities.map { e ->
                    ExportProgress(
                        jobId = e.id,
                        state = runCatchingState(e.state),
                        progress = e.progress,
                        errorCode = e.errorCode?.let { code -> runCatching { ErrorCode.valueOf(code) }.getOrNull() },
                    )
                },
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ExportUiState(),
        )

    private fun runCatchingState(raw: String): ExportJob.State =
        runCatching { ExportJob.State.valueOf(raw) }.getOrDefault(ExportJob.State.FAILED_PERMANENT)

    /** 書き出し要求の登録（キュー投入のみ）。実処理は存在しないので QUEUED のままで明示。 */
    suspend fun enqueue(request: ExportRequest): ImageOpResult<String> {
        if (request.quality !in 0..100) {
            return ImageOpResult.Failure(ErrorCode.INSUFFICIENT_STORAGE, "quality out of range")
        }
        val now = System.currentTimeMillis()
        val job = com.imageshot.core.database.ExportJobEntity(
            id = UUID.randomUUID().toString(),
            documentId = request.documentId,
            inputFingerprint = request.settingsHash, // TODO: 実指紋はDBから取得（ADR-010）
            recipeRevision = request.recipeRevision,
            settingsHash = request.settingsHash,
            outputUri = request.outputUri,
            state = ExportJob.State.QUEUED.name,
            progress = 0,
            attempt = 0,
            errorCode = null,
            createdAt = now,
            updatedAt = now,
        )
        exportJobDao.enqueue(job)
        return ImageOpResult.Success(job.id)
    }
}
