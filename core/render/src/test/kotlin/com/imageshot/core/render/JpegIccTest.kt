package com.imageshot.core.render

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JPEG への APP2 (ICC_PROFILE) セグメント挿入（JpegIcc）と最小 sRGB ICC プロファイル生成
 * （MinimalSrgbIcc・コード生成・アセット不使用）の構造検証（JVM）。
 * エンコーダ実体を用いず、JPEG セグメント構造の合成バイト列で走査・挿入の契約を検査する。
 * テスト側は同じ構造規則の独立パーサを持つ（相互検査）。
 * 【明示】実機で BitmapFactory がこのプロファイル付き JPEG を SRGB 色空間として復号するかは
 * 未検証（on-device 統合テストで確認する項目・報告済み）。
 */
class JpegIccTest {

    // ---- テスト用 JPEG セグメント組み立てヘルパ（純バイト列）----

    private fun marker(m: Int): ByteArray = byteArrayOf(
        ((m ushr 8) and 0xFF).toByte(), (m and 0xFF).toByte(),
    )

    /** 長さ付きセグメント（マーカー2 + 長さ2〔長さは自身を含む〕 + ペイロード）。 */
    private fun segment(m: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return marker(m) + byteArrayOf(
            ((len ushr 8) and 0xFF).toByte(), (len and 0xFF).toByte(),
        ) + payload
    }

    /** JFIF APP0 相当のダミーペイロード。 */
    private fun dummyPayload(size: Int): ByteArray = ByteArray(size) { (it and 0x7F).toByte() }

    /** SOI + APP0 + DQT + SOF0 + SOS(+entropy) + EOI の最小構造。 */
    private fun minimalJpeg(): ByteArray {
        var out = byteArrayOf()
        out += marker(0xFFD8) // SOI
        out += segment(0xFFE0, dummyPayload(16)) // APP0
        out += segment(0xFFDB, dummyPayload(69)) // DQT
        out += segment(0xFFC0, dummyPayload(15)) // SOF0
        // SOS: ヘッダ3バイト + エントロピー相当（stuffing FF00 を含む）
        val sosPayload = byteArrayOf(0x01, 0x00, 0x00, 0x12, 0x34, 0xFF.toByte(), 0x00.toByte())
        out += segment(0xFFDA, sosPayload)
        out += marker(0xFFD9) // EOI
        return out
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private val ICC_HEADER: ByteArray = "ICC_PROFILE".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)

    private fun isIccApp2(b: ByteArray, markerByte: Int, lenFieldAt: Int): Boolean {
        val at = lenFieldAt + 2 // 長さフィールド直後 = ICC ヘッダ先頭
        return markerByte in 0xE0..0xEF &&
            at + ICC_HEADER.size + 2 <= b.size &&
            (0 until ICC_HEADER.size).all { b[at + it] == ICC_HEADER[it] }
    }

    /**
     * 独立パーサ: SOS 直前までのセグメント列（markerStart = FF run 先頭・長さ付きのみ）。
     */
    private data class Seg(val marker: Int, val markerStart: Int, val dataStart: Int, val lenField: Int)

    private fun segmentsBeforeSos(jpeg: ByteArray): List<Seg> {
        assertTrue("SOI minimum", jpeg.size >= 2)
        val segs = ArrayList<Seg>()
        var i = 2
        while (i < jpeg.size) {
            assertTrue("marker run expected FF at $i", (jpeg[i].toInt() and 0xFF) == 0xFF)
            val markerStart = i
            while (i < jpeg.size && (jpeg[i].toInt() and 0xFF) == 0xFF) i++
            assertTrue("no dangling fill bytes at $i", i < jpeg.size)
            val m = jpeg[i].toInt() and 0xFF
            assertTrue("structure broken at $i", m != 0x00 && m != 0xFF && m != 0xD9)
            if (m == 0xDA) {
                segs.add(Seg(m, markerStart, i + 1, -1))
                return segs
            }
            val len = u16(jpeg, i + 1)
            segs.add(Seg(m, markerStart, i + 3, len))
            i = i + 1 + len
        }
        error("SOS not found")
    }

    /** 独立パーサ: 出力から ICC APP2 グループ（seq, count, payload）を取り出す。 */
    private fun iccApp2Payloads(jpeg: ByteArray): List<Triple<Int, Int, ByteArray>> {
        val out = ArrayList<Triple<Int, Int, ByteArray>>()
        var i = 2
        while (i < jpeg.size) {
            while (i < jpeg.size && (jpeg[i].toInt() and 0xFF) == 0xFF) i++
            val m = jpeg[i].toInt() and 0xFF
            if (m == 0xDA || m == 0xD9) return out // SOS/EOI で終端（長さフィールドを持たない）
            val len = u16(jpeg, i + 1)
            if (isIccApp2(jpeg, m, i + 1)) {
                val seq = jpeg[i + 3 + ICC_HEADER.size].toInt() and 0xFF
                val count = jpeg[i + 3 + ICC_HEADER.size + 1].toInt() and 0xFF
                val payload = jpeg.copyOfRange(i + 3 + ICC_HEADER.size + 2, i + 1 + len)
                out.add(Triple(seq, count, payload))
            }
            i = i + 1 + len
        }
        return out
    }

    /** ICC APP2 グループを除去したバイト列（「byte喪失無し」の基準化）。既存byteは完全保持。 */
    private fun withoutIccApp2(jpeg: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        out += jpeg[0]
        out += jpeg[1] // SOI
        var i = 2
        while (i < jpeg.size) {
            assertTrue((jpeg[i].toInt() and 0xFF) == 0xFF)
            val runStart = i
            while (i < jpeg.size && (jpeg[i].toInt() and 0xFF) == 0xFF) i++
            val m = jpeg[i].toInt() and 0xFF
            if (m == 0xDA || m == 0xD9) {
                // SOS/EOI 以降はそのまま（エントロピーを保つ）。
                for (k in runStart until jpeg.size) out += jpeg[k]
                return out.toByteArray()
            }
            val len = u16(jpeg, i + 1)
            if (!isIccApp2(jpeg, m, i + 1)) {
                for (k in runStart until i + 1 + len) out += jpeg[k] // セグメント全体を完全複製
            }
            i = i + 1 + len
        }
        return out.toByteArray()
    }

    // ---- MinimalSrgbIcc（プロファイル本体）----

    private fun u32(p: ByteArray, off: Int): Int = ((p[off].toInt() and 0xFF) shl 24) or
        ((p[off + 1].toInt() and 0xFF) shl 16) or
        ((p[off + 2].toInt() and 0xFF) shl 8) or
        (p[off + 3].toInt() and 0xFF)

    private fun f1616(p: ByteArray, off: Int): Double = u32(p, off) / 65536.0

    private fun asciiAt(p: ByteArray, off: Int, expected: String) {
        val b = expected.toByteArray(Charsets.US_ASCII)
        for (i in b.indices) assertEquals("ascii@off=$off+$i", b[i], p[off + i])
    }

    /** タグ表の走査（テスト側独立）。 */
    private fun tagData(p: ByteArray, sig: String): Pair<Int, Int> {
        val count = u32(p, 128)
        for (i in 0 until count) {
            val e = 132 + 12 * i
            if (String(p.copyOfRange(e, e + 4), Charsets.US_ASCII) == sig) {
                return u32(p, e + 4) to u32(p, e + 8)
            }
        }
        error("missing tag $sig")
    }

    private fun srgbEotf(x: Double): Double {
        val v = x.coerceIn(0.0, 1.0)
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    @Test
    fun profileHeader_structurallyCorrect() {
        val p = MinimalSrgbIcc.profileBytes()
        assertEquals("4-byte aligned", 0, p.size % 4)
        assertEquals("size field == total", p.size, u32(p, 0))
        asciiAt(p, 36, "acsp") // magic
        asciiAt(p, 12, "mntr") // device class
        asciiAt(p, 16, "RGB ") // colour space
        asciiAt(p, 20, "XYZ ") // PCS
        assertEquals(0x04, p[8].toInt() and 0xFF) // version 4.3
        assertEquals(0x30, p[9].toInt() and 0xFF)
        assertEquals(0x00, p[10].toInt() and 0xFF)
        assertEquals(0x00, p[11].toInt() and 0xFF)
        assertEquals("rendering intent perceptual", 0, u32(p, 64))
        // PCS 照明 D50。
        assertEquals(0.96420288, f1616(p, 68), 2.0e-4)
        assertEquals(1.0, f1616(p, 72), 2.0e-4)
        assertEquals(0.82490540, f1616(p, 76), 2.0e-4)
        // dateTimeNumber: 年=2026（作成日は固定定数・実行時刻不依存）。
        assertEquals(2026, u16(p, 24))
        assertEquals(1, u16(p, 26))
    }

    @Test
    fun profileTagTable_exactlyNineTags_alignmentAndNoGapsOrTrailingPadding() {
        val p = MinimalSrgbIcc.profileBytes()
        val count = u32(p, 128)
        assertEquals(9, count)
        val entries = (0 until count).map { i ->
            val e = 132 + 12 * i
            Triple(
                String(p.copyOfRange(e, e + 4), Charsets.US_ASCII),
                u32(p, e + 4),
                u32(p, e + 8),
            )
        }
        assertEquals(
            setOf("desc", "cprt", "wtpt", "rXYZ", "gXYZ", "bXYZ", "rTRC", "gTRC", "bTRC"),
            entries.map { it.first }.toSet(),
        )
        val dataStart = 128 + 4 + 12 * count
        // サイズ4アライン・範囲内・隙間/余分pad無し（実装はパディングを宣言サイズに含める）。
        var cursor = dataStart
        for ((sig, off, size) in entries.sortedBy { it.second }) {
            assertEquals("$sig offset 4-aligned", 0, off % 4)
            assertTrue("$sig dataStart", off >= dataStart)
            assertEquals("$sig size 4-aligned", 0, size % 4)
            assertEquals("$sig contiguous, no gaps", cursor, off)
            assertTrue("$sig in bounds", off + size <= p.size)
            cursor = off + size
        }
        assertEquals("profile ends exactly at last tag (no trailing pad)", p.size, cursor)
    }

    @Test
    fun profileTags_descCprtMluc_primariesAndTrcValues() {
        val p = MinimalSrgbIcc.profileBytes()
        // desc: mluc（enUS）+ "sRGB IEC61966-2.1"。
        val (dOff, _) = tagData(p, "desc")
        asciiAt(p, dOff, "mluc")
        assertEquals(1, u32(p, dOff + 8)) // レコード数
        assertEquals(12, u32(p, dOff + 12)) // レコードサイズ
        assertEquals('e'.code, p[dOff + 16].toInt())
        assertEquals('n'.code, p[dOff + 17].toInt())
        assertEquals('U'.code, p[dOff + 18].toInt())
        assertEquals('S'.code, p[dOff + 19].toInt())
        val dLen = u32(p, dOff + 20)
        val dStrOff = u32(p, dOff + 24)
        assertEquals(
            "sRGB IEC61966-2.1",
            String(p.copyOfRange(dOff + dStrOff, dOff + dStrOff + dLen), Charsets.UTF_16BE),
        )
        // cprt: mluc（enUS）+ "No license restrictions apply"（CC0相当の自由テキスト）。
        val (cOff, _) = tagData(p, "cprt")
        asciiAt(p, cOff, "mluc")
        assertEquals(
            "No license restrictions apply",
            String(p.copyOfRange(cOff + 28, cOff + 28 + u32(p, cOff + 20)), Charsets.UTF_16BE),
        )
        // 白点（wtpt = D50）。
        val (wOff, _) = tagData(p, "wtpt")
        asciiAt(p, wOff, "XYZ ")
        assertEquals(0.96420288, f1616(p, wOff + 8), 2.0e-4)
        assertEquals(1.0, f1616(p, wOff + 12), 2.0e-4)
        assertEquals(0.82490540, f1616(p, wOff + 16), 2.0e-4)
        // 原色（D50/Bradford 適応済みの sRGB→XYZ 行）。
        val (rOff, _) = tagData(p, "rXYZ")
        assertEquals(0.4360747, f1616(p, rOff + 8), 2.0e-4)
        assertEquals(0.2225045, f1616(p, rOff + 12), 2.0e-4)
        assertEquals(0.0139322, f1616(p, rOff + 16), 2.0e-4)
        val (gOff, _) = tagData(p, "gXYZ")
        assertEquals(0.3850649, f1616(p, gOff + 8), 2.0e-4)
        assertEquals(0.7168786, f1616(p, gOff + 12), 2.0e-4)
        assertEquals(0.0971045, f1616(p, gOff + 16), 2.0e-4)
        val (bOff, _) = tagData(p, "bXYZ")
        assertEquals(0.1430804, f1616(p, bOff + 8), 2.0e-4)
        assertEquals(0.0605919, f1616(p, bOff + 12), 2.0e-4)
        assertEquals(0.7141733, f1616(p, bOff + 16), 2.0e-4)
        // TRC: curv・256項・sRGB EOTF 式値（丸め差 ±1 内）・単調・黒/白の固定点。
        for (sig in listOf("rTRC", "gTRC", "bTRC")) {
            val (off, size) = tagData(p, sig)
            asciiAt(p, off, "curv")
            assertEquals(256, u32(p, off + 8))
            assertEquals(12 + 2 * 256, size)
            var prev = -1
            for (i in 0..255) {
                val expected = (srgbEotf(i / 255.0) * 65535.0).roundToInt()
                val got = u16(p, off + 12 + 2 * i)
                assertTrue("$sig i=$i within ±1 of exact sRGB EOTF", abs(got - expected) <= 1)
                assertTrue("$sig i=$i monotonic", got >= prev)
                prev = got
            }
            assertEquals("$sig black == 0", 0, u16(p, off + 12))
            assertEquals("$sig white == 65535", 65535, u16(p, off + 12 + 2 * 255))
        }
    }

    // ---- JpegIcc（APP2 挿入）----

    @Test
    fun insert_singleChunk_parsesBack() {
        val profile = MinimalSrgbIcc.profileBytes()
        val jpeg = minimalJpeg()
        val out = JpegIcc.insertApp2IccProfile(jpeg, profile)!!
        // APP2 ICC グループが1群・seq=1・count=1・payload はプロファイルそのもの。
        val group = iccApp2Payloads(out)
        assertEquals(1, group.size)
        assertEquals(1, group[0].first)
        assertEquals(1, group[0].second)
        assertArrayEquals(profile, group[0].third)
        // 挿入は SOS 直前で、元セグメントは元順序のまま。
        val segs = segmentsBeforeSos(out)
        assertEquals(listOf(0xE0, 0xDB, 0xC0, 0xE2, 0xDA), segs.map { it.marker })
        // セグメント長は規定域（length field ≥ 2）。
        assertTrue(segs.dropLast(1).all { it.lenField >= 2 })
    }

    @Test
    fun insert_noBytesLost_removingInsertedRegionEqualsOriginal() {
        val profile = MinimalSrgbIcc.profileBytes()
        val jpeg = minimalJpeg()
        val out = JpegIcc.insertApp2IccProfile(jpeg, profile)!!
        // 挿入領域を除去すると元byte列と完全一致（喪失・改変なし）。
        assertArrayEquals(jpeg, withoutIccApp2(out))
    }

    @Test
    fun insert_idempotency_secondPassReturnsNull() {
        val profile = MinimalSrgbIcc.profileBytes()
        val once = JpegIcc.insertApp2IccProfile(minimalJpeg(), profile)!!
        assertNull("second pass must refuse (no duplicate)", JpegIcc.insertApp2IccProfile(once, profile))
    }

    @Test
    fun insert_multiChunk() {
        // 70000 バイト（>65519）→ 2 チャンク。seq 1..2・count=2・結合で入力プロファイルと一致。
        val big = ByteArray(70_000) { (it * 31 + 7).toByte() }
        val out = JpegIcc.insertApp2IccProfile(minimalJpeg(), big)!!
        val group = iccApp2Payloads(out)
        assertEquals(2, group.size)
        assertEquals(1, group[0].first)
        assertEquals(2, group[1].first)
        assertEquals(2, group[0].second)
        assertEquals(2, group[1].second)
        assertEquals(JpegIcc.MAX_CHUNK_PAYLOAD, group[0].third.size)
        assertEquals(70_000 - JpegIcc.MAX_CHUNK_PAYLOAD, group[1].third.size)
        assertArrayEquals(big, group[0].third + group[1].third)
        assertArrayEquals(minimalJpeg(), withoutIccApp2(out))
    }

    @Test
    fun insert_rejectsMalformedInputs() {
        val profile = MinimalSrgbIcc.profileBytes()
        // SOI 無し。
        assertNull(JpegIcc.insertApp2IccProfile(byteArrayOf(0x00, 0x01), profile))
        // EOI-only（SOS 無し → 挿入点なし）。
        assertNull(JpegIcc.insertApp2IccProfile(marker(0xFFD8) + marker(0xFFD9), profile))
        // SOS 未到達（EOI に出会う）。
        assertNull(
            JpegIcc.insertApp2IccProfile(
                marker(0xFFD8) + segment(0xFFDB, ByteArray(10)) + marker(0xFFD9),
                profile,
            ),
        )
        // 長さ破綻（len < 2）。
        val badLen = marker(0xFFD8) + byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x00, 0x01) + marker(0xFFDA)
        assertNull(JpegIcc.insertApp2IccProfile(badLen, profile))
        // 途切れ（len がバッファ超過）。
        val truncated = marker(0xFFD8) + byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x50.toByte(), 0x10) + marker(0xFFDA)
        assertNull(JpegIcc.insertApp2IccProfile(truncated, profile))
        // 空プロファイル。
        assertNull(JpegIcc.insertApp2IccProfile(minimalJpeg(), ByteArray(0)))
    }

    @Test
    fun insert_nonIccApp2DoesNotBlockInsertion() {
        // 既存 APP2 が ICC でなければ挿入可能（既存byte保持・後続解析も破綻しない）。
        val jpeg = marker(0xFFD8) +
            segment(0xFFE2, "FooBar".toByteArray(Charsets.US_ASCII)) +
            marker(0xFFDA) + marker(0xFFD9)
        val out = JpegIcc.insertApp2IccProfile(jpeg, MinimalSrgbIcc.profileBytes())!!
        val group = iccApp2Payloads(out)
        assertEquals(1, group.size)
        assertEquals(1, group[0].first)
    }

    @Test
    fun insert_existingIccFragmentsAcrossChunks_areRejectedAsOneGroup() {
        // 既存 ICC がマルチチャンク（seq=1,count=2）でも検出して null（重複付与の禁止）。
        val payload = ICC_HEADER + byteArrayOf(0x01, 0x02) + dummyPayload(20)
        val jpeg = marker(0xFFD8) + segment(0xFFE2, payload) + marker(0xFFDA) + marker(0xFFD9)
        val group = iccApp2Payloads(jpeg)
        assertEquals(1, group.size)
        assertEquals(1, group[0].first)
        assertEquals(2, group[0].second)
        assertNull(JpegIcc.insertApp2IccProfile(jpeg, MinimalSrgbIcc.profileBytes()))
    }
}
