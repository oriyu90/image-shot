package com.imageshot.core.database

import com.imageshot.core.model.Asset
import com.imageshot.core.model.EditDocument
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.EditSnapshot
import com.imageshot.core.model.RecipeSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * payload / recipe の JSON 直列化とエンティティ⇔モデル変換。
 * DB内部の保存形式（JSON + schemaVersion）とバージョンをここに閉じ込めて、
 * 上位モジュールへはモデルのみ出す。
 */
object EditRecordCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 正規化済みイベント payload を保存用 JSON に変換。 */
    fun encodeEventPayload(payload: JsonObject): String = json.encodeToString(JsonObject.serializer(), payload)

    fun decodeEventPayload(payloadJson: String): JsonObject {
        val element = json.parseToJsonElement(payloadJson)
        return element as? JsonObject ?: error("event payload must be a JSON object")
    }

    /**
     * 永続行の payload 復元 + schemaVersion ゲート（ADR-022）。
     * - 未対応バージョン（将来バージョンを含む）は既定値へのフォールバックを行わず、require で明示失敗。
     * - 旧バージョンは移行実装後に受理範囲へ追加する（現行は 1 のみ）。
     */
    fun decodeEventPayload(payloadJson: String, payloadSchemaVersion: Int): JsonObject {
        require(payloadSchemaVersion in 1..EVENT_PAYLOAD_SCHEMA_VERSION) {
            "unsupported event payload schemaVersion: $payloadSchemaVersion"
        }
        return decodeEventPayload(payloadJson)
    }

    fun encodeRecipe(recipe: EditRecipe): String = json.encodeToString(EditRecipe.serializer(), recipe)

    /** スキーマ変更時は旧版読み出し+移行+再保存（§3.2）。現状は version 1 のみ。 */
    fun decodeRecipe(recipeJson: String, schemaVersion: Int): EditRecipe {
        require(schemaVersion == RecipeSchema.CURRENT) {
            "unsupported recipe schemaVersion: $schemaVersion"
        }
        return json.decodeFromString(EditRecipe.serializer(), recipeJson)
    }
}

/** payloadSchemaVersion はレシピスキーマに揃える。 */
val EVENT_PAYLOAD_SCHEMA_VERSION: Int = RecipeSchema.CURRENT

/** スナップショット recipeSchemaVersion も同様。 */
val RECIPE_SCHEMA_VERSION: Int = RecipeSchema.CURRENT

fun AssetEntity.toModel(): Asset = Asset(
    id = id,
    sourceUri = sourceUri,
    displayName = displayName,
    mimeGuess = mimeGuess,
    sniffedFormat = sniffedFormat,
    width = width,
    height = height,
    orientation = orientation,
    dateTaken = dateTaken,
    rawCameraId = rawCameraId,
    colorProfileHash = colorProfileHash,
    imageFingerprint = imageFingerprint,
    availability = availability,
    importedAt = importedAt,
)

fun Asset.toEntity(): AssetEntity = AssetEntity(
    id = id,
    sourceUri = sourceUri,
    displayName = displayName,
    mimeGuess = mimeGuess,
    sniffedFormat = sniffedFormat,
    width = width,
    height = height,
    orientation = orientation,
    dateTaken = dateTaken,
    rawCameraId = rawCameraId,
    colorProfileHash = colorProfileHash,
    imageFingerprint = imageFingerprint,
    availability = availability,
    importedAt = importedAt,
)

fun EditDocumentEntity.toModel(): EditDocument = EditDocument(
    id = id,
    assetId = assetId,
    schemaVersion = schemaVersion,
    currentRevision = currentRevision,
    sourceFingerprint = sourceFingerprint,
    lastCommittedAt = lastCommittedAt,
    checksum = checksum,
)

fun EditSnapshotEntity.toModel(): EditSnapshot = EditSnapshot(
    documentId = documentId,
    revisionNo = revisionNo,
    checksum = checksum,
    recipe = EditRecordCodec.decodeRecipe(recipeJson, recipeSchemaVersion),
)

fun EditSnapshot.toEntity(): EditSnapshotEntity = EditSnapshotEntity(
    documentId = documentId,
    revisionNo = revisionNo,
    recipeJson = EditRecordCodec.encodeRecipe(recipe),
    recipeSchemaVersion = RECIPE_SCHEMA_VERSION,
    checksum = checksum,
)
