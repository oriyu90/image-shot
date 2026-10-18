package com.imageshot.core.storage

import java.io.InputStream
import java.security.MessageDigest

/**
 * sha-256 ファンガープリント計算（01_製品仕様書.md U02 AC: 原本SHA-256不変を検証する正本）。
 * - 逐次 stream で計算し全量メモリ展開しない（§9.1 巨大Bitmap/Buffer多重保持禁止の考え方に準拠）。
 * - 純JVM。register の遅延実行（U01: ファンガープリントは遅延実行可）から呼ばれる。
 */
internal object Fingerprinter {

    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(AES_BUF)
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            if (read > 0) digest.update(buf, 0, read)
        }
        return hex(digest)
    }

    private fun hex(digest: MessageDigest): String =
        digest.digest().joinToString(separator = "") { "%02x".format(it) }

    private const val AES_BUF = 64 * 1024
}
