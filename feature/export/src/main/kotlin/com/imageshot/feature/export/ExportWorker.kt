package com.imageshot.feature.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.imageshot.core.database.AssetDao
import com.imageshot.core.model.Asset
import com.imageshot.core.model.ErrorCode
import com.imageshot.core.model.ExportJob
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.render.ExportBitmapRenderer
import com.imageshot.core.render.ExportRenderSpec
import com.imageshot.core.render.Stage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * 書き出し処理の実体（ADR-010: 再開可能ジョブ）。WorkManager CoroutineWorker。
 * - 状態遷移は §8.2 の2段階コミット。DB（export_jobs）が state/progress/attempt/errorCode の正本。
 * - 設定正本（ExportJobRequest）は request ファイル。Worker は jobId でそれを読む。
 * - 失敗は単一の classifyFailure で分類し、部分出力を COMPLETE にしない（T-010）。
 *
 * 手動DI: 依存は ExportWorkerDeps.install() を AppContainer 側で実行する
 * （デフォルト WorkerFactory の反射生成 (Context, WorkerParameters) を壊さないため）。
 */
class ExportWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val deps = ExportWorkerDeps.installed()
            ?: return Result.failure() // 結線漏れ。実行不能を成功扱いしない（ADR-014）。
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()

        val job = deps.repository.get(jobId) ?: return Result.failure()
        when (job.state) {
            ExportJob.State.COMPLETE -> return Result.success()
            ExportJob.State.CANCELLED -> {
                deps.repository.cleanupStaging(jobId)
                return Result.success()
            }
            ExportJob.State.FAILED_PERMANENT -> return Result.failure()
            else -> {} // QUEUED / FAILED_RETRYABLE / 復旧対象の busy 状態
        }

        val attempt = job.attempt + 1
        if (attempt > MAX_ATTEMPTS) {
            deps.repository.transition(
                jobId,
                ExportJob.State.FAILED_PERMANENT,
                job.progress,
                ErrorCode.EXPORT_INTERRUPTED,
                attempt,
            )
            return Result.failure()
        }
        return runPipeline(deps, jobId, attempt)
    }

    // ---------- §8.2 2段階コミット ----------

    private suspend fun runPipeline(
        deps: ExportWorkerDeps.DepsEntry,
        jobId: String,
        attempt: Int,
    ): Result {
        val repository = deps.repository

        // ---- PREPARING（設定正本の存在検査・staging 余裕容量検査） ----
        repository.transition(jobId, ExportJob.State.PREPARING, 3, null, attempt)
        val request = repository.loadRequest(jobId)
        if (request == null) {
            // cacheDir消去等で設定正本が失われた。設定なしの再実行は不能（UIから再設定が必要）。
            repository.transition(jobId, ExportJob.State.FAILED_PERMANENT, 3, ErrorCode.EXPORT_INTERRUPTED, attempt)
            return Result.failure()
        }
        val stagedFile = repository.stagedFileFor(jobId)
        val usable = stagedFile.parentFile?.usableSpace ?: 0L
        if (usable <= MIN_USABLE_SPACE_BYTES) {
            repository.transition(jobId, ExportJob.State.FAILED_RETRYABLE, 3, ErrorCode.INSUFFICIENT_STORAGE, attempt)
            return Result.retry()
        }
        if (isStopped) return cancelled(repository, jobId, attempt)

        // ---- 入力URI解決（assetDao 正本）。availability が OK 以外は原本存在を誤認しない（§8.1）----
        val asset = deps.assetDao.get(request.documentId)
        val sourceUri = if (asset != null && asset.availability == Asset.AVAILABILITY_OK) {
            Uri.parse(asset.sourceUri)
        } else null
        if (sourceUri == null) {
            repository.transition(jobId, ExportJob.State.FAILED_RETRYABLE, 5, ErrorCode.URI_REVOKED, attempt)
            return Result.retry()
        }

        // ---- RENDERING / ENCODING（同一ノード実装・フル解像度） ----
        repository.transition(jobId, ExportJob.State.RENDERING, 10, null, attempt)
        val renderOutcome = coroutineScope {
            // 進捗/状態はDB正本。onStage は非suspendコールバックのため、順序保証つき writer を別コルーチンに分離する。
            val stageWrites = Channel<Pair<ExportJob.State, Int>>(Channel.UNLIMITED)
            val progressWriter = launch {
                var lastState: ExportJob.State = ExportJob.State.RENDERING
                for ((nextState, percent) in stageWrites) {
                    // 遷移合法性は ExportStateMachine の契約に従う（RENDERING/ENCODING 隣接遷移）。
                    if (nextState != lastState && ExportStateMachine.canTransition(lastState, nextState)) {
                        repository.transition(jobId, nextState, percent, null, attempt)
                        lastState = nextState
                    } else if (nextState == lastState) {
                        repository.transition(jobId, lastState, percent, null, attempt)
                    }
                }
            }
            val outcome = deps.renderer.render(
                source = sourceUri,
                recipe = request.recipe,
                spec = ExportRenderSpec(
                    format = request.format,
                    quality = request.quality,
                    maxEdgePx = request.maxEdgePx,
                ),
                target = stagedFile.outputStream().buffered(),
                shouldCancel = { isStopped },
                onStage = { stage, percent ->
                    val nextState = if (stage == Stage.ENCODE) ExportJob.State.ENCODING else ExportJob.State.RENDERING
                    stageWrites.trySend(nextState to percent)
                },
            )
            stageWrites.close()
            progressWriter.join()
            outcome
        }
        when (renderOutcome) {
            is ImageOpResult.Failure -> {
                if (isStopped) return cancelled(repository, jobId, attempt)
                repository.cleanupStaging(jobId)
                val (state, code) = classifyFailure(renderOutcome.code)
                repository.transition(jobId, state, 10, code, attempt)
                return if (state == ExportJob.State.FAILED_RETRYABLE) Result.retry() else Result.failure()
            }
            is ImageOpResult.Degraded ->
                // Degraded は書き出し契約では返さない（rendererはSuccess/Failureのみ）。黙って続行させず扱いを明示する。
                throw IllegalStateException("renderer returned Degraded unexpectedly: ${renderOutcome.code}")
            is ImageOpResult.Success -> {}
        }
        val sha = renderOutcome.value.sha256Hex

        if (isStopped) return cancelled(repository, jobId, attempt)

        // ---- VERIFYING（staged ファイルの閉鎖後フル・ハッシュ照合；部分/破損はここで止める） ----
        repository.transition(jobId, ExportJob.State.VERIFYING, 97, null, attempt)
        val stagedHash = runCatching { fileSha256(stagedFile) }.getOrNull()
        if (stagedHash == null || stagedHash != sha || stagedFile.length() <= 0) {
            repository.cleanupStaging(jobId)
            repository.transition(jobId, ExportJob.State.FAILED_RETRYABLE, 97, ErrorCode.EXPORT_INTERRUPTED, attempt)
            return Result.retry()
        }

        // ---- PUBLISHING（MediaStore: IS_PENDING=1→検証→IS_PENDING=0 / SAF: ストリーム書込み+読み戻し検証） ----
        repository.transition(jobId, ExportJob.State.PUBLISHING, 98, null, attempt)
        val publishOutcome: ImageOpResult<Uri> = when (request.destination.kind) {
            ExportDestination.KIND_MEDIA_STORE -> publishToMediaStore(deps, request, stagedFile, sha)
            ExportDestination.KIND_SAF -> publishToSaf(request, deps.contentResolver, stagedFile, sha)
            else ->
                ImageOpResult.Failure(
                    ErrorCode.EXPORT_INTERRUPTED,
                    "unknown destination kind: ${request.destination.kind}",
                )
        }

        return when (publishOutcome) {
            is ImageOpResult.Failure -> {
                repository.cleanupStaging(jobId)
                val (state, _) = classifyFailure(publishOutcome.code)
                repository.transition(jobId, state, 99, publishOutcome.code, attempt)
                if (state == ExportJob.State.FAILED_RETRYABLE) Result.retry() else Result.failure()
            }
            is ImageOpResult.Success -> {
                // 最終出力URIの記録（DBには state 正本カラムのみ存在しない; request ファイル側に記録）
                repository.persistRequest(jobId, request.copy(outputFinalUri = publishOutcome.value.toString()))
                repository.transition(jobId, ExportJob.State.COMPLETE, 100, null, attempt)
                repository.cleanupStaging(jobId)
                Result.success()
            }
            is ImageOpResult.Degraded ->
                throw IllegalStateException("publisher returned Degraded unexpectedly: ${publishOutcome.code}")
        }
    }

    private suspend fun publishToMediaStore(
        deps: ExportWorkerDeps.DepsEntry,
        request: ExportJobRequest,
        stagedFile: File,
        sha: String,
    ): ImageOpResult<Uri> {
        val publisher = MediaStorePublisher(deps.contentResolver)
        val format = ExportFormat.fromName(request.format)
            ?: return ImageOpResult.Failure(ErrorCode.UNSUPPORTED_CODEC, "unknown format: ${request.format}")
        val base = ExportNaming.sanitizeBaseName(request.baseFileName ?: "photo")
        // §8.2: 既存と同名→新名生成。上書きはこのモジュールでは提供しない（別の明示承認を要する操作）。
        val uniqueName = ExportNaming.composeUniqueFileName(base, format.fileExtension, publisher.collectExistingNames())
        val allocated = publisher.allocatePending(uniqueName, format.mime)
        return when (allocated) {
            is ImageOpResult.Failure -> allocated
            is ImageOpResult.Success -> publisher.publish(allocated.value, stagedFile, sha)
            is ImageOpResult.Degraded ->
                throw IllegalStateException("MediaStore allocate returned Degraded unexpectedly: ${allocated.code}")
        }
    }

    private fun publishToSaf(
        request: ExportJobRequest,
        resolver: ContentResolver,
        stagedFile: File,
        sha: String,
    ): ImageOpResult<Uri> {
        val target = request.destination.uri?.takeIf { it.isNotBlank() }
            ?: return ImageOpResult.Failure(ErrorCode.URI_REVOKED, "SAF destination URI is blank")
        return SafDocumentPublisher(resolver).publish(Uri.parse(target), stagedFile, sha)
    }

    /**
     * 失敗の再試行可否分類（ADR-014）。
     * - 再試行系: OOM / 容量不足 / プロセス・I/O中断 / URI権限周り（ユーザー操作で解決し得る）。
     * - 恒久系: コーデック・メタデータ不整合（再施行しても同じ結果）。
     */
    private fun classifyFailure(code: ErrorCode): Pair<ExportJob.State, ErrorCode> = when (code) {
        ErrorCode.OUT_OF_MEMORY, ErrorCode.INSUFFICIENT_STORAGE,
        ErrorCode.EXPORT_INTERRUPTED, ErrorCode.URI_REVOKED,
        -> ExportJob.State.FAILED_RETRYABLE to code
        ErrorCode.UNSUPPORTED_CODEC, ErrorCode.UNSUPPORTED_CAMERA, ErrorCode.INVALID_ICC,
        ErrorCode.MALFORMED_METADATA, ErrorCode.PRESET_PARTIAL,
        -> ExportJob.State.FAILED_PERMANENT to code
        ErrorCode.GPU_CONTEXT_LOST -> ExportJob.State.FAILED_RETRYABLE to code
    }

    private suspend fun cancelled(repository: ExportJobRepository, jobId: String, attempt: Int): Result {
        // 未完成の staging を消去し CANCELLED 化。公開先の半端物は未検証の為、実機検証対象。
        repository.cleanupStaging(jobId)
        repository.transition(jobId, ExportJob.State.CANCELLED, 0, null, attempt)
        return Result.success()
    }

    /** staged ファイル全体の SHA-256（VERIFYING 段の正本計算・スキップしない）。 */
    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } >= 0) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        const val KEY_JOB_ID = "export.jobId"
        const val UNIQUE_WORK_PREFIX = "export-"
        const val MAX_ATTEMPTS = 3

        /** 安全側固定値: staging 前の実空間下限（§9.1 固定ローカル閾値、リモート設定不可）。 */
        const val MIN_USABLE_SPACE_BYTES = 16L * 1024 * 1024

        fun input(jobId: String) = androidx.work.Data.Builder()
            .putString(KEY_JOB_ID, jobId)
            .build()

        fun uniqueWorkName(jobId: String): String = "$UNIQUE_WORK_PREFIX$jobId"
    }
}

/**
 * 手動DI注入点。AppContainer から install() を呼ぶこと（報告書の AppContainer crib 参照）。
 * デフォルト WorkerFactory が反映生成できない問題を避けるため (Context, WorkerParameters) のみの
 * コンストラクタと、ここで集合依存を参照する。
 */
object ExportWorkerDeps {

    private var deps: DepsEntry? = null

    fun install(
        repository: ExportJobRepository,
        assetDao: AssetDao,
        renderer: ExportBitmapRenderer,
        contentResolver: ContentResolver,
    ) {
        deps = DepsEntry(repository, assetDao, renderer, contentResolver)
    }

    fun installed(): DepsEntry? = deps

    data class DepsEntry(
        val repository: ExportJobRepository,
        val assetDao: AssetDao,
        val renderer: ExportBitmapRenderer,
        val contentResolver: ContentResolver,
    )
}
