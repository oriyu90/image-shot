package com.imageshot.feature.export

/**
 * 書き出しファイル名の生成・重複回避（§8.2: 既存と同名は新名生成、上書きは別の明示承認）。
 * 純Kotlin（JVM単体テスト対象）。
 */
object ExportNaming {

    /** MediaStore 書き込み不可文字列と制御文字を除去し、長さ上限を適用する。 */
    fun sanitizeBaseName(raw: String, maxLen: Int = 100): String {
        val stripped = raw
            .replace(ILLEGAL_PATTERN, "")
            .trim { it <= ' ' || it == '.' }
        return if (stripped.isEmpty()) DEFAULT_BASE_NAME else stripped.take(maxLen)
    }

    /**
     * 重複回避付きの表示ファイル名（拡張子別）。衝突番号は 2 から連番で付与する。
     * 例: "photo" → "photo.jpg"、"photo (2).jpg"。大文字小文字・拡張子の差分は等価に扱う。
     */
    fun composeUniqueFileName(base: String, ext: String, existingNames: Set<String>): String {
        val baseName = sanitizeBaseName(base)
        fun composed(n: Int): String =
            if (n <= 1) "$baseName.$ext" else "$baseName ($n).$ext"
        var n = 1
        while (composed(n) in existingNames.mapToLowerSet() ) {
            n++
        }
        return composed(n)
    }

    private fun Set<String>.mapToLowerSet(): Set<String> = asSequence().map { it.lowercase() }.toSet()

    /**
     * 重複回避の候補名だけが必要な場合（既存を除外した提案名）。
     * 「上書き必須承認は別操作」として、任意の上書きはこのモジュールでは提供しない。
     */
    fun noSuffixName(base: String, ext: String): String =
        "${sanitizeBaseName(base)}.$ext"

    private val ILLEGAL_PATTERN = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
    private const val DEFAULT_BASE_NAME = "photo"
}
