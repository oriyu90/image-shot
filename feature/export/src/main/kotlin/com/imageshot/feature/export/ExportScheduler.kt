package com.imageshot.feature.export

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * ExportJob の OS適合ジョブ実行系の接続インターフェース（ADR-010）。
 * WorkManager 実装は以下。手動DI（AppContainer）から WorkManager を供給する（報告書の結線 crib 参照）。
 */
interface ExportJobScheduler {
    /** QUEUED のジョブを Worker に割当てる。同一ジョブの既存リクエストは置換する。 */
    fun schedule(jobId: String)

    /** 取消。実行中も停止させる（取消後の実状は DB に反映される）。 */
    fun cancel(jobId: String)
}

class WorkManagerExportJobScheduler(
    private val workManager: WorkManager,
) : ExportJobScheduler {

    override fun schedule(jobId: String) {
        val request = OneTimeWorkRequest.Builder(ExportWorker::class.java)
            .setInputData(ExportWorker.buildInputData(jobId))
            .setConstraints(Constraints.NONE)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(ExportWorker.uniqueWorkName(jobId), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(jobId: String) {
        workManager.cancelUniqueWork(ExportWorker.uniqueWorkName(jobId))
    }
}

/** ExportWorker への入力組み立て（jobId のみ。設定正本は request ファイル）。 */
fun ExportWorker.Companion.buildInputData(jobId: String): Data =
    Data.Builder().putString(ExportWorker.KEY_JOB_ID, jobId).build()
