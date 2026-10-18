package com.imageshot.core.storage

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * staging（02_実装定義書.md §8.1: 非seekableなRAWのランダムアクセス要件）。
 *
 * - M1 のライブラリ登録は fingerprint を遅延実行するため**stagingは現状呼ばれない**
 *   （原本コピーを作らない原則、AGENTS.md/ADR-009）。
 * - RAW現像実装（native/raw-backend 接続後、S-05 spike）で「seek不可 + RAND アクセス必要」
 *   と確定したときにのみ本ユーティリティ経由で有限コピーを行う。
 * - 上限超過・空き容量不足は [InsufficientStagingException]（ErrorCode.INSUFFICIENT_STORAGE へ
 *   写像される）。**原著は一切変更しない**（読み込み専用 stream からのみコピーする）。
 * - 純JVMで unit test 可能。
 */
internal object Staging {

    /** 空き容量の下限マージン（未実測の安全側仮置き。実測で見直す）。 */
    internal const val MIN_FREE_MARGIN_BYTES: Long = 16L * 1024 * 1024

    internal class InsufficientStagingException(message: String) : IOException(message)

    /**
     * 原本 stream → dest への一方向コピー。dest 以外は一切書き換えない。
     * @param maxBytes scp 上限（ImportPolicy.maxStagingBytes 由来）
     * @param freeBytesAtStart コピー開始時点の空き容量（stat 配下 dir）
     * @return 実際に書き込んだバイト数（コピー完走時）
     * @throws InsufficientStagingException 空き不足 / 上限超過
     */
    fun copyLimited(
        openSource: () -> InputStream,
        dest: File,
        maxBytes: Long,
        freeBytesAtStart: Long,
    ): Long {
        if (freeBytesAtStart < MIN_FREE_MARGIN_BYTES) {
            throw InsufficientStagingException(
                "insufficient storage for staging copy: free=$freeBytesAtStart < margin=$MIN_FREE_MARGIN_BYTES",
            )
        }
        try {
            var written = 0L
            openSource().use { source ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(COPY_BUF)
                    while (true) {
                        val read = source.read(buf)
                        if (read < 0) break
                        if (read == 0) continue
                        written += read
                        if (written > maxBytes) {
                            throw InsufficientStagingException(
                                "staging copy exceeded limit: $written bytes > max=$maxBytes",
                            )
                        }
                        out.write(buf, 0, read)
                    }
                }
            }
            return written
        } catch (e: InsufficientStagingException) {
            // 部分コピーを成果物として公開しない（§8.2: 半端な公開物を出さない）。
            dest.delete()
            throw e
        }
    }

    private const val COPY_BUF = 64 * 1024
}
