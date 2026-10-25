package com.imageshot.core.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.security.MessageDigest

/**
 * 表示名解決（PR-19 / 06_未実装欠陥と実装計画.md C2・07_Wave3実装計画書.md §3）の複合返却。
 * 連鎖のどの段から名前が来たか等の出所情報は持たせない（07 §3: 単に (name, fullyResolved)）。
 */
internal data class ResolvedDisplayName(
    /** 空でない表示名（実名または hash プレースホルダ）。トリム済み。 */
    val name: String,
    /** 連鎖 a)〜d) のいずれかで実名を採用したとき true。hash プレースホルダのとき false。 */
    val fullyResolved: Boolean,
)

/**
 * 連鎖に必要な URI 文字列的事実（android 依存の採取を [AndroidDisplayNameSource] 側で1回だけ行い、
 * 連鎖本体はこれのみに依存して純Kotlin化する。各項は実装内で例外を吸収し null/既定に倒す）。
 */
internal data class UriNameFacts(
    /** URI の文字列表現（プレースホルダ hash の基盤・決定性のため不変）。取得不能なら空文字。 */
    val uriString: String,
    val lastPathSegment: String?,
    /** DocumentsContract.isDocumentUri（resolver 側の b) 段 gate に使う）。 */
    val isDocumentUri: Boolean,
    /** c) 段の MediaStore 系 authority 判定に使う。 */
    val authority: String?,
)

/**
 * 連鎖 a)〜c) の照会口（Android 依存を薄く切る面・実運用は [AndroidDisplayNameSource]）。
 * 実装は例外を出してよい（resolver 側で各段 runCatching に引き受ける契約。JVM fake が
 * SecurityException を注入できる）。JVM 単体テストは純 fake を注入して連鎖本体を検証する。
 */
internal interface DisplayNameSource {
    val facts: UriNameFacts

    /** a) OpenableColumns.DISPLAY_NAME 照会。null・空白・内部連番値になり得る。 */
    fun openableDisplayName(): String?

    /** b) document URI に限り ドキュメント表示名列（Document.COLUMN_DISPLAY_NAME）照会。非 document は null。 */
    fun documentName(): String?

    /** c) MediaStore 系 authority に限り `_display_name`。resolver 側でも authority gate を行う。 */
    fun mediaStoreDisplayName(): String?
}

/**
 * 実運用の照会実体（ContentResolver / DocumentsContract）。
 * 【JVM 単体テストの限界（正直な開示）】本クラスの Cursor 照会・DocumentsContract の実際の挙動は
 * フレームワーク実装であり JVM では検証しない（実機観察は 07 §19「Photo Picker クラウド URI の
 * 表示名挙動=観察未達」のとおり端末検証待ち。実装側も各段 runCatching で覆い例外を出さない）。
 */
internal class AndroidDisplayNameSource(
    private val contentResolver: ContentResolver,
    /** DocumentsContract.isDocumentUri に必要（applicationContext を渡す）。 */
    private val context: Context,
    private val uri: Uri,
) : DisplayNameSource {

    override val facts: UriNameFacts = UriNameFacts(
        uriString = runCatching { uri.toString() }.getOrNull().orEmpty(),
        lastPathSegment = runCatching { uri.lastPathSegment }.getOrNull(),
        isDocumentUri = runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false),
        authority = runCatching { uri.authority }.getOrNull(),
    )

    override fun openableDisplayName(): String? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    }.getOrNull()

    override fun documentName(): String? {
        if (!facts.isDocumentUri) return null
        // 07 §3 の「DocumentsContract.getDocumentName」は SDK 公開 API に存在しない（android-36 stub
        // 実測）。等価手段として Document.COLUMN_DISPLAY_NAME を照会する（SourceMetadataReader.readDocument
        // と同じ document URI 照会様式）。
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }

    override fun mediaStoreDisplayName(): String? {
        // 不明 authority に暗黙参照しない（SourceMetadataReader.isMediaStoreImageUri と同一規則）。
        if (!AssetDisplayNameResolver.isMediaAuthority(facts.authority)) return null
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
        return runCatching {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media._ID} = ?",
                arrayOf(id.toString()),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }
}

/**
 * 取り込み対象 URI の表示名を決める連鎖リゾルバ（PR-19・06 C2「表示名が Picker の ID のまま」の完全化）。
 *
 * 連鎖（07_Wave3実装計画書.md §3 PR-19 の設計）:
 * 1. a) `OpenableColumns.DISPLAY_NAME`（null/空白/内部連番のみなら次段へ）
 * 2. b) document URI のときのみ ドキュメント表示名列 `Document.COLUMN_DISPLAY_NAME` の照会
 *   （07 §3 は「DocumentsContract.getDocumentName」と記すが、同名の公開 API は存在しない
 *   （android-36 stub 実測）。等価手段として表示名列の照会を採用した。報告済み）。
 * 3. c) MediaStore 系 authority（"media" / "com.android.providers.media*"）の `_display_name`
 * 4. d) `uri.lastPathSegment` の正規化（doc-id 並びの下位名抽出。数字 ID 形は不採用）
 * 5. 連鎖全滅 ⇒ `img-<hash8>.<ext>` プレースホルダ
 *
 * 契約:
 * - 返却は [ResolvedDisplayName]（(name, fullyResolved) の複合返却。出所情報は持たせない）。
 * - 例外（SecurityException 等を含む）は連鎖の各段で吸収し、決して呼出し元へ投げない。
 *   **失敗時契約: 連鎖全滅は hash プレースホルダで応答（取り込み失敗にはしない）。**
 * - 照会はローカル provider のみ。画像内容・メタデータの外部送信は行わない（01 §12）。
 * - `fullyResolved=false` の警告表示（07 §3-2「表示名を復元できません」）は library 側の
 *   既存警告メカニズムと後続 PR で調整する（本 PR では UI 変更なし）。
 *
 * 【JVM 単体テストの限界（正直な開示）】連鎖本体 [resolveChain] は純Kotlinで fake source を
 * 注入して検証する（各段の例外注入も JVM で検証）。Android 照会実体 [AndroidDisplayNameSource]
 * はフレームワーク実装のため JVM 未検証（実機観察は端末検証待ち・07 §19 未観察）。
 */
internal class AssetDisplayNameResolver(
    /** 実運用の Android 依存 source 生成（1 URI = 1 source。JVM テストは純 fake に差し替える）。 */
    private val sourceFactory: (Uri) -> DisplayNameSource,
) {

    /**
     * 実運用入口。source 構築自体が不測の失敗をしても例外を出さず、「URI文字列のみ判明・
     * 全段不明」の代替 source で連鎖を流す（= プレースホルダへ。連鎖規則の正本は [resolveChain]）。
     * 呼出しは IO dispatcher 上のみ（照会を含む）。
     */
    fun resolve(uri: Uri, mimeType: String?, sniffedFormat: String?): ResolvedDisplayName {
        val source = runCatching { sourceFactory(uri) }.getOrNull() ?: run {
            object : DisplayNameSource {
                override val facts: UriNameFacts = UriNameFacts(
                    uriString = runCatching { uri.toString() }.getOrNull().orEmpty(),
                    lastPathSegment = null,
                    isDocumentUri = false,
                    authority = null,
                )

                override fun openableDisplayName(): String? = null

                override fun documentName(): String? = null

                override fun mediaStoreDisplayName(): String? = null
            }
        }
        return resolveChain(source, mimeType, sniffedFormat)
    }

    /**
     * 連鎖本体（純Kotlin・JVM 検証対象）。各段の呼出しを runCatching で包み、例外を出さずに
     * 次段へ継続する。採用値はトリム済み。全滅は hash プレースホルダ（[placeholder]）。
     */
    internal fun resolveChain(
        source: DisplayNameSource,
        mimeType: String?,
        sniffedFormat: String?,
    ): ResolvedDisplayName {
        val facts = runCatching { source.facts }
            .getOrNull()
            ?: UriNameFacts("", null, isDocumentUri = false, authority = null)

        // a) OpenableColumns.DISPLAY_NAME
        runCatching { source.openableDisplayName() }.getOrNull()
            ?.let { adopt(it) }
            ?.let { return ResolvedDisplayName(it, fullyResolved = true) }

        // b) document URI のときのみ ドキュメント表示名列を照会（非 document での照会はしない）。
        if (facts.isDocumentUri) {
            runCatching { source.documentName() }.getOrNull()
                ?.let { adopt(it) }
                ?.let { return ResolvedDisplayName(it, fullyResolved = true) }
        }

        // c) MediaStore 系 authority のときのみ _display_name（不明 authority は照会しない）。
        if (isMediaAuthority(facts.authority)) {
            runCatching { source.mediaStoreDisplayName() }.getOrNull()
                ?.let { adopt(it) }
                ?.let { return ResolvedDisplayName(it, fullyResolved = true) }
        }

        // d) lastPathSegment の正規化（不採用/空白/連番 tail は null で次へ）。
        normalizeSegment(facts.lastPathSegment)?.let {
            return ResolvedDisplayName(it, fullyResolved = true)
        }

        // 連鎖全滅（失敗時契約: 取り込み失敗にしない）。プレースホルダは決定的。
        return placeholder(facts.uriString, mimeType, sniffedFormat)
    }

    companion object {
        /**
         * 連鎖各段の値の採用判定（null=不採用=次段へ継続）。採用値はトリムして返す。
         * - null / 空白 / トリム後空 ⇒ 不採用。
         * - 数字のみ（内部連番 id）⇒ 不採用（07 §3 / 06 C2）。
         * - 数字+短拡張子（06 C2 実測例 `1743.jpg` 相当）⇒ 不採用。
         *   同形の実名写真（例 `20260125.jpg`）はプレースホルダ側に倒れる痛みを認める。
         *   口径は PR-19 の実機観察（07 §19 未観察）後に見直す（本判定は出所によらず統一適用）。
         */
        internal fun adopt(name: String?): String? {
            val trimmed = name?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            if (trimmed.all { it.isDigit() }) return null
            if (DIGIT_ID_WITH_SHORT_EXT.matches(trimmed)) return null
            return trimmed
        }

        /**
         * d) の正規化。SAF document id 並び（`primary:DCIM/IMG_1.jpg` 等）やパス的な尾から
         * ファイル名らしき部分を収める（最後の `/`、次いで `:` の後を採る）。
         * 空・トリム後空・連番尾は不採用（null → プレースホルダ側へ）。
         */
        internal fun normalizeSegment(segment: String?): String? {
            val raw = segment?.trim().orEmpty()
            if (raw.isEmpty()) return null
            var tail = raw
            val slashIdx = tail.lastIndexOf('/')
            if (slashIdx >= 0) tail = tail.substring(slashIdx + 1)
            val colonIdx = tail.lastIndexOf(':')
            if (colonIdx >= 0) tail = tail.substring(colonIdx + 1)
            return adopt(tail)
        }

        /**
         * MediaStore 系 authority 判定（[SourceMetadataReader] の isMediaStoreImageUri と同一規則）。
         * `media` と `com.android.providers.media*`（module 等）のみ c) 段の照会対象。
         */
        internal fun isMediaAuthority(authority: String?): Boolean =
            authority != null &&
                (authority == "media" || authority.startsWith("com.android.providers.media"))

        /**
         * 連鎖全滅時のプレースホルダ（07 §3 失敗時契約）。`img-<hash8>.<ext>`:
         * - hash8 = URI 文字列の SHA-256 先頭8文字（16進小文字）。同一 URI は常に同一表示名（決定的）。
         * - ext は mimeType → sniffedFormat（magic bytes 対応の実測形式）→ 既定 jpg の順
         *   （[placeholderExtension]）。既定 `.bin` にしない: 実際に取り込みを通る形式は
         *   ImportSniffer 対応（JPEG/PNG/WEBP/HEIF）であり、その主流 `.jpg` に合流させる。
         * - プレースホルダは表示補助である（パス鍵には使用しない・PR-8 契約。エクスポート既定名
         *   への流用時もユーザーが上書きできる）。
         */
        internal fun placeholder(
            uriString: String?,
            mimeType: String?,
            sniffedFormat: String?,
        ): ResolvedDisplayName = ResolvedDisplayName(
            name = "img-${digestHead8(uriString.orEmpty())}.${placeholderExtension(mimeType, sniffedFormat)}",
            fullyResolved = false,
        )

        /** SHA-256 digest の先頭8文字（16進小文字）。決定性のため URI 文字列から直接計算する。 */
        internal fun digestHead8(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }
                .take(8)

        /**
         * プレースホルダ拡張子の推定。mimeType を第一に（明示値）、不明なら sniff の対応結果
         * （[ImportSniffer.sniff] の supported 結果文字列・実コード確認済みの値）、両不全なら `jpg`。
         */
        internal fun placeholderExtension(mimeType: String?, sniffedFormat: String?): String {
            when (mimeType?.trim()?.lowercase()) {
                "image/jpeg", "image/jpg" -> return "jpg"
                "image/png" -> return "png"
                "image/webp" -> return "webp"
                "image/heif", "image/heif-sequence" -> return "heif"
                "image/heic", "image/heic-sequence" -> return "heic"
            }
            when (sniffedFormat) {
                "JPEG" -> return "jpg"
                "PNG" -> return "png"
                "WEBP" -> return "webp"
                "HEIF" -> return "heif"
            }
            // 両不全の既定（.bin にしない: 07 §3 の方針。取り込み通過形式の主流に合流）。
            return "jpg"
        }

        /** 06 C2 実測（`1743.jpg`）相当の「数字+短拡張子」内部連番形式。 */
        private val DIGIT_ID_WITH_SHORT_EXT = Regex("""^\d+\.[A-Za-z0-9]{1,5}$""")
    }
}
