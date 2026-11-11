# 14 GUI リメイク（ui-design-skills / ui-android）

対象: `com.imageshot.app` v0.1.0。2026-11-02 実施。
正本: `DESIGN_RULES.md`。トークン: `core:ui/src/main/kotlin/com/imageshot/core/ui/theme/`。

## 1. 何を変えたか

| 領域 | 変更前 | 変更後 |
|---|---|---|
| 色 | 画面ごとに `Color(0x…)` 直書き（27 箇所）。ライブラリは緑、編集は橙で不統一 | `Color.kt` の M3 全ロール + 単一アクセント（teal）。画面は `MaterialTheme.colorScheme` のみ |
| 余白 | `8.dp` などのリテラルが散在 | `Spacing.kt`（4dp 基準）+ `MaterialTheme.spacing` |
| 角丸・字 | 既定値とリテラル | `Shape.kt` / `Type.kt`（M3 type scale） |
| ライブラリ | 本文先頭に巨大な 3 ボタン、検索欄とチップが折返し、サムネイル 112dp 固定 | TopAppBar（取り込みは主操作 + オーバーフロー）、検索/チップの 1 段化、176dp の正方グリッド、空状態、2 ペイン詳細の再構成 |
| 編集 | タブが 3 列×2 行の箱、スライダー 80dp 行、橙アクセント | 横スクロールのピルタブ + 区切り線、テーマ色のスライダー、パネル内の階層整理 |
| 書き出し | 全幅に長文（2000px 超の行長） | 設定カード + ジョブの 2 カラム（840dp 以上）、内容上限 1080dp、注記は読み物幅 720dp |

## 2. 構造

- 新モジュール `core:ui`（Android library / Compose）にトークンと `ImageShotTheme` を集約。
  `app` と 3 feature が依存する。`feature` から `app` へは依存しない（従来の境界を維持）。
- `app/PictureTheme.kt` は削除し、`com.imageshot.core.ui.theme.ImageShotTheme` に統一。
- 常時ダーク固定・dynamic color なし（01_製品仕様書 §4.3 / ADR-016）。端末の壁紙色で
  写真の見え方が変わらないことを優先した。

## 3. レスポンシブ

| 幅 | ライブラリ | 編集 | 書き出し |
|---|---|---|---|
| < 600dp | 1 ペイン + グリッド | プレビュー + 下部パネル | 1 カラム |
| 600–839dp | 1 ペイン（列数増） | プレビュー + 右パネル | 1 カラム（上限 1080dp） |
| ≥ 840dp | 2 ペイン（一覧 + 詳細） | プレビュー + 右パネル | 設定 / ジョブの 2 カラム |

検証は TB710FU（2000×3200 / 400dpi = 800dp 幅 → 2 ペイン）と、
`wm size 1080x2400` + `wm density 420`（411dp 幅 → compact）で行った。

## 4. バージョン

- v0.1.1（versionCode 2）。挙動・DB・payload・文字列 key は v0.1.0 から不変。
  コミット `84c8cf9`、タグ `v0.1.1`、GitHub Release 公開（2026-11-11）。詳細は `13_設計評価とリリース判定.md` §8。

## 5. 検証（2026-11-02/03）

- `:app:assembleRelease` 成功、`res_values_en_check` PASS（library 52 key・plurals 対応）、
  `ui_strings_check` 違反 0。
- TB710FU 実機: 起動 FATAL 0、ライブラリ（2 ペイン）/ 編集 / 書き出しの描画を目視、
  compact（411dp）で 1 ペイン + 下部パネルを確認。
- 旧データはクリーンインストールで削除済み（ユーザー指示）。再取り込み 1 件で
  取り込み → 編集 → undo/redo → 書き出し（2019×1080 JPEG）まで通過。

## 6. 未対応・次段

- 編集画面のプレビュー余白（レターボックス）の活用、数値ダイアログの見直し。
- ライブラリの複数選択・一括操作、日付グループ見出し。
- M3 Expressive の新 API（Compose BOM 2026.x 系）は未導入。BOM 更新は Kotlin 3.x 移行とセット。
- ライトテーマは持たない（仕様どおり）。
