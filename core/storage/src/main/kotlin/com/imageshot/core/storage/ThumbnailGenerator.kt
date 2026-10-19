package com.imageshot.core.storage

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.imageshot.core.database.ThumbnailDao
import com.imageshot.core.database.ThumbnailEntity
import com.imageshot.core.render.SourceDecode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 生成要求（PR-8）。sourceUri は DB（AssetEntity.sourceUri）と同じ文字列形式のまま受ける。
 * displayName は契約上受け取るが、経路名・ログ・cacheKey のいずれにも一切使用しない
 * （表示名由来の文字が書込起点に回り込まない防御。JVM 単体テストで assetId のみが
 * 経路を決定することを確認する）。
 */
data class ThumbnailSeed(
    val assetId: String,
    val sourceUri: String,
    val displayName: String,
)

/**
 * 1レベル分のエンコード成果（実機経路のみ生成する）。[ThumbnailGenerator.persistRows]
 * が ThumbnailEntity 行へ写像するときの入力。
 */
data class EncodedThumb(
    /** level taxonomy（PR-8 確定）: 0=最大辺256px、1=最大辺1024px。 */
    val level: Int,
    /** 書き出し済み実体ファイル。 */
    val file: File,
    /** byteSize は file.length() の実測値（推定ではない）。 */
    val byteSize: Long,
)

/**
 * サムネイル生成パイプライン（PR-8 / 未実装欠陥 F2）。原本に非破壊（ADR-009）。
 *
 * 契約（Entities.kt の level taxonomy に対する本PRの決定を含む）:
 * - level taxonomy: Entities.kt のコメントは「0=full, 段階数値は別定義」のみで数値定義が
 *   コード上存在しないため、本PRで確定する。**level 0 = 最大辺256px、level 1 = 最大辺1024px。**
 *   level 0 はライブラリグリッド（112dp セル）用、level 1 は将来の詳細表示/編集プレビュー用の中間行。
 *   レベルは「ソースがその最大辺を超える場合のみ」生成し、拡大は行わない（半端な小規模メディアは
 *   level 0 の原寸のみ）。
 * - 冪等: 既存行のある asset（[ThumbnailDao.forAsset] が非空）は早期に true を返し、
 *   再デコード・再エンコード・上書きを行わない（行が正本）。行が有っても実体ファイル欠落の
 *   場合も再生成しない（表示側は null フォールバックで従来のソースURI全量デコードを続行）。
 * - ファイル配置: `filesDir/thumbnails/<assetId>/_<level>.jpg`。ディレクトリ名は assetId
 *   （UUID書式）のみから決定し displayName は結合・使用されない。JPEG q90 固定。
 * - 行写像: レベルごとに1行。`cacheKey`=root相対パス、`contentHash`=エンコード実体のsha-256
 *   （[Fingerprinter]）、`accessedAt`=生成時刻、`byteSize`=実測ファイル長。
 *   不明フィールドへの作為的補完はしない（DB schema v1 のまま・移行は発生させない）。
 * - 失敗方針: 書込前に assetId 書式検証（書込不発）、デコード失敗は null 契約を維持
 *   （不明は不明のまま・代替画素を作らない・写真を消さない/参照しない）、エンコード失敗は
 *   **自前成果物のみ**掃除して asset 単位で1回だけ Log.w（[Companion.TAG]）→ false を返す。
 *   例外は呼出し元に解放しない（CancellationException 自体は伝搬させる: 取り消しを失敗に
 *   変換しない＝構造化並行性の維持）。asset を unavailable に更新させない。
 * - 並行性: [ensureForAssets] は [Companion.CONCURRENCY]=2 資産単位の Semaphore でバウンドし、
 *   資産内のレベル生成は逐次。
 *
 * 【JVM 単体テストの限界（正直な開示）】android.graphics 経路（bounds probe / 対象サンプルでの
 * デコード、createScaledBitmap、JPEG compress）はフレームワーク実装であり JVM では検証できない。
 * そのため bounds probe とデコードを関数パラメータとして差し替え可能にし、JVM では「既存行の
 * 冪等」「サンプル選定」「デコード失敗 → false・行不発」「assetId 書式の書込前拒否」
 * 「行写像（byteSize・cacheKey・contentHash・accessedAt）」を実コードで検証する。
 * **Bitmap 経路・Coil 表示パス・性能（NFR-10）は実機未測定。実機検証は PR-8 の計測後に
 * orchestrator が実施: NFR-10 未測定。**
 */
class ThumbnailGenerator internal constructor(
    /**
     * サムネイル root（filesDir/thumbnails）。作成は生成経路でのみ行い、参照だけの経路
     * （[bestDisplayPath] 等）ではディレクトリを作らない。
     */
    private val thumbnailRoot: File,

    /** 共有デコードパス（[SourceDecode]）用 resolver。実運用は非null。JVM 検証では null（関数差し替え）。 */
    private val sourceResolver: ContentResolver?,

    private val dao: ThumbnailDao,

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    /**
     * デコード実体。既定値は共有デコードパス（[SourceDecode]、EXIF は ADR-023 の
     * PLATFORM_APPLIED・自前正規化なし）。JVM 単体テストは android framework無しで動くため差し替える。
     */
    private val decodeSource: (ContentResolver?, String?, Int) -> SourceDecode.Result? =
        ::decodeSharedPath,

    /**
     * ソース bounds の軽量 probe（inJustDecodeBounds・全量展開なし）。不明は (0,0)。
     */
    private val probeSourceBounds: (ContentResolver?, String?) -> Pair<Int, Int> =
        ::probeBoundsSharedPath,
) {

    /** 実運用 DI（AppContainer）。生成の自動実行は行わない（ライブラリ表示時に ViewModel が指示）。 */
    constructor(context: Context, dao: ThumbnailDao) : this(
        thumbnailRoot = File(context.filesDir, ROOT_DIR),
        sourceResolver = context.contentResolver,
        dao = dao,
    )

    /**
     * asset 単位の生成。
     * - 既存行があれば true（何もしない）。
     * - 成功時は全計画レベルの行を upsert する。
     * - 失敗時は false（Log.w 1回・行不発・自前成果物のみ掃除・原本写真には一切触れない）。
     *
     * @param displayName 契約上受け取るが、ファイル名・ログ・鍵には一切使用しない（PR-8 契約）。
     */
    suspend fun ensureForAsset(assetId: String, sourceUri: String, displayName: String): Boolean {
        // 冪等判定（行が正本）。行さえあれば何もしないことを JVM テストが実コードで確認する。
        val existing = runCatching { dao.forAsset(assetId) }.getOrElse { emptyList() }
        if (existing.isNotEmpty()) return true

        // 書込前の早期拒否（assetId 書式外はデコードより先に止める: 何も書かないまま false）。
        if (assetDirectoryName(assetId) == null) {
            reportFailure(
                assetId,
                "assetId must be a UUID-form directory name; refusing to write (displayName is never used)",
            )
            return false
        }
        val success = runCatching { ensureGeneration(assetId, sourceUri) }.getOrElse { e ->
            if (e is CancellationException) throw e // 取り消しは失敗へ変換しない（構造化並行性）。
            reportFailure(assetId, "generation crashed", e)
            false
        }
        return success
    }

    /**
     * asset 群の生成（[Companion.CONCURRENCY] 資産単位でバウンド。資産内のレベルは逐次）。
     * 冪等性は [ensureForAsset] に一任する。完了ごとに [onEach] を呼ぶ（表示側の漸次反映用）。
     */
    suspend fun ensureForAssets(
        seeds: List<ThumbnailSeed>,
        onEach: suspend (assetId: String, ok: Boolean) -> Unit = { _, _ -> },
    ): Map<String, Boolean> = coroutineScope {
        val permits = Semaphore(CONCURRENCY)
        seeds.map { seed ->
            async {
                permits.withPermit {
                    val ok = ensureForAsset(seed.assetId, seed.sourceUri, seed.displayName)
                    onEach(seed.assetId, ok)
                    seed.assetId to ok
                }
            }
        }.awaitAll().toMap()
    }

    /**
     * 表示用の最良サムネイル絶対パス。行の最大 level の実在ファイル（非空）を指す。
     * 行なし/書式外/実体欠落は null（=呼出し側は従来どおりソースURIを読んで表示する）。
     */
    suspend fun bestDisplayPath(assetId: String): String? {
        val rows = runCatching { dao.forAsset(assetId) }.getOrElse { emptyList() }
        val best = rows.maxByOrNull { it.level } ?: return null
        val assetDir = assetDirectoryName(best.assetId) ?: return null
        val file = File(thumbnailRoot, "$assetDir/$FILE_PREFIX${best.level}$FILE_SUFFIX")
        val present = runCatching { file.isFile && file.length() > 0L }.getOrDefault(false)
        return if (present) file.absolutePath else null
    }

    /** 行の存在（行が正本。VM 側の再生成判定はこれのみで行う。ファイル欠落があっても再生成しない）。 */
    suspend fun hasRows(assetId: String): Boolean =
        runCatching { dao.forAsset(assetId) }.getOrElse { emptyList() }.isNotEmpty()

    /**
     * 行写像（レベルごとに byteSize・cacheKey・contentHash・accessedAt を記録して upsert）。
     * 部分行は作らない（[ensureGeneration] が全レベル成功後にのみ呼ぶ）。
     * contentHash はエンコード実体の sha-256、accessedAt は生成時刻、byteSize は実測値。
     */
    internal suspend fun persistRows(
        assetId: String,
        encoded: List<EncodedThumb>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<ThumbnailEntity> {
        val rows = encoded.map { thumb ->
            ThumbnailEntity(
                assetId = assetId,
                level = thumb.level,
                // cacheKey は root 相対（"<assetId>/_<level>.jpg"）。assetId 書式は呼出し元で検証済み。
                cacheKey = checkNotNull(leafFor(assetId, thumb.level)) { "unsafe assetId reached persistRows" },
                contentHash = Fingerprinter.sha256Hex(thumb.file.inputStream()),
                accessedAt = nowMs,
                byteSize = thumb.byteSize,
            )
        }
        rows.forEach { row -> dao.upsert(row) }
        return rows
    }

    /** レベル生成（実機領域）。JVM 単体テストはここへ届かない（差し替え関数が null を返すため）。 */
    private suspend fun ensureGeneration(assetId: String, sourceUri: String): Boolean =
        withContext(ioDispatcher) {
            val bounds = probeSourceBounds(sourceResolver, sourceUri)
            val sample = sampleForBounds(bounds.first, bounds.second)
            val decoded = decodeSource(sourceResolver, sourceUri, sample)
            if (decoded == null) {
                // 失敗契約: 不明は不明のまま。代替画素を作らず・行も作らない（落ちない）。
                reportFailure(assetId, "decode failed (bounds=${bounds.first}x${bounds.second}, sample=$sample)")
                return@withContext false
            }
            val source = decoded.bitmap
            val plans = decideLevels(source.width, source.height)
            if (plans.isEmpty()) {
                // 寸法不明も「不明のまま」失敗（作為的な寸法を持つ行を作らない）。
                reportFailure(assetId, "decoded dimensions unknown (${source.width}x${source.height})")
                return@withContext false
            }

            val assetDir = File(thumbnailRoot, checkNotNull(assetDirectoryName(assetId)))
            if (!runCatching { assetDir.mkdirs(); assetDir.isDirectory }.getOrDefault(false)) {
                reportFailure(assetId, "asset directory unavailable: ${assetDir.path}")
                return@withContext false
            }

            val written = mutableListOf<EncodedThumb>()
            var inFlight: File? = null
            try {
                for (plan in plans) {
                    // assetId 書式は ensureForAsset の前段で検証済み（到達不能系は例外で正本化）。
                    val leaf = checkNotNull(leafFor(assetId, plan.level)) { "unsafe assetId in generation" }
                    val dest = File(assetDir, leaf.substringAfterLast('/'))
                    inFlight = dest
                    written += encodeAndRegister(source, dest, plan)
                    inFlight = null
                }
                persistRows(assetId, written)
                true
            } catch (e: CancellationException) {
                cleanupPartial(written, inFlight)
                throw e
            } catch (e: Throwable) {
                // 自前成果物のみ掃除（原本・編集文書・asset 行には一切触れない）。
                cleanupPartial(written, inFlight)
                reportFailure(assetId, "encode/store failed: ${e.message}", e)
                false
            }
        }

    /** 対象レベルに合わせた縮小（拡大禁止: target がソース寸法以上なら元ビットマップをそのまま使用）と JPEG エンコード。 */
    private fun encodeAndRegister(source: Bitmap, dest: File, plan: LevelPlan): EncodedThumb {
        val dims = scaledDims(source.width, source.height, plan.targetMaxEdge)
        val bitmap = if (dims.first == source.width && dims.second == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, dims.first, dims.second, true) // filter=true
        }
        dest.parentFile?.let { it.mkdirs() }
        FileOutputStream(dest).use { out ->
            val encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (!encoded) {
                throw IllegalStateException("JPEG encoder returned false (${bitmap.width}x${bitmap.height})")
            }
            out.flush()
        }
        val size = dest.length()
        if (size <= 0L) {
            throw IllegalStateException("JPEG encode produced an empty file: ${dest.name}")
        }
        return EncodedThumb(level = plan.level, file = dest, byteSize = size)
    }

    /** 失敗レポート（asset 単位で1回）。Log の失敗自体を結果に反映しない（JVM 単体は android framework 無し）。 */
    private fun reportFailure(assetId: String, reason: String, cause: Throwable? = null) {
        runCatching {
            if (cause == null) {
                Log.w(TAG, "thumbnail generation failed assetId=$assetId reason=$reason")
            } else {
                Log.w(TAG, "thumbnail generation failed assetId=$assetId reason=$reason", cause)
            }
        }
    }

    private fun cleanupPartial(written: List<EncodedThumb>, inFlight: File?) {
        (written.map { it.file } + listOfNotNull(inFlight)).forEach { file ->
            runCatching { file.delete() }
        }
    }

    companion object {
        /** 失敗レポートのタグ（PR-8 契約: 失敗は asset 単位で1回だけ Log.w）。 */
        const val TAG = "ThumbnailGen"

        /** level taxonomy（本PRで数値確定。Entities.kt の「0=full, 段階数値は別定義」に対する決定）。 */
        const val LEVEL_0 = 0
        const val LEVEL_1 = 1

        /** level 0 の最大辺（ライブラリグリッド 112dp セル用）。 */
        const val LEVEL_0_MAX_EDGE = 256

        /** level 1 の最大辺（詳細表示/編集プレビューの中間行。現行 UI の直接消費者は未実装）。 */
        const val LEVEL_1_MAX_EDGE = 1024

        const val JPEG_QUALITY = 90

        /** filesDir 直下の root ディレクトリ名。 */
        const val ROOT_DIR = "thumbnails"

        /** 生成成果物のファイル名接頭辞（_<level>.jpg）。 */
        const val FILE_PREFIX = "_"
        const val FILE_SUFFIX = ".jpg"

        /** [ensureForAssets] の資産単位並行上限。 */
        const val CONCURRENCY = 2

        /**
         * assetId が安全なディレクトリ名である場合のみ assetId 自身を返す（UUID書式限定:
         * ContentUriImportRepository が UUID.randomUUID() で採番する正本書式）。拒否は遍歴文字
         * （..、/、\ 等）を初め、displayName 由来の形式一切を含む（表示名はこの書式に一致できない）。
         */
        internal fun assetDirectoryName(assetId: String): String? =
            assetId.takeIf { UUID_ASSET_ID.matches(it) }

        /** サムネイル root 相対の実体ファイル名（"<assetId>/_<level>.jpg"）。書式外 assetId は null。 */
        internal fun leafFor(assetId: String, level: Int): String? {
            val dir = assetDirectoryName(assetId) ?: return null
            return "$dir/$FILE_PREFIX$level$FILE_SUFFIX"
        }

        /** filesDir 相対の格納経路（DB の cacheKey 正本形式: "thumbnails/<assetId>/_<level>.jpg"）。 */
        internal fun relativePathFor(assetId: String, level: Int): String? =
            leafFor(assetId, level)?.let { "$ROOT_DIR/$it" }

        /**
         * 計画レベル決定（純粋関数）。
         * level 0 は常に存在（最大辺 [LEVEL_0_MAX_EDGE] か、ソースが小さければその寸法。拡大しない）。
         * level 1 はソース最大辺が [LEVEL_1_MAX_EDGE] を超える場合のみ（拡大禁止のため）。
         * 寸法不明 (0,0) は空（=行を作らない）。
         */
        internal fun decideLevels(sourceW: Int, sourceH: Int): List<LevelPlan> {
            if (sourceW <= 0 || sourceH <= 0) return emptyList()
            val maxEdge = maxOf(sourceW, sourceH)
            val level0Edge = minOf(maxEdge, LEVEL_0_MAX_EDGE)
            val plans = mutableListOf(LevelPlan(LEVEL_0, level0Edge))
            if (maxEdge > LEVEL_1_MAX_EDGE) plans += LevelPlan(LEVEL_1, LEVEL_1_MAX_EDGE)
            return plans
        }

        /** ソース寸法から targetMaxEdge の最大辺を持つ縮小後寸法（拡大禁止: target が超えるなら原寸）。 */
        internal fun scaledDims(sourceW: Int, sourceH: Int, targetMaxEdge: Int): Pair<Int, Int> {
            if (sourceW <= 0 || sourceH <= 0 || targetMaxEdge <= 0) return 0 to 0
            val maxEdge = maxOf(sourceW, sourceH)
            if (targetMaxEdge >= maxEdge) return sourceW to sourceH
            val scale = targetMaxEdge.toDouble() / maxEdge
            return (sourceW * scale).roundToInt().coerceAtLeast(1) to
                (sourceH * scale).roundToInt().coerceAtLeast(1)
        }

        /**
         * bounds から inSampleSize 相当のサンプル（2の冪）を決定。
         * 最大の計画レベル（ソースが超えるなら level1=1024）の最大辺が拡大なしで到達できる
         * 最大の sample を選ぶ。措定の「~512px」は level1=1024 と不整合（sample 4 でデコードした
         * 1000x750 から 1024 を作るには拡大が必要）となるため、拡大禁止契約（decideLevels）を
         * 正本とし sample は「最大計画レベル基準」に統一した決定をした（PR-8 レポートで報告）。
         * bounds 不明 (0,0) は sample=1（デコード実体の null 契約で吸収する）。
         */
        internal fun sampleForBounds(sourceW: Int, sourceH: Int): Int {
            val maxEdge = maxOf(sourceW, sourceH)
            if (maxEdge <= 0) return 1
            val target = when {
                maxEdge > LEVEL_1_MAX_EDGE -> LEVEL_1_MAX_EDGE
                maxEdge > LEVEL_0_MAX_EDGE -> LEVEL_0_MAX_EDGE
                else -> maxEdge
            }
            var sample = 1
            while (maxEdge / (sample * 2) >= target) sample *= 2
            return sample
        }

        private val UUID_ASSET_ID = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )
    }

    /** 計画上の1レベル（level 番号と、そのレベルで作る最大辺）。 */
    data class LevelPlan(
        val level: Int,
        val targetMaxEdge: Int,
    )
}

/**
 * 既定の共有デコードパス（[SourceDecode]）。実機経路のみ使用。
 * JVM 単体テストは android framework に触れないためこの既定を差し替える。
 */
private fun decodeSharedPath(
    resolver: ContentResolver?,
    sourceUri: String?,
    sample: Int,
): SourceDecode.Result? {
    val stabilized = resolver ?: return null
    val uri = sourceUri ?: return null
    return runCatching { SourceDecode.decode(stabilized, Uri.parse(uri), sample) }.getOrNull()
}

/** 既定の bounds probe（inJustDecodeBounds・全量展開を行わない）。不明は (0,0) を返す。 */
private fun probeBoundsSharedPath(
    resolver: ContentResolver?,
    sourceUri: String?,
): Pair<Int, Int> = runCatching {
    val stabilized = resolver ?: return@runCatching 0 to 0
    val uri = sourceUri ?: return@runCatching 0 to 0
    val stream = stabilized.openInputStream(Uri.parse(uri)) ?: return@runCatching 0 to 0
    stream.use { s ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(s, null, options)
        (options.outWidth.takeIf { it > 0 } ?: 0) to (options.outHeight.takeIf { it > 0 } ?: 0)
    }
}.getOrDefault(0 to 0)
