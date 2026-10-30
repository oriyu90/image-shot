package com.imageshot.core.storage

import com.imageshot.core.model.ImportProbe
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * magic sniff の対応判定テスト（§6 A区分 / §7.1 拡張子だけを信用しない）。
 */
class ImportSnifferTest {

    @Test
    fun `JPEG magic は supported で rawCandidate でない`() {
        val out = ImportSniffer.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xE0.toByte(), 0x00))
        assertEquals("JPEG", out.format)
        assertTrue(out.supported)
        assertFalse(out.rawCandidate)
    }

    @Test
    fun `PNG magic は supported`() {
        val head = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val out = ImportSniffer.sniff(head)
        assertEquals("PNG", out.format)
        assertTrue(out.supported)
    }

    @Test
    fun `RIFF WEBP は supported`() {
        val head = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray()
        val out = ImportSniffer.sniff(head)
        assertEquals("WEBP", out.format)
        assertTrue(out.supported)
    }

    @Test
    fun `ftyp heic は HEIF supported`() {
        val head = byteArrayOf(0, 0, 0, 24) + "ftyp".toByteArray() + "heic".toByteArray()
        val out = ImportSniffer.sniff(head)
        assertEquals("HEIF", out.format)
        assertTrue(out.supported)
    }

    @Test
    fun `AVIF brand は 非対応候補として明示される`() {
        val head = byteArrayOf(0, 0, 0, 24) + "ftyp".toByteArray() + "avif".toByteArray()
        val out = ImportSniffer.sniff(head)
        assertEquals("AVIF", out.format)
        assertFalse(out.supported)
        assertTrue(out.rawCandidate)
        assertTrue(out.warnings.isNotEmpty())
    }

    @Test
    fun `TIFF家族 II は RAW非対応候補`() {
        val out = ImportSniffer.sniff("II".toByteArray() + byteArrayOf(42, 0) + ByteArray(32))
        assertEquals("TIFF_FAMILY", out.format)
        assertFalse(out.supported)
        assertTrue(out.rawCandidate)
    }

    @Test
    fun `TIFF家族 MM も RAW非対応候補`() {
        val out = ImportSniffer.sniff("MM".toByteArray() + byteArrayOf(0, 42) + ByteArray(32))
        assertTrue(out.rawCandidate)
        assertFalse(out.supported)
    }

    @Test
    fun `CR3 brand は RAW非対応候補`() {
        val head = byteArrayOf(0, 0, 0, 24) + "ftyp".toByteArray() + "crx ".toByteArray()
        val out = ImportSniffer.sniff(head)
        assertFalse(out.supported)
        assertTrue(out.rawCandidate)
    }

    @Test
    fun `空ヘッダーは UNKNOWN 非対応`() {
        val out = ImportSniffer.sniff(ByteArray(0))
        assertEquals("UNKNOWN", out.format)
        assertFalse(out.supported)
    }

    @Test
    fun `既知magicに一致しない入力は UNKNOWN と警告`() {
        val out = ImportSniffer.sniff("xx--".toByteArray())
        assertEquals("UNKNOWN", out.format)
        assertFalse(out.supported)
        assertTrue(out.warnings.isNotEmpty())
    }

    @Test
    fun `isImageMime は image開始のみ true`() {
        assertTrue(ImportSniffer.isImageMime("image/jpeg"))
        assertTrue(ImportSniffer.isImageMime("image/heic"))
        assertFalse(ImportSniffer.isImageMime("video/mp4"))
        assertFalse(ImportSniffer.isImageMime(null))
    }

    @Test
    fun `RAW 拡張子は機種名付きで表示できる`() {
        assertEquals("Sony ARW", ImportSniffer.rawDisplayName("DSC00001.ARW"))
        assertEquals("DNG", ImportSniffer.rawDisplayName("shot.dng"))
        assertEquals("Nikon NEF", ImportSniffer.rawDisplayName("x.NEF"))
        assertNull(ImportSniffer.rawDisplayName("photo.jpg"))
        assertNull(ImportSniffer.rawDisplayName(null))
    }

    @Test
    fun `実 ARW ヘッダーは TIFF 非対応候補として拒否される`() {
        var dir: File? = File(System.getProperty("user.dir"))
        var arw: File? = null
        repeat(4) {
            val candidate = File(dir, "ARW (sony)/DSC00001.ARW")
            if (candidate.isFile) arw = candidate
            dir = dir?.parentFile
        }
        assumeTrue("ARW fixture が無い環境ではスキップ", arw != null)
        val head = arw!!.inputStream().use { it.readNBytes(64) }
        val outcome = ImportSniffer.sniff(head)
        assertEquals("TIFF_FAMILY", outcome.format)
        assertFalse(outcome.supported)
        assertTrue(outcome.rawCandidate)
    }

    @Test
    fun `RAW 非対応の説明文は機種名と未対応を明示する`() {
        val probe = ImportProbe(
            uri = "content://downloads/DSC00001.ARW",
            displayName = "DSC00001.ARW",
            mimeGuess = "image/x-sony-arw",
            sniffedFormat = "TIFF_FAMILY",
            sizeBytes = 24_969_216,
            seekable = true,
            supported = false,
            warnings = listOf("TIFF family: DNG/camera RAW; decoder validation pending, unsupported candidate"),
        )
        val detail = unsupportedImportDetail(probe)
        assertTrue(detail.contains("Sony ARW (RAW development is not supported)"))
        assertTrue(detail.contains("DSC00001.ARW"))
        assertTrue(detail.contains("TIFF_FAMILY"))
    }

    @Test
    fun `RAW は octet-stream でも SAF 列挙の対象になる`() {
        assertTrue(ImportSniffer.isImportCandidate("application/octet-stream", "DSC00001.ARW"))
        assertTrue(ImportSniffer.isImportCandidate("application/octet-stream", "x.cr3"))
        assertTrue(ImportSniffer.isImportCandidate("image/jpeg", "photo.jpg"))
        assertFalse(ImportSniffer.isImportCandidate("application/pdf", "doc.pdf"))
        assertFalse(ImportSniffer.isImportCandidate("application/octet-stream", "notes.txt"))
    }

    @Test
    fun `許可ローカルスキーマは content と file のみ`() {
        assertTrue(ImportSniffer.isAllowedLocalScheme("content"))
        assertTrue(ImportSniffer.isAllowedLocalScheme("file"))
        assertFalse(ImportSniffer.isAllowedLocalScheme("https"))
        assertFalse(ImportSniffer.isAllowedLocalScheme(null))
    }
}
