package com.imageshot.core.storage

import com.imageshot.core.database.ThumbnailDao
import com.imageshot.core.database.ThumbnailEntity
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * サムネイル生成（PR-8）のJVM検証。android.graphics 経路（decode/bounds 的実体・スケール・JPEG エンコード）
 * はフレームワーク実装なのでここで検証しない（本物の経路は実機検証を必要とする — NFR-10 未測定）。
 * 本テストは、差し替え関数でその領域を迂回した上で実コードへ入る領域（冪等性・sample 選定・
 * 失敗契約・行写像・経路安全性）だけを守る。
 */
class ThumbnailGeneratorTest {

    /** UUID書式の正本事例（ContentUriImportRepository が採番する書式）。 */
    private val assetA = "3f2b9a1c-1111-4222-8333-444455556666"
    private val assetB = "9a8b7c6d-5555-4666-8777-888899990000"

    /** デコード差し替え経路の呼び出し記録（エンコード走行の有無を広い側面で守る）。 */
    private class DecodeProbe {
        var calls = 0
        var lastSample = -1
    }

    /** Room REPLACE 相当の fake（PK: assetId+level）。DB 依存・android 依存なし。 */
    private class FakeThumbnailDao : ThumbnailDao {
        val rows = mutableListOf<ThumbnailEntity>()
        val upsertCalls = mutableListOf<ThumbnailEntity>()

        override suspend fun upsert(thumbnail: ThumbnailEntity) {
            rows.removeAll { it.assetId == thumbnail.assetId && it.level == thumbnail.level }
            rows.add(thumbnail)
            upsertCalls.add(thumbnail)
        }

        override suspend fun forAsset(assetId: String): List<ThumbnailEntity> =
            rows.filter { it.assetId == assetId }.sortedBy { it.level }

        override suspend fun totalBytes(): Long = rows.sumOf { it.byteSize }
    }

    private fun row(assetId: String, level: Int, byteSize: Long = 11L) = ThumbnailEntity(
        assetId = assetId,
        level = level,
        cacheKey = "$assetId/_$level.jpg",
        contentHash = "seed-hash",
        accessedAt = 1L,
        byteSize = byteSize,
    )

    private fun testGenerator(
        root: File,
        dao: ThumbnailDao,
        bounds: Pair<Int, Int> = 2000 to 1500,
        decodeProbe: DecodeProbe = DecodeProbe(),
    ): ThumbnailGenerator = ThumbnailGenerator(
        thumbnailRoot = root,
        sourceResolver = null, // JVM: android framework に触れない（差し替え関数が代行）
        dao = dao,
        probeSourceBounds = { _, _ -> bounds },
        decodeSource = { _, _, sample ->
            decodeProbe.calls += 1
            decodeProbe.lastSample = sample
            null
        },
    )

    private fun tempRoot(prefix: String): File = createTempDirectory(prefix).toFile()

    @Test
    fun `decideLevels は最大辺規約で 256・1024・原寸を決める`() {
        fun edges(w: Int, h: Int) = ThumbnailGenerator.decideLevels(w, h).map { it.targetMaxEdge }
        fun levels(w: Int, h: Int) = ThumbnailGenerator.decideLevels(w, h).map { it.level }

        // 2000x1500 → level0(256) + level1(1024)
        assertEquals(listOf(256, 1024), edges(2000, 1500))
        assertEquals(listOf(0, 1), levels(2000, 1500))
        // 800x600 → 最大辺1024を超えないので level0 のみ
        assertEquals(listOf(256), edges(800, 600))
        // 200x100 → 256 を超えないので level0 は原寸（拡大しない）。level1 なし
        assertEquals(listOf(200), edges(200, 100))
        // 境界: 1024ちょうどは level1 なし（「超える場合のみ」）、1025 は両方
        assertEquals(listOf(256), edges(1024, 768))
        assertEquals(listOf(256, 1024), edges(1025, 600))
        // 寸法不明は空（=行を作らない）
        assertTrue(ThumbnailGenerator.decideLevels(0, 0).isEmpty())
        assertTrue(ThumbnailGenerator.decideLevels(-1, 100).isEmpty())
    }

    @Test
    fun `scaledDims は拡大せず最大辺ターゲットに一致させる`() {
        assertEquals(1024 to 768, ThumbnailGenerator.scaledDims(2000, 1500, 1024))
        assertEquals(256 to 192, ThumbnailGenerator.scaledDims(800, 600, 256))
        // 拡大禁止: ターゲットがソース寸法以上なら原寸
        assertEquals(200 to 100, ThumbnailGenerator.scaledDims(200, 100, 200))
        assertEquals(200 to 100, ThumbnailGenerator.scaledDims(200, 100, 256))
        // 原点寸法不明は (0,0)
        assertEquals(0 to 0, ThumbnailGenerator.scaledDims(0, 0, 256))
    }

    @Test
    fun `sampleForBounds は最大計画レベル基準の2の冪を選ぶ`() {
        // 2000x1500 → sample 1（sample2 では 1000 < 1024 になり level1 が拡大になってしまう）
        assertEquals(1, ThumbnailGenerator.sampleForBounds(2000, 1500))
        // 4032x3024 → sample 2（2016 >= 1024、sample4 では 1008 < 1024）
        assertEquals(2, ThumbnailGenerator.sampleForBounds(4032, 3024))
        // 800x600（level1 の計画対象外）→ 256 基準で sample 2（sample4 だと 200 < 256）
        assertEquals(2, ThumbnailGenerator.sampleForBounds(800, 600))
        // 200x100 → 原寸（拡大不要）
        assertEquals(1, ThumbnailGenerator.sampleForBounds(200, 100))
        // bounds 不明 (0,0) → sample 1（デコード実体の null 契約に委譲）
        assertEquals(1, ThumbnailGenerator.sampleForBounds(0, 0))
        // 巨大パノラマ → sample 16（1250 >= 1024、sample32 は 625 < 1024）
        assertEquals(16, ThumbnailGenerator.sampleForBounds(20000, 15000))
        // サンプル適用後も最大 2 の冪で level1(1024) が到達可能であることの直接確認
        listOf(2000 to 1500, 4032 to 3024, 20000 to 15000).forEach { (w, h) ->
            val sample = ThumbnailGenerator.sampleForBounds(w, h)
            assertTrue("w=$w h=$h sample=$sample", maxOf(w, h) / sample >= 1024)
        }
    }

    @Test
    fun `経路ビルダーはUUID書式のassetIdからのみ決まりdisplayNameの余地を持たない`() {
        val id = assetA
        assertEquals("$id/_0.jpg", ThumbnailGenerator.leafFor(id, 0))
        assertEquals("$id/_1.jpg", ThumbnailGenerator.leafFor(id, 1))
        assertEquals("thumbnails/$id/_0.jpg", ThumbnailGenerator.relativePathFor(id, 0))
        // 同一 assetId は常に同一経路。displayName はこの関数の引数に存在しない
        // （表示名から経路への取り込み経路は言語仕様上発生しない）。
        assertEquals(ThumbnailGenerator.leafFor(id, 0), ThumbnailGenerator.leafFor(id, 0))
        assertEquals(ThumbnailGenerator.relativePathFor(id, 1), "thumbnails/" + ThumbnailGenerator.leafFor(id, 1))
        // traversal・セパレータ・書式外は一括拒否（書込不発）
        listOf("", "..", "...", "a/b", "a\\b", "IMG_0001.jpg", "../evil", "  ", "ssh", "生.JPG")
            .forEach { unsafe ->
                assertNull("assetId=$unsafe", ThumbnailGenerator.leafFor(unsafe, 0))
                assertNull(ThumbnailGenerator.relativePathFor(unsafe, 0))
            }
    }

    @Test
    fun `既存行のある資産は再デコード・再エンコード・上書きをしない`() = runBlocking {
        val dao = FakeThumbnailDao()
        dao.rows += row(assetA, 0)
        dao.rows += row(assetA, 1)
        val root = tempRoot("thumb-idem")
        val probe = DecodeProbe()
        val generator = testGenerator(root, dao, decodeProbe = probe)

        // displayName に無害でない文字を渡しても（無縁の引数であること自体の確認）結果は変わらない。
        val ok = generator.ensureForAsset(assetA, "file:///IMG_0001.jpg", "IMG_0001.jpg")

        assertTrue(ok)
        assertEquals(0, probe.calls) // デコード（=エンコード走行の入口）も走らない
        assertTrue(dao.upsertCalls.isEmpty()) // 上書きしない
        assertEquals(2, dao.rows.size) // 行も増えない
    }

    @Test
    fun `デコード失敗は行を作らずfalseで応答し落とさない`() = runBlocking {
        val dao = FakeThumbnailDao()
        val root = tempRoot("thumb-decode-fail")
        val probe = DecodeProbe()
        val generator = testGenerator(root, dao, bounds = 2000 to 1500, decodeProbe = probe)

        val ok = generator.ensureForAsset(assetA, "content://media/x", "IMG_0001.jpg")

        assertFalse(ok)
        assertEquals(1, probe.calls)
        // 2000x1500 は sample 1（最大計画レベル 1024 基準）でデコードを試みる契約
        assertEquals(1, probe.lastSample)
        assertTrue(dao.upsertCalls.isEmpty())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `assetId がUUID書式でなければデコードより先に拒否して書込不発`() = runBlocking {
        val dao = FakeThumbnailDao()
        val root = tempRoot("thumb-unsafe")
        val probe = DecodeProbe()
        val generator = testGenerator(root, dao, decodeProbe = probe)

        // displayName にUUID書式相当の文字列を渡しても、writePath の鍵は assetId のみ（影響ゼロの確認）。
        val ok = generator.ensureForAsset("IMG_0001.jpg", "content://media/x", assetA)

        assertFalse(ok)
        assertEquals(0, probe.calls) // デコードも始まらない（書込前の早期拒否）
        assertTrue(dao.upsertCalls.isEmpty())
    }

    @Test
    fun `ensureForAssets は欠けている資産のみ生成を試みて全件の結果を返す`() = runBlocking {
        val dao = FakeThumbnailDao()
        dao.rows += row(assetA, 0)
        val root = tempRoot("thumb-batch")
        val probe = DecodeProbe()
        val generator = testGenerator(root, dao, bounds = 4000 to 3000, decodeProbe = probe)
        val callbacks = mutableListOf<Pair<String, Boolean>>()

        val result = generator.ensureForAssets(
            listOf(
                ThumbnailSeed(assetId = assetA, sourceUri = "content://a", displayName = "IMG_A.jpg"),
                ThumbnailSeed(assetId = assetB, sourceUri = "content://b", displayName = "IMG_B.jpg"),
            ),
        ) { assetId, ok -> callbacks += assetId to ok }

        assertEquals(mapOf(assetA to true, assetB to false), result)
        assertEquals(2, callbacks.size)
        // 既存行（assetA）はデコード試行せず、欠け（assetB）のみ1回走る
        assertEquals(1, probe.calls)
        assertTrue(dao.upsertCalls.isEmpty())
    }

    @Test
    fun `persistRows は byteSize・cacheKey・contentHash・accessedAt を行に写像する`() = runBlocking {
        val dao = FakeThumbnailDao()
        val root = tempRoot("thumb-persist")
        val generator = testGenerator(root, dao, bounds = 2000 to 1500)
        val bytes0 = ByteArray(37) { (it % 89).toByte() }
        val bytes1 = ByteArray(1021) { (it % 131).toByte() }
        val file0 = File(root, "$assetA/_0.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(bytes0)
        }
        val file1 = File(root, "$assetA/_1.jpg").apply { writeBytes(bytes1) }

        val rows = generator.persistRows(
            assetA,
            listOf(
                EncodedThumb(level = 0, file = file0, byteSize = file0.length()),
                EncodedThumb(level = 1, file = file1, byteSize = file1.length()),
            ),
            nowMs = 100L,
        )

        assertEquals(2, dao.upsertCalls.size)
        assertEquals(rows, dao.upsertCalls.toList())
        val r0 = dao.upsertCalls[0]
        val r1 = dao.upsertCalls[1]
        assertEquals(assetA, r0.assetId)
        assertEquals(0, r0.level)
        assertEquals(37L, r0.byteSize)
        assertEquals("$assetA/_0.jpg", r0.cacheKey)
        // contentHash はエンコード実体の sha-256（Fingerprinter の値と一致）
        assertEquals(Fingerprinter.sha256Hex(ByteArrayInputStream(bytes0)), r0.contentHash)
        assertEquals(100L, r0.accessedAt)
        assertEquals(1, r1.level)
        assertEquals(1021L, r1.byteSize)
        assertEquals(Fingerprinter.sha256Hex(ByteArrayInputStream(bytes1)), r1.contentHash)
    }

    @Test
    fun `bestDisplayPath は最大levelの実在ファイルの絶対パスを返す`() = runBlocking {
        val dao = FakeThumbnailDao()
        val root = tempRoot("thumb-display")
        // 行は在るが群ファイル欠落 → null（=表示側は従来のソースURI表示へフォールバック）
        dao.rows += row(assetA, 0)
        dao.rows += row(assetA, 1)
        val generator = testGenerator(root, dao)
        assertNull(generator.bestDisplayPath(assetA))

        // 実体を置くと最大level(1) のファイルを指す
        File(root, "$assetA/_1.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(9))
        }
        assertEquals(File(root, "$assetA/_1.jpg").absolutePath, generator.bestDisplayPath(assetA))

        // 行なし → null
        assertNull(generator.bestDisplayPath(assetB))
    }
}
