package com.imageshot.core.render

import kotlin.math.roundToInt

/**
 * 最小構成の sRGB 行列/TRC モニター ICC プロファイル（アセット不使用・純Kotlin生成）。
 *
 * 用途: JPEG 書き出しに APP2 (ICC_PROFILE) セグメントで埋め込む（JpegIcc 参照）。
 * 内容は意図的に「sRGB」と述べている最小要件のみ（v4準拠の matrix/TRC 表示系プロファイル）:
 *   - ヘッダ: バージョン 4.3（0x04300000）、device class 'mntr'、色空間 'RGB '、PCS 'XYZ '、
 *     rendering intent 0（perceptual）、PCS 白点 D50、作成日時は固定定数（2026-01-01、
 *     テスト決定性のため実行時刻に依存させない）。
 *   - タグ: desc/cprt（mluc）+ wtpt/rXYZ/gXYZ/bXYZ（XYZType、D50/Bradford 適応済み原色）+
 *     rTRC/gTRC/bTRC。
 *   - 原色（D50/Bradford 適応済み sRGB→XYZ の通例の行数値）:
 *     R=(0.4360747, 0.2225045, 0.0139322) G=(0.3850649, 0.7168786, 0.0971045)
 *     B=(0.1430804, 0.0605919, 0.7141733)
 *
 * 【TRC 表現の設計判断（明示）】sRGB EOTF は「x/12.92（x≤0.04045）/ ((x+0.055)/1.055)^2.4」の
 * 分段式。ICC parametricCurveType（'para'）の各 functionType は折返し点 d を −b/a として
 * 暗黙導出する形式に沿うため、sRGB の公式定数（d=0.04045 と a/b の組が厳密には整合しない）を
 * 主要パーサ（Skia/lcms 等）がすべて同一に解釈する確信が取れない。そのため
 * 'para' は採用せず、**sRGB EOTF の厳密式値から生成した 256 項の 'curv' テーブル**
 * （u16 閉区間 0..65535、線形補間前提の標準レイアウト）を採用する。
 * これはタグ定義上常に有効で、バイトレイアウトを本リポジトリの JVM テストで完全に
 * 検査できる（「検証できる最も安全なバイトレイアウト」の選択）。
 * テーブル化の近似誤差は標本点間で ≤ 1/65535（sRGB 定数自体の既知の3位の整合差は別）であり、
 * 実表色精度の定量的検証（ColorChecker/ΔE）は未実施・実施時に報告する。
 */
object MinimalSrgbIcc {

    /** プロファイル作成日の固定定数（dateTimeNumber: 2026-01-01 00:00:00）。決定性のため固定。 */
    private const val BUILD_YEAR = 2026
    private const val CURV_ENTRIES = 256

    fun profileBytes(): ByteArray {
        // 1) タグデータ部を先に生成し、オフセットを確定させる。
        val desc = mlucTag("sRGB IEC61966-2.1")
        val cprt = mlucTag("No license restrictions apply")
        val wtpt = xyzTag(D50_X, D50_Y, D50_Z)
        val rxyz = xyzTag(0.4360747, 0.2225045, 0.0139322)
        val gxyz = xyzTag(0.3850649, 0.7168786, 0.0971045)
        val bxyz = xyzTag(0.1430804, 0.0605919, 0.7141733)
        val rtrc = srgbCurvTag()
        val gtrc = srgbCurvTag()
        val btrc = srgbCurvTag()
        val tags = listOf(
            "desc" to desc, "cprt" to cprt, "wtpt" to wtpt,
            "rXYZ" to rxyz, "gXYZ" to gxyz, "bXYZ" to bxyz,
            "rTRC" to rtrc, "gTRC" to gtrc, "bTRC" to btrc,
        )

        val tagTableBytes = 4 + 12 * tags.size
        val dataStart = HEADER_SIZE + tagTableBytes
        var dataBytes = 0
        for ((_, data) in tags) dataBytes += data.size
        val profileSize = dataStart + dataBytes

        val out = ByteArray(profileSize)

        // ---- ヘッダ（128 バイト）----
        putU32(out, 0, profileSize) // プロファイル全体サイズ
        putU32(out, 4, 0) // preferred CMM: none
        // バージョン 4.3: 0x04 0x30 0x00 0x00
        out[8] = 0x04
        out[9] = 0x30
        out[10] = 0x00
        out[11] = 0x00
        ascii(out, 12, "mntr") // device class
        ascii(out, 16, "RGB ") // 色空間
        ascii(out, 20, "XYZ ") // PCS
        // dateTimeNumber（u16×6）。固定定数（実行時刻不依存・テスト決定性）。
        putU16(out, 24, BUILD_YEAR)
        putU16(out, 26, 1)
        putU16(out, 28, 1)
        putU16(out, 30, 0)
        putU16(out, 32, 0)
        putU16(out, 34, 0)
        ascii(out, 36, "acsp") // マジック
        putU32(out, 40, 0) // platform: none
        putU32(out, 44, 0) // flags
        putU32(out, 48, 0) // manufacturer
        putU32(out, 52, 0) // model
        putU32(out, 56, 0) // attributes lo
        putU32(out, 60, 0) // attributes hi
        putU32(out, 64, 0) // rendering intent 0 = perceptual
        // PCS 白点（PCS illuminant D50）
        putF1616(out, 68, D50_X)
        putF1616(out, 72, D50_Y)
        putF1616(out, 76, D50_Z)
        putU32(out, 80, 0) // creator
        // 84..99 profile ID（不在=0）
        // 100..127 reserved（0）

        // ---- タグテーブル（count + 9×12）----
        putU32(out, HEADER_SIZE, tags.size)
        var off = dataStart
        tags.forEachIndexed { i, (sig, data) ->
            val entry = HEADER_SIZE + 4 + 12 * i
            ascii(out, entry, sig)
            putU32(out, entry + 4, off)
            putU32(out, entry + 8, data.size)
            System.arraycopy(data, 0, out, off, data.size)
            off += data.size
        }
        check(off == profileSize) { "ICC assembly overflow: $off != $profileSize" }
        return out
    }

    /**
     * 標準 D50 白点（PCS 照明）。s15Fixed16 エンコードは ×65536 丸め。
     */
    private const val D50_X = 0.96420288
    private const val D50_Y = 1.0
    private const val D50_Z = 0.82490540

    private const val HEADER_SIZE = 128

    /** multiLocalizedUnicodeType（'mluc'）：単一レコード（en-US）＋UTF-16BE 文字列。 */
    private fun mlucTag(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_16BE)
        val size = 16 /* mluc hdr */ + 12 /* record */ + body.size
        val buf = ByteArray(pad4(size))
        ascii(buf, 0, "mluc")
        putU32(buf, 8, 1) // レコード数
        putU32(buf, 12, 12) // レコードサイズ
        buf[16] = 'e'.code.toByte() // language "en"
        buf[17] = 'n'.code.toByte()
        buf[18] = 'U'.code.toByte() // country "US"
        buf[19] = 'S'.code.toByte()
        putU32(buf, 20, body.size) // 文字列長（byte）
        putU32(buf, 24, 28) // 文字列オフセット（タグデータ先頭基準）
        System.arraycopy(body, 0, buf, 28, body.size)
        return buf
    }

    /** XYZType（'XYZ ' + reserved4 + s15Fixed16×3 = 20 バイト）。 */
    private fun xyzTag(x: Double, y: Double, z: Double): ByteArray {
        val buf = ByteArray(20)
        ascii(buf, 0, "XYZ ")
        putF1616(buf, 8, x)
        putF1616(buf, 12, y)
        putF1616(buf, 16, z)
        return buf
    }

    /**
     * sRGB EOTF の厳密式から生成した 256 項の 'curv' テーブル TRC。
     * curvType = 'curv' + reserved4 + count(4) + count×u16（0..65535 = 0.0..1.0）。
     * 入力は device 側の [0,1] を i/255 で標本化し、出力は PCS 側の同区間へ×65535 で丸める。
     */
    private fun srgbCurvTag(): ByteArray {
        val buf = ByteArray(12 + 2 * CURV_ENTRIES)
        ascii(buf, 0, "curv")
        putU32(buf, 8, CURV_ENTRIES)
        for (i in 0 until CURV_ENTRIES) {
            val x = i / (CURV_ENTRIES - 1.0)
            val y = srgbEotf(x)
            putU16(buf, 12 + 2 * i, (y * 65535.0).roundToInt().coerceIn(0, 65535))
        }
        return buf
    }

    /** sRGB EOTF（IEC 61966-2-1 の分段式・厳密形）。 */
    private fun srgbEotf(x: Double): Double {
        val v = x.coerceIn(0.0, 1.0)
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun Double.pow(p: Double): Double = Math.pow(this, p)

    /** 4 バイト境界への切り上げ（パディングを宣言サイズに含める — 末尾に未宣言の余りが残らない）。 */
    private fun pad4(n: Int): Int = (n + 3) / 4 * 4

    private fun putU32(dest: ByteArray, off: Int, v: Int) {
        dest[off] = (v ushr 24).toByte()
        dest[off + 1] = ((v ushr 16) and 0xFF).toByte()
        dest[off + 2] = ((v ushr 8) and 0xFF).toByte()
        dest[off + 3] = (v and 0xFF).toByte()
    }

    private fun putU16(dest: ByteArray, off: Int, v: Int) {
        dest[off] = ((v ushr 8) and 0xFF).toByte()
        dest[off + 1] = (v and 0xFF).toByte()
    }

    /** s15Fixed16Number（×65536 丸め）。 */
    private fun putF1616(dest: ByteArray, off: Int, v: Double) {
        putU32(dest, off, (v * 65536.0).roundToInt())
    }

    private fun ascii(dest: ByteArray, off: Int, s: String) {
        val b = s.toByteArray(Charsets.US_ASCII)
        System.arraycopy(b, 0, dest, off, b.size)
    }
}

/**
 * JPEG への APP2（ICC_PROFILE）セグメント挿入（ICC embedded profile in JPEG の正本句）。
 *
 * 入力は既存バイトを一切書き換えない（挿入は SOS 直前の連接領域のみ）。
 * - SOI (FFD8) で開始していない → null。
 * - セグメント走査（SOI〜SOS 直前）： 既に APP2 "ICC_PROFILE\0" がある → null（重複付与禁止）。
 *   EOI が SOS 前に出る（EOI-only）/ セグメント長の破綻・走査不能 → null。
 * - 挿入位置は SOS (FFDA) のマーカー直前。プロファイルは ≤65519 バイトずつの複数 APP2
 *   （ヘッダ "ICC_PROFILE\0" + seq(1〜) + count）に分割する。seq は 1 起点。
 *   セグメント長（長さフィールド値）は ≤65535 に収まる。
 */
object JpegIcc {

    /** APP2 セグメント1本へ入るプロファイル断片の最大byte数（= 65533 − 14: ICC_PROFILE\0+seq+count）。 */
    const val MAX_CHUNK_PAYLOAD: Int = 65533 - 14

    private val ICC_HEADER = "ICC_PROFILE".toByteArray(Charsets.US_ASCII) + 0x00.toByte()

    fun insertApp2IccProfile(jpeg: ByteArray, profile: ByteArray): ByteArray? {
        if (profile.isEmpty()) return null
        if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null

        // ---- 走査: 既存 ICC の有無と SOS 直前位置の特定 ----
        var i = 2
        var insertAt = -1
        while (i < jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) return null // マーカー位置に非FF（構造破綻）
            val markerStart = i
            while (i < jpeg.size && jpeg[i] == 0xFF.toByte()) i++ // fill bytes
            if (i >= jpeg.size) return null
            val marker = jpeg[i].toInt() and 0xFF
            if (marker == 0x00 || marker == 0xFF) return null // entropy stuffing / 不正連続FF
            when {
                marker == 0xD9 -> return null // EOI: SOS 前に終端（EOI-only 等は挿入不能）
                marker == 0xDA -> { insertAt = markerStart; break }
                marker == 0x01 || marker in 0xD0..0xD7 -> i += 1 // TEM / RST（SOS 前に出ないが構文上は長さなし）
                else -> {
                    // 長さ付きセグメント（APPn/COM/SOF/DHT/DQT/DRI…）
                    if (i + 3 > jpeg.size) return null
                    val len = ((jpeg[i + 1].toInt() and 0xFF) shl 8) or (jpeg[i + 2].toInt() and 0xFF)
                    if (len < 2) return null
                    val segEnd = i + 1 + len // マーカーの次バイトから len バイト
                    if (segEnd > jpeg.size) return null // 途切れ
                    if (marker in 0xE0..0xEF && startsWithIccHeader(jpeg, i + 3)) {
                        // 既に ICC APP2 が存在（重複付与禁止）。i+3 = マーカー(1) + 長さ(2) の直後。
                        return null
                    }
                    i = segEnd
                }
            }
        }
        if (insertAt < 0) return null

        // ---- 挿入セグメント列の生成（chunk 分割・seq は 1 起点）----
        // count は u8。上限超過は不正要求として null（静かな切断をしない）。
        val chunkCount = (profile.size + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD
        if (chunkCount > 255) return null
        val head = jpeg.copyOfRange(0, insertAt)
        val segments = ArrayList<ByteArray>(chunkCount)
        for (seq in 1..chunkCount) {
            val from = (seq - 1) * MAX_CHUNK_PAYLOAD
            val to = minOf(from + MAX_CHUNK_PAYLOAD, profile.size)
            val payload = profile.copyOfRange(from, to)
            // セグメント内容（長さフィールドの直後） = 11+1+1+1 + payload = 14 + payload
            // 長さフィールド値 = 内容 + 2（長さフィールド自身） = payload + 16（最大 65535）
            val lenField = payload.size + 16
            if (lenField > 65535) return null // 保険（MAX_CHUNK_PAYLOAD と整合するはず）
            val seg = ByteArray(lenField + 2) // 全長: FF E2(2) + 長さフィールド(2) + 内容(lenField)
            seg[0] = 0xFF.toByte()
            seg[1] = 0xE2.toByte()
            seg[2] = ((lenField ushr 8) and 0xFF).toByte()
            seg[3] = (lenField and 0xFF).toByte()
            // ICC ヘッダ（"ICC_PROFILE\0" 11+1）
            System.arraycopy(ICC_HEADER, 0, seg, 4, ICC_HEADER.size) // 4..15
            seg[16] = seq.toByte()
            seg[17] = chunkCount.toByte()
            System.arraycopy(payload, 0, seg, 18, payload.size)
            segments.add(seg)
        }

        // ---- 連接（既存バイトは無変換で保持する）----
        val total = head.size + segments.sumOf { it.size } + (jpeg.size - insertAt)
        val out = ByteArray(total)
        System.arraycopy(head, 0, out, 0, head.size)
        var w = head.size
        for (seg in segments) {
            System.arraycopy(seg, 0, out, w, seg.size)
            w += seg.size
        }
        System.arraycopy(jpeg, insertAt, out, w, jpeg.size - insertAt)
        return out
    }

    /** 長さフィールド直後の位置（at）から "ICC_PROFILE\0" が続くか判定。 */
    private fun startsWithIccHeader(jpeg: ByteArray, at: Int): Boolean {
        if (at + ICC_HEADER.size > jpeg.size) return false
        for (k in ICC_HEADER.indices) {
            if (jpeg[at + k] != ICC_HEADER[k]) return false
        }
        return true
    }
}
