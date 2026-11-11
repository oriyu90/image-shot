# DESIGN_RULES — Image Shot

GUI の唯一の正本。UI に触る前に必ず読む。
トークンは `core/ui/src/main/kotlin/com/imageshot/core/ui/theme/` に置き、画面は
`MaterialTheme.colorScheme` / `typography` / `shapes` / `spacing` だけを参照する。

## 1. 採用アンカー

| 項目 | 決定 | 理由 |
|---|---|---|
| 主アンカー | **Material You**（`ui-android` skill #7） | Android 16 専用アプリ。M3 のロール・形状・モーション・予測型戻るが最も自然 |
| 色の生成 | **固定 seed `#3E7C6F` のダークスキーム**（dynamic color は使わない） | 01_製品仕様書 §4.3 / ADR-016: 端末壁紙由来の色で写真の見え方が変わってはならない |
| ライトテーマ | **持たない**（常時ダーク） | 同上。写真編集面は低反射の暗色固定 |
| 密度 | balanced | 一覧は詰め、編集は操作対象を大きく取る |
| 対象サーフェス | スマホ縦/横・タブレット・折りたたみ・DeX | `BoxWithConstraints` の幅で切替（後述） |

**禁止のブレンド**: Brutalist、Editorial serif、Apple Music のクロームは使わない。

## 2. トークン

| 種別 | ファイル | 備考 |
|---|---|---|
| Color | `theme/Color.kt` | M3 全ロール。`primary` は 1 系統のみ。`tertiary` を第2アクセントにしない |
| Type | `theme/Type.kt` | M3 type scale。family は `FontFamily.Default`（Android 14+ は Roboto Flex） |
| Shape | `theme/Shape.kt` | 4 / 8 / 12 / 16 / 28 dp |
| Spacing | `theme/Spacing.kt` | 2 / 4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 / 56 dp + `screenGutter` / `readingMaxWidth` |

`Color(0x…)`・`16.dp`・`TextStyle(fontSize=…)` を**画面ファイルに書かない**。
必要ならトークンを増やす（増やす場合はこの文書に追記）。

例外: 既存の `EditorPalette` のような画面内プライベート別名は、値を `Palette`（core:ui の
公開トークン object）へ 1:1 に張り替えてよい。新規コードは `MaterialTheme.colorScheme` を直接使う。

## 3. 有効な禁止パターン（`ui-android` skill の ban list）

1. **汎用グラデーション**（紫→桃、藍→紫、装飾目的の Brush）— 有効
2. **埋め草コピー**（Lorem ipsum / "Beautiful and intuitive" / 汎用 "Get Started"）— 有効
3. **任意値**（トークン外の dp / sp / Color）— 有効
4. **既定フォントの痕跡**（画面内でのフォント直指定）— 有効
5. **ガラスモーフィズム / blur** — 有効（全面禁止）
6. **カードの入れ子**（Card in Card、border と elevation の併用）— 有効
7. **汎用レイアウト**（中央寄せヒーロー + 3 カード機能紹介など）— 有効
8. **装飾モーション**（無限ループ、自動カルーセル、シマー乱用）— 有効

## 4. レスポンシブ規則

幅は `BoxWithConstraints` の `maxWidth` で判定する（WindowSizeClass 相当）。

| 幅 | クラス | ライブラリ | 編集 |
|---|---|---|---|
| < 600dp | compact | 1 ペイン。タップで編集へ | プレビュー + 下部パネル（タブ + スライダー） |
| 600–839dp | medium | 1 ペイン（グリッド列数が増える） | プレビュー + 右パネル（幅 320dp 上限） |
| ≥ 840dp | expanded | 2 ペイン（一覧 + 詳細 320–420dp） | プレビュー + 右パネル（幅 400dp 上限） |

- 横画面・DeX では**縦スクロールを本文に限定**し、画面全体を縦に伸ばさない。
- 読み物・フォームは `spacing.readingMaxWidth`（720dp）で行長を制限する。
- タッチターゲットは 48dp 以上。マウス（DeX）でも hover/focus が破綻しないよう、
  押下状態は色で示す（拡大縮小の装飾は使わない）。

## 5. 多言語

- 文字列は `feature/*/res/values/strings.xml`（ja）と `values-en/strings.xml`（en）の**両方**に同じ key で追加する。
- 追加後は `python3 tools/res_values_en_check.py` と `python3 tools/ui_strings_check.py` を通す。
- 文言は具体的に書く（"取り込む" > "OK"）。英語は sentence case。

## 6. 検証

```bash
./gradlew :app:assembleDebug
python3 tools/res_values_en_check.py
python3 tools/ui_strings_check.py
# 実機/エミュレータ: 縦横 + 幅 600/840dp 境界 + DeX 相当
```

UI 監査（`verification.md` の Token/UI Audit）を PR 説明に貼る。

## 7. 既知の制約

- Compose BOM 2025.06.01 / Material3 1.3.2 のため、M3 **Expressive** の新 API
  （shape morph, expressive motion tokens）は使えない。M3 1.3 の範囲で同じ意図を実装する。
- Navigation は navigation-compose。`ListDetailPaneScaffold` は使わず 2 ペインを自前で組む。
