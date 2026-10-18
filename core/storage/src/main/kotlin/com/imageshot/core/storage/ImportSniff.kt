package com.imageshot.core.storage

/**
 * magic bytes sniff（02_実装定義書.md §7.1: 拡張子だけを信用しない）。
 *
 * - 判定はヘッダー段階の第1フィルタであり、正本は decoder+検証結果（§6）。
 * - §6 の A 区分（JPEG/PNG/WebP/HEIF）のみ supported=true。
 * - TIFF 系（DNG/カメラRAW）・AVIF は「非対応候補」として明示する
 *   （01_製品仕様書.md: 対応外の深い階層・機種別差があるものは部分対応/閲覧のみとして明示）。
 * - 純Kotlin（Android依存なし）。JVM unit test の対象。
 */
internal data class SniffOutcome(
    val format: String,
    /** §6 A区分の暫定対応判定。false でも rawCandidate=true なら「非対応候補」として明示する。 */
    val supported: Boolean,
    /** デコーダー検証待ちの非対応候補（RAW/DNG/AVIF等）。UI へ明示するために分離する。 */
    val rawCandidate: Boolean,
    val warnings: List<String>,
)

internal object ImportSniffer {

    /**
     * 先頭バイト列から形式を判定する。
     * 非対応を「黙って無視」しない。候補として明示するための警告文を付与する。
     */
    fun sniff(head: ByteArray): SniffOutcome {
        val jpeg = head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
        val png = head.size >= 8 && head[0] == 0x89.toByte() &&
            head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()
        val webp = head.size >= 12 && String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WEBP"

        if (jpeg) return SniffOutcome("JPEG", supported = true, rawCandidate = false, warnings = emptyList())
        if (png) return SniffOutcome("PNG", supported = true, rawCandidate = false, warnings = emptyList())
        if (webp) return SniffOutcome("WEBP", supported = true, rawCandidate = false, warnings = emptyList())

        if (head.size >= 12 && String(head, 4, 4) == "ftyp") {
            val brand = String(head, 8, 4)
            return when {
                brand.startsWith("hei") || brand.startsWith("mif1") || brand.startsWith("msf1") ->
                    SniffOutcome("HEIF", supported = true, rawCandidate = false, emptyList())
                brand.startsWith("avi") ->
                    SniffOutcome("AVIF", supported = false, rawCandidate = true,
                        listOf("AVIF: §6 A/B目前。端末OSデコーダー検証が未達のため非対応候補"))
                brand.startsWith("crx") || brand.startsWith("cam") ->
                    SniffOutcome("RAW_ISOBMFF", supported = false, rawCandidate = true,
                        listOf("RAW: brand=$brand デコーダー検証待ち（機種/圧縮方式別判定は§4.1）"))
                else -> SniffOutcome(
                    "ISOBMFF_$brand",
                    supported = false,
                    rawCandidate = false,
                    listOf("ISOBMFF brand=$brand: 映像等の対象外形式（§13 非対象）"),
                )
            }
        }

        if (head.size >= 4 && (String(head, 0, 2) == "II" || String(head, 0, 2) == "MM")) {
            // TIFF 家族: DNG とカメラRAW（同一拡張子でも機種/圧縮差がある §6 注）の確定は
            // デコーダー検証後。現時点では RAW 現像が未実装のため「非対応候補」と明示する。
            return SniffOutcome(
                "TIFF_FAMILY",
                supported = false,
                rawCandidate = true,
                listOf("TIFF家族: DNG/カメラRAW。デコーダー検証未達のため非対応候補（機種/圧縮方式別に確定予定）"),
            )
        }

        if (head.size >= 3 && String(head, 0, 3) == "GIF") {
            return SniffOutcome("GIF", supported = false, rawCandidate = false,
                listOf("GIF: §6 C区分（測定後に追加検討）"))
        }

        if (head.isEmpty()) {
            return SniffOutcome("UNKNOWN", supported = false, rawCandidate = false,
                listOf("空ストリーム"))
        }

        return SniffOutcome("UNKNOWN", supported = false, rawCandidate = false,
            listOf("既知のmagicに一致せず（対応判定は候補として明示）"))
    }

    /** SAF フォルダー走査（ACTION_OPEN_DOCUMENT_TREE）で mime が image で始まるもののみ対象化する。 */
    fun isImageMime(mime: String?): Boolean = mime != null && mime.startsWith("image/")

    /** 登録・表示に許すローカル取得スキーマ。ネットワーク経由の暗黙読み込み禁止（オフライン優先）。 */
    fun isAllowedLocalScheme(scheme: String?): Boolean = scheme in setOf("content", "file")
}
