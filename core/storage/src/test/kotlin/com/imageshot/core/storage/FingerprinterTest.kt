package com.imageshot.core.storage

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprinterTest {

    @Test
    fun `sha256 は既知のベクトルと一致する`() {
        // sha-256("hello") の既知値
        val expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        val actual = Fingerprinter.sha256Hex(ByteArrayInputStream("hello".toByteArray()))
        assertEquals(expected, actual)
    }

    @Test
    fun `空入力は SHA-256 の空ブロック値`() {
        val expected =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(expected, Fingerprinter.sha256Hex(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `同一内容ならバッファ跨界でも同一指紋`() {
        val data = ByteArray(64 * 1024 * 2 + 7) { (it % 251).toByte() }
        val expected = Fingerprinter.sha256Hex(ByteArrayInputStream(data))
        assertEquals(
            expected,
            Fingerprinter.sha256Hex(ByteArrayInputStream(data)),
        )
        assertTrue(expected.length == 64)
    }
}

class ProviderErrorClassifierTest {

    @Test
    fun `SecurityException は権限失効`() {
        val classified = ProviderErrorClassifier.classify(SecurityException("denied"))
        assertTrue(classified is ProviderFailure.PermissionRevoked)
    }

    @Test
    fun `FileNotFoundException は実体消失（切断）`() {
        val classified = ProviderErrorClassifier.classify(FileNotFoundException("gone"))
        assertTrue(classified is ProviderFailure.ContentGone)
    }

    @Test
    fun `その他 IOException は provider IO`() {
        val classified = ProviderErrorClassifier.classify(IOException("io"))
        assertTrue(classified is ProviderFailure.ProviderIo)
    }

    @Test
    fun `OutOfMemoryError は OOM分類`() {
        val classified = ProviderErrorClassifier.classify(OutOfMemoryError("heap"))
        assertTrue(classified is ProviderFailure.OutOfMemory)
    }

    @Test
    fun `分類不能は Unexpected で無通知続行しない`() {
        val classified = ProviderErrorClassifier.classify(IllegalStateException("bad state"))
        assertTrue(classified is ProviderFailure.Unexpected)
    }
}

class StagingTest {

    @Test
    fun `上限内のコピーは完走してバイト数を返す`() {
        val dir = kotlin.io.path.createTempDirectory("staging")
        val data = ByteArray(4 * 1024 + 11) { (it % 199).toByte() }
        val dest = java.io.File(dir.toFile(), "stage.bin")
        val written = Staging.copyLimited(
            openSource = { ByteArrayInputStream(data) },
            dest = dest,
            maxBytes = data.size.toLong(),
            freeBytesAtStart = Staging.MIN_FREE_MARGIN_BYTES * 10,
        )
        assertEquals(data.size.toLong(), written)
        assertEquals(data.toList(), dest.readBytes().toList())
    }

    @Test
    fun `サイズ不明などの上限超過は INSUFFICIENT 由来例外で dest を残さない`() {
        val dir = kotlin.io.path.createTempDirectory("staging")
        val dest = java.io.File(dir.toFile(), "stage.bin")
        try {
            Staging.copyLimited(
                openSource = { ByteArrayInputStream(ByteArray(32)) },
                dest = dest,
                maxBytes = 8,
                freeBytesAtStart = Staging.MIN_FREE_MARGIN_BYTES * 10,
            )
            org.junit.Assert.fail("InsufficientStagingException expected")
        } catch (expected: Staging.InsufficientStagingException) {
            assertTrue(!dest.exists())
        }
    }

    @Test
    fun `空き容量マージン不足はコピー開始前に失敗する`() {
        val dir = kotlin.io.path.createTempDirectory("staging")
        val dest = java.io.File(dir.toFile(), "stage.bin")
        try {
            Staging.copyLimited(
                openSource = { ByteArrayInputStream(ByteArray(8)) },
                dest = dest,
                maxBytes = 64,
                freeBytesAtStart = 0,
            )
            org.junit.Assert.fail("InsufficientStagingException expected")
        } catch (expected: Staging.InsufficientStagingException) {
            assertTrue(expected.message.orEmpty().contains("margin"))
        }
    }
}
