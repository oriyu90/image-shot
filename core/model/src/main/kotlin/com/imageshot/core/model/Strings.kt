package com.imageshot.core.model

/**
 * 文字列リソース取得の契約（PR-27 G1 文字列外部化）。
 *
 * ViewModel は Context を保持しない（02_実装定義書.md 想定の責務分割・テスト容易性）。
 * 画面直書き文言は各 feature の strings.xml に正本を置き、VM が文言を組み立てる場合は
 * この取得関数だけを app 層（AppContainer/MainActivity）から注入する。
 * - 取得は Android の `Context.getString(resId, args)` に委譲される前提
 *   （書式引数は platform フォーマッタで展開 → PR-27 以前の Kotlin 文字列テンプレートと
 *   byte 等価の出力を維持する）。
 * - 純 Kotlin であり、core:model は Android リソースに依存しない
 *   （依存方向: feature → core:model、core:model → なし）。
 */
fun interface Strings {
    /** 文字列リソース ID と書式引数から文言を組み立てる。`args` は platform フォーマッタへ素通し。 */
    fun get(resId: Int, vararg args: Any?): String
}
