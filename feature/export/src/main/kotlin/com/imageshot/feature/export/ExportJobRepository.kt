package com.imageshot.feature.export

import com.imageshot.core.database.ExportJobDao
import com.imageshot.core.model.ErrorCode
import com.imageshot.core.model.ExportJob
import com.imageshot.core.model.ExportProgress
import com.imageshot.core.model.ImageOpResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * ExportJob キューの正本管理（ADR-010）。
 * - DB（export_jobs）が state / progress / attempt / errorCode の正本（§8 ローカルデータ復旧）。
 * - 設定本体（ExportJobRequest）は request ファイルに JSON で保持し、VMで作成・Workerで読む。
 *
 * 【既知の限界（明示・未解決課題として報告）】
 * - request ファイルは cacheDir 配下。キャッシュ消去で失われる。request が無いジョブは
 *   recoverInterrupted() で FAILED_PERMANENT に分類され、再試行には UI からの設定再入力が必要。
 *   恒久対応は export_jobs への設定ペイロードカラム追加（DBスキャマ変更＝別PR）。
 * - MediaStore pending 行・SAF 半端物の OS 側ふるまいは実機検証項目（本モジュールの単体テスト対象外）。
 */
class ExportJobRepository(
    private val dao: ExportJobDao,
    private val stagingDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ---------- staging / request ファイル ----------

    fun ensureStagingDir(): File = stagingDir.apply { mkdirs() }

    /** エンコード済みバイトの一時置き（VERIFYING のフル・ハッシュ確認対象）。 */
    fun stagedFileFor(jobId: String): File = File(ensureStagingDir(), "$jobId$STAGED_SUFFIX")

    fun requestFileFor(jobId: String): File = File(ensureStagingDir(), "$jobId$REQUEST_SUFFIX")

    /** 設定を request ファイルへ書き込み。ジョブ作成時および最終URI記録時に呼ぶ。 */
    fun persistRequest(jobId: String, request: ExportJobRequest): ImageOpResult<Unit> =
        runCatching {
            requestFileFor(jobId).writeText(
                json.encodeToString(ExportJobRequest.serializer(), request),
                Charsets.UTF_8,
            )
        }.fold(
            onSuccess = { ImageOpResult.Success(Unit) },
            onFailure = { e ->
                ImageOpResult.Failure(ErrorCode.INSUFFICIENT_STORAGE, "request file persist failed: ${e.message}")
            },
        )

    fun loadRequest(jobId: String): ExportJobRequest? = runCatching {
        val file = requestFileFor(jobId)
        if (!file.isFile) return null
        json.decodeFromString(ExportJobRequest.serializer(), file.readText(Charsets.UTF_8))
    }.getOrNull()

    /** staging 置き場の消去（VERIFYING失敗/PUBLISHING 後/終端処理向け）。request ファイルは残す。 */
    suspend fun cleanupStaging(jobId: String) {
        stagedFileFor(jobId).delete()
    }

    // ---------- エンキュー ----------

    suspend fun enqueue(request: ExportJobRequest): ImageOpResult<String> {
        if (request.documentId.isBlank()) {
            return ImageOpResult.Failure(ErrorCode.UNSUPPORTED_CODEC, "documentId is blank")
        }
        if (request.recipe.documentId != request.documentId) {
            return ImageOpResult.Failure(
                ErrorCode.UNSUPPORTED_CODEC,
                "recipe.documentId=${request.recipe.documentId} must match request.documentId",
            )
        }
        when (val v = ExportFormatValidator.validate(request.format, request.quality, request.maxEdgePx)) {
            is ImageOpResult.Failure -> return v
            else -> {}
        }
        if (request.destination.kind == ExportDestination.KIND_SAF && request.destination.uri.isNullOrBlank()) {
            return ImageOpResult.Failure(ErrorCode.URI_REVOKED, "SAF destination requires an explicit URI")
        }

        val id = UUID.randomUUID().toString()
        val persisted = persistRequest(id, request)
        if (persisted is ImageOpResult.Failure) return persisted

        val now = clock()
        val entity = com.imageshot.core.database.ExportJobEntity(
            id = id,
            documentId = request.documentId,
            inputFingerprint = request.recipe.source.fingerprint,
            recipeRevision = request.recipeRevision,
            settingsHash = ExportSettingsHash.compute(
                documentId = request.documentId,
                recipeRevision = request.recipeRevision,
                recipe = request.recipe,
                format = request.format,
                quality = request.quality,
                maxEdgePx = request.maxEdgePx,
                destinationKind = request.destination.kind,
            ),
            outputUri = request.destination.uri.orEmpty(),
            state = ExportJob.State.QUEUED.name,
            progress = 0,
            attempt = 0,
            errorCode = null,
            createdAt = now,
            updatedAt = now,
        )
        val inserted = dao.enqueue(entity)
        if (inserted == -1L) {
            return ImageOpResult.Failure(ErrorCode.EXPORT_INTERRUPTED, "export job insert skipped (DB conflict)")
        }
        return ImageOpResult.Success(id)
    }

    // ---------- 観測・状態 ----------

    suspend fun get(jobId: String): ExportJob? =
        dao.observe(jobId).firstOrNull()?.let { toJob(it) }

    fun observe(jobId: String): Flow<ExportProgress?> =
        dao.observe(jobId).map { e -> e?.let { toProgress(it) } }

    fun observeAll(): Flow<List<ExportProgress>> =
        dao.observeAll().map { list -> list.map { toProgress(it) } }

    /** 状態機械の遷移を唯一のDAO書き込み窓口として集約する。 */
    suspend fun transition(
        jobId: String,
        state: ExportJob.State,
        progress: Int,
        errorCode: ErrorCode?,
        attempt: Int,
    ) {
        dao.transition(
            id = jobId,
            state = state.name,
            progress = progress,
            errorCode = errorCode?.name,
            attempt = attempt,
            updatedAt = clock(),
        )
    }

    // ---------- 復旧（起動時・次回Worker起動時の走査） ----------

    /**
     * §8.2 / ADR-010: busy（PREPARING..PUBLISHING）で止まっているジョブを走査し、
     * request ファイルの有無で再実行可否を判定する。能な半端物はここで廃棄する。
     * - request あり → FAILED_RETRYABLE（staging を消してフル再実行）
     * - request なし → FAILED_PERMANENT（設定正本が失われている。UIから再設定が必要）
     */
    suspend fun recoverInterrupted(): List<ExportJob> {
        val busyJobs = dao.findIncompleteRecovering()
        val recovered = mutableListOf<ExportJob>()
        for (entity in busyJobs) {
            val requestExists = requestFileFor(entity.id).isFile
            val targetState =
                if (requestExists) ExportJob.State.FAILED_RETRYABLE
                else ExportJob.State.FAILED_PERMANENT
            dao.transition(
                id = entity.id,
                state = targetState.name,
                progress = entity.progress,
                errorCode = ErrorCode.EXPORT_INTERRUPTED.name,
                attempt = entity.attempt,
                updatedAt = clock(),
            )
            stagedFileFor(entity.id).delete()
            recovered += toJob(entity).copy(state = targetState, errorCode = ErrorCode.EXPORT_INTERRUPTED)
        }
        return recovered
    }

    // ---------- 内部 ----------

    private fun toJob(e: com.imageshot.core.database.ExportJobEntity): ExportJob = ExportJob(
        id = e.id,
        documentId = e.documentId,
        inputFingerprint = e.inputFingerprint,
        recipeRevision = e.recipeRevision,
        settingsHash = e.settingsHash,
        outputUri = e.outputUri,
        state = runCatching { ExportJob.State.valueOf(e.state) }.getOrDefault(ExportJob.State.FAILED_PERMANENT),
        progress = e.progress,
        attempt = e.attempt,
        errorCode = e.errorCode?.let { runCatching { ErrorCode.valueOf(it) }.getOrNull() },
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
    )

    private fun toProgress(e: com.imageshot.core.database.ExportJobEntity): ExportProgress = ExportProgress(
        jobId = e.id,
        state = runCatching { ExportJob.State.valueOf(e.state) }.getOrDefault(ExportJob.State.FAILED_PERMANENT),
        progress = e.progress,
        errorCode = e.errorCode?.let { runCatching { ErrorCode.valueOf(it) }.getOrNull() },
    )

    private companion object {
        const val STAGED_SUFFIX = ".part"
        const val REQUEST_SUFFIX = ".request.json"
    }
}
