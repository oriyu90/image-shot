package com.imageshot.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `許可ローカルスキーマは content と file のみ`() {
        assertTrue(ImportSniffer.isAllowedLocalScheme("content"))
        assertTrue(ImportSniffer.isAllowedLocalScheme("file"))
        assertFalse(ImportSniffer.isAllowedLocalScheme("https"))
        assertFalse(ImportSniffer.isAllowedLocalScheme(null))
    }
}
