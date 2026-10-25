package com.imageshot.core.storage

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表示名解決連鎖（PR-19）のJVM検証。Android 照会実体（[AndroidDisplayNameSource] の
 * ContentResolver/DocumentsContract 経路）はフレームワーク実装のためここでは検証しない（実機観察待ち）。
 * 本テストは連鎖本体（[AssetDisplayNameResolver.resolveChain]）へ純 fake source を注入して、
 * 各段の採用判定・継続・例外吸収・プレースホルダの決定性と拡張子推定を実コードで守る。
 */
class AssetDisplayNameResolverTest {

    /** 連鎖各段の照会記録＋値/例外注入のできる純 fake（Android型に触れない）。 */
    private class FakeSource(
        override val facts: UriNameFacts,
        private val openable: (() -> String?)? = null,
        private val document: (() -> String?)? = null,
        private val mediaStore: (() -> String?)? = null,
    ) : DisplayNameSource {

        /** 実際に照会された段の記録（gate 抑制の検証に使う）。 */
        val queried = mutableListOf<String>()

        override fun openableDisplayName(): String? {
            queried += "openable"
            return openable?.invoke()
        }

        override fun documentName(): String? {
            queried += "document"
            return document?.invoke()
        }

        override fun mediaStoreDisplayName(): String? {
            queried += "mediaStore"
            return mediaStore?.invoke()
        }
    }

    /** テスト用 resolver。Android 実装ファクトリは使わない（実戦入口 resolve() は JVM 未検証）。 */
    private fun resolver(): AssetDisplayNameResolver =
        AssetDisplayNameResolver { _ -> throw AssertionError("JVM テストは resolveChain を直接使う") }

    private fun facts(
        uriString: String = "content://x",
        lastPathSegment: String? = null,
        isDocumentUri: Boolean = false,
        authority: String? = null,
    ): UriNameFacts = UriNameFacts(
        uriString = uriString,
        lastPathSegment = lastPathSegment,
        isDocumentUri = isDocumentUri,
        authority = authority,
    )

    /** 期待 hash8 の独立計算（resolver 実装を流用せず SHA-256 規格へ直接参照する）。 */
    private fun expectedHash8(uriString: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(uriString.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
            .take(8)

    @Test
    fun `openable の実名を採用する（トリムも行う）`() {
        val src = FakeSource(
            facts = facts(uriString = "content://a/IMG_x.jpg"),
            openable = { " IMG_0001.jpg " },
        )
        val result = resolver().resolveChain(src, mimeType = "image/jpeg", sniffedFormat = "JPEG")
        assertEquals("IMG_0001.jpg", result.name)
        assertTrue(result.fullyResolved)
        assertEquals(listOf("openable"), src.queried)
    }

    @Test
    fun `openable が null 空白 数字のみのときは不採用で連鎖を続行する`() {
        listOf(null, "   ", "3101").forEach { bad ->
            val src = FakeSource(
                facts = facts(isDocumentUri = true, uriString = "content://b"),
                openable = { bad },
                document = { "IMG_0002.jpg" },
            )
            val result = resolver().resolveChain(src, null, null)
            assertEquals("bad=$bad", "IMG_0002.jpg", result.name)
            assertTrue(result.fullyResolved)
            // a) が不採用のまま b) まで到達する（連鎖の打ち切りでない）。
            assertEquals("bad=$bad", listOf("openable", "document"), src.queried)
        }
    }

    @Test
    fun `数字と短拡張子の連番形式は 06 C2 実測相当として採用しない`() {
        val src = FakeSource(
            facts = facts(uriString = "content://c/1743.jpg", lastPathSegment = "IMG_0003.jpg"),
            openable = { "1743.jpg" },
        )
        val result = resolver().resolveChain(src, null, null)
        assertEquals("IMG_0003.jpg", result.name)
        assertTrue(result.fullyResolved)
    }

    @Test
    fun `document 名は document URI のときのみ照会される`() {
        val nonDoc = FakeSource(
            facts = facts(isDocumentUri = false, uriString = "content://d", lastPathSegment = "IMG_E.jpg"),
            document = { "-nonDocument では照会しない-" },
        )
        val result1 = resolver().resolveChain(nonDoc, null, null)
        assertEquals("IMG_E.jpg", result1.name)
        assertTrue("document 照会は抑制される", "document" !in nonDoc.queried)

        val doc = FakeSource(
            facts = facts(isDocumentUri = true, uriString = "content://d"),
            document = { "IMG_F.jpg" },
        )
        val result2 = resolver().resolveChain(doc, null, null)
        assertEquals("IMG_F.jpg", result2.name)
        assertEquals(listOf("openable", "document"), doc.queried)
    }

    @Test
    fun `MediaStore 照会は media 系 authority のときのみ行われる`() {
        listOf("media", "com.android.providers.media.module").forEach { authority ->
            val src = FakeSource(
                facts = facts(authority = authority, uriString = "content://$authority/1743"),
                mediaStore = { "IMG_0006.jpg" },
            )
            val result = resolver().resolveChain(src, null, null)
            assertEquals(authority, "IMG_0006.jpg", result.name)
            assertTrue(result.fullyResolved)
        }
        // media 系でない authority（null 含む）は照会しない（不明 authority への暗黙参照禁止）。
        listOf(null, "com.android.documentsui", "com.example.local").forEach { authority ->
            val src = FakeSource(
                facts = facts(authority = authority, uriString = "content://x", lastPathSegment = "IMG_G.jpg"),
                mediaStore = { "照会されてはいけない" },
            )
            val result = resolver().resolveChain(src, null, null)
            assertEquals(authority, "IMG_G.jpg", result.name)
            assertTrue("mediaStore 照会は抑制される", "mediaStore" !in src.queried)
        }
    }

    @Test
    fun `SecurityException は各段で吸収され連鎖を続行する`() {
        val src = FakeSource(
            facts = facts(isDocumentUri = true, authority = "media", uriString = "content://e",
                lastPathSegment = "IMG_H.jpg"),
            openable = { throw SecurityException("revoked") },
            document = { throw SecurityException("revoked") },
            mediaStore = { throw SecurityException("revoked") },
        )
        val result = resolver().resolveChain(src, null, null)
        assertEquals("IMG_H.jpg", result.name)
        assertTrue(result.fullyResolved)
        // 吸収で打ち切らず、全段の照会と継続が行われていること。
        assertEquals(listOf("openable", "document", "mediaStore"), src.queried)
    }

    @Test
    fun `SecurityException 以外の例外も各段で吸収する`() {
        val src = FakeSource(
            facts = facts(isDocumentUri = true, authority = "media", uriString = "content://g",
                lastPathSegment = "IMG_I.jpg"),
            openable = { throw IllegalStateException("provider crash") },
            document = { throw IllegalArgumentException("bad doc id") },
            mediaStore = { throw RuntimeException("unexpected") },
        )
        val result = resolver().resolveChain(src, null, null)
        assertEquals("IMG_I.jpg", result.name)
        assertTrue(result.fullyResolved)
    }

    @Test
    fun `連鎖の優先順位は openable document mediaStore segment の順に従う`() {
        val full = FakeSource(
            facts = facts(isDocumentUri = true, authority = "media", uriString = "content://f"),
            openable = { "IMG_O.jpg" },
            document = { "IMG_D.jpg" },
            mediaStore = { "IMG_M.jpg" },
        )
        assertEquals("IMG_O.jpg", resolver().resolveChain(full, null, null).name)

        // openable 不採用 → document。
        val noOpenable = FakeSource(
            facts = facts(isDocumentUri = true, authority = "media", uriString = "content://f"),
            document = { "IMG_D.jpg" },
            mediaStore = { "IMG_M.jpg" },
        )
        assertEquals("IMG_D.jpg", resolver().resolveChain(noOpenable, null, null).name)

        // document が数字 doc id（ダウンロード系のような内部連番）でも継続 → mediaStore。
        val noDocumentName = FakeSource(
            facts = facts(isDocumentUri = true, authority = "media", uriString = "content://f"),
            document = { "2214" },
            mediaStore = { "IMG_M.jpg" },
        )
        assertEquals("IMG_M.jpg", resolver().resolveChain(noDocumentName, null, null).name)

        // 全段 null → lastPathSegment（doc id 並びの正規化）。
        val onlySegment = FakeSource(
            facts = facts(isDocumentUri = true, authority = "media", uriString = "content://f",
                lastPathSegment = "primary:DCIM/IMG_S.jpg"),
        )
        assertEquals("IMG_S.jpg", resolver().resolveChain(onlySegment, null, null).name)
    }

    @Test
    fun `連鎖全滅は hash プレースホルダで応答する（取り込み失敗にしない）`() {
        val uriString = "content://media/external/images/media/1743"
        val src = FakeSource(
            facts = facts(uriString = uriString, lastPathSegment = "1743"),
        )
        val result = resolver().resolveChain(src, null, null)
        assertEquals("img-${expectedHash8(uriString)}.jpg", result.name)
        assertFalse(result.fullyResolved)
    }

    @Test
    fun `プレースホルダは同一URI文字列から決定的で異なるURIは異なる値`() {
        val uriString = "content://media/picker/cloud/1743"
        val first = resolver().resolveChain(FakeSource(facts(uriString)), null, null)
        val second = resolver().resolveChain(FakeSource(facts(uriString)), null, null)
        assertEquals(first.name, second.name)
        assertFalse(first.fullyResolved)
        // 形式契約: img-<16進8文字>.<拡張子>
        assertTrue(Regex("""^img-[0-9a-f]{8}\.[a-z0-9]+$""").matches(first.name))
        val other = resolver().resolveChain(FakeSource(facts("content://media/picker/cloud/1744")), null, null)
        assertNotEquals(first.name, other.name)
    }

    @Test
    fun `プレースホルダ拡張子は mimeType → sniffedFormat → 既定jpg の順に推定する`() {
        fun placeholderName(mimeType: String?, sniffedFormat: String?): String {
            val src = FakeSource(facts = facts(uriString = "content://p"))
            return resolver().resolveChain(src, mimeType, sniffedFormat).name
        }
        val expectedBase = "img-${expectedHash8("content://p")}."

        assertEquals(expectedBase + "png", placeholderName("image/png", "JPEG")) // mime 優先
        assertEquals(expectedBase + "heic", placeholderName("image/heic", null))
        assertEquals(expectedBase + "png", placeholderName(null, "PNG"))
        assertEquals(expectedBase + "webp", placeholderName(null, "WEBP"))
        assertEquals(expectedBase + "heif", placeholderName(null, "HEIF"))
        assertEquals(expectedBase + "jpg", placeholderName(null, null))
        // 非対応候補・不明系（取り込みでは弾かれる値だが resolver 契約上既定へ倒す）。
        assertEquals(expectedBase + "jpg", placeholderName(null, "TIFF_FAMILY"))
        assertEquals(expectedBase + "jpg", placeholderName("application/octet-stream", "UNKNOWN"))
    }

    @Test
    fun `lastPathSegment の doc id 並びは下位名に正規化して採用する`() {
        val src = FakeSource(
            facts = facts(uriString = "content://s", lastPathSegment = "primary:DCIM/IMG_0008.jpg"),
        )
        val result = resolver().resolveChain(src, null, null)
        assertEquals("IMG_0008.jpg", result.name)
        assertTrue(result.fullyResolved)
    }

    @Test
    fun `lastPathSegment が空白または数字のみの内部 id ならプレースホルダに倒す`() {
        listOf("   ", "1743", "media/external/images/1743", "1743.jpg").forEach { segment ->
            val uriString = "content://m/$segment"
            val src = FakeSource(
                facts = facts(uriString = uriString, lastPathSegment = segment),
            )
            val result = resolver().resolveChain(src, null, null)
            assertEquals(segment, "img-${expectedHash8(uriString)}.jpg", result.name)
            assertFalse(result.fullyResolved)
        }
    }

    @Test
    fun `adopt は null 空白 連番形式を不採用とし実名はトリムして採用する`() {
        assertNull(AssetDisplayNameResolver.adopt(null))
        assertNull(AssetDisplayNameResolver.adopt(""))
        assertNull(AssetDisplayNameResolver.adopt("   "))
        assertNull(AssetDisplayNameResolver.adopt("1743"))
        assertNull(AssetDisplayNameResolver.adopt("1743.jpg")) // 06 C2 実測相当
        assertEquals("IMG_0001.jpg", AssetDisplayNameResolver.adopt("  IMG_0001.jpg "))
        // 数字を含む（純数字でない）実名は採用する。
        assertEquals("0000abc.jpg", AssetDisplayNameResolver.adopt("0000abc.jpg"))
        assertEquals("1743 .jpg", AssetDisplayNameResolver.adopt("1743 .jpg"))
    }

    @Test
    fun `facts 取得の不測の失敗でも例外を出さず URI 不明のプレースホルダに倒す`() {
        val src = object : DisplayNameSource {
            override val facts: UriNameFacts
                get() = throw IllegalStateException("broken source")

            override fun openableDisplayName(): String? = throw SecurityException("revoked")

            override fun documentName(): String? = throw SecurityException("revoked")

            override fun mediaStoreDisplayName(): String? = null
        }
        val result = resolver().resolveChain(src, null, null)
        // URI 文字列すら不明（空文字として URI から hash）の既定値で決定的に応答する。
        assertEquals("img-${expectedHash8("")}.jpg", result.name)
        assertFalse(result.fullyResolved)
    }
}
