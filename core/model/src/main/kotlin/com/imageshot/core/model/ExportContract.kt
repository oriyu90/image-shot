package com.imageshot.core.model

/**
 * 書き出しジョブ契約（§5 ExportRepository / §8.2 2段階コミット）。
 * 状態遷移: QUEUED -> PREPARING -> RENDERING -> ENCODING -> VERIFYING -> PUBLISHING -> COMPLETE
 */
@kotlinx.serialization.Serializable
data class ExportRequest(
    val documentId: String,
    val recipeRevision: Long,
    /** 書き出し先 URI（ユーザーが明示承認した箇所以外へ書き込まない）。 */
    val outputUri: String,
    val format: String,
    /** 0..100 */
    val quality: Int,
    val settingsHash: String,
)

@kotlinx.serialization.Serializable
data class ExportJob(
    val id: String,
    val documentId: String,
    val inputFingerprint: String,
    val recipeRevision: Long,
    val settingsHash: String,
    val outputUri: String,
    val state: State,
    /** 0..100 */
    val progress: Int,
    val attempt: Int,
    val errorCode: ErrorCode?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    enum class State {
        QUEUED, PREPARING, RENDERING, ENCODING, VERIFYING, PUBLISHING,
        COMPLETE, FAILED_RETRYABLE, FAILED_PERMANENT, CANCELLED,
    }
}

@kotlinx.serialization.Serializable
data class ExportProgress(
    val jobId: String,
    val state: ExportJob.State,
    val progress: Int,
    val errorCode: ErrorCode?,
)
