# Prism Photo（Image Shot / com.imageshot.app）— Android 16+ 写真現像・編集アプリ設計パック

作成: 2026-10-18 / 最終更新: 2026-10-26 / 状態: 調査に基づく**開発基準案** + Gradleマルチモジュール雛形。性能・画質の数値は実装済みを意味しない。

## 同梱物

1. `01_製品仕様書.md`: 製品目標、機能とリリース範囲、画面、画像形式、受入条件。
2. `02_実装定義書.md`: Kotlin/Compose/C++ NDK、RAW/ICC/HDR、編集DAG、JNI、DB、SAF、ExportJob、試験。
3. `03_ADR_設計判断記録.md`: 17のアーキテクチャ選定と背景・代替案・リスク・再検討条件。
4. `04_AI開発Skill構成_調査と導入.md`: 公式/コミュニティSkillの選別、OpenCode導入、複数エージェント分担。
5. `05_機能体系調査_実装ギャップ.md`: 写真編集機能を細分化した優先順位表と技術的ギャップ分析。
6. `AGENTS.md`: エージェントの短い全体作業指針。
7. `.opencode/skills/*/SKILL.md`: 開発専用の7つのAgent Skills（実際に配置可能）。
8. `qa/リリース試験マトリクス.md`: 対応画面・ファイル形式・安全・性能・出荷判定。
9. `templates/PR_CHECKLIST.md`: PR完了条件。

## Gradleマルチモジュール雛形

Android 16（API 36）以降専用。`./gradlew :app:assembleDebug` で `app` APK が生成される。

| モジュール | 役割 | 境界（02_実装定義書.md §2） |
|---|---|---|
| `app` | applicationId `com.imageshot.app`、MainActivity、Composeテーマ、navigation-compose、手動DI（AppContainer、Hilt不使用） | feature を束ねる |
| `core/model` | EditRecipe / EditEvent / RenderRequest / RenderResult / Asset / ErrorCode(§11)。kotlinx.serialization。純Kotlin JVM | 依存なし |
| `core/database` | Room (WAL) + KSP。AssetEntity / EditDocumentEntity / EditRevisionEntity(append-only) / EditSnapshotEntity / ExportJobEntity / ThumbnailEntity。recipe/events は JSON + schemaVersion で保存（CBOR化はTODO(ADR確認)） | feature → core |
| `core/storage` | ImportRepository（ContentResolver/SAF、fingerprint=sha-256、非seekable入力のstagingコピー） | 原本上書きなし |
| `core/render` | Kotlin/Bitmap 暫定トーンパイプライン（8bit sRGB CPU）。レシピ同値契約の土台 | NDK/C++ 実体は別モジュール |
| `feature/library` | 写真グリッド + Photo Picker 取り込み（INTERNET権限なし） | — |
| `feature/editor` | 編集画面骨格（プレビュー/スライダー/履歴） | プレビューサーフェスは Sprint C |
| `feature/export` | 書き出し画面 + ExportJob キュー（WorkManager実働は Sprint C） | — |

ビルド正本バージョンは `gradle/libs.versions.toml`（**PR-21・2026-10-25 更新: AGP 9.4.0 / built-in Kotlin 2.2.10 / KSP 2.3.12 / Gradle 9.6.0 wrapper** / Compose BOM 2025.06.01 / Room 2.8.5 / WorkManager 2.10.5 / Coil 3.3.0 / kotlinx-serialization 1.11.0 / navigation-compose 2.9.5）。built-in Kotlin は AGP 同梱版が正本であり独立には変更できない（KGP 由来プラグインも 2.2.10 に統一）。Coil 3.4+ と Compose BOM 2026.x は Kotlin/compileSdk 37 前提のため、compileSdk 36 の本雛形では旧世代を選択している（Kotlin 正本を 3.x に引き上げる際に再更新する）。

## 使用手順

新規Android Studioプロジェクトを作成後、この設計パック内の文書をそのプロジェクトの`docs/`に、`AGENTS.md`と`.opencode/skills/`をリポジトリルートに配置する。OpenCodeはプロジェクトルートで起動する。Skill探索先の一次資料: https://opencode.ai/docs/skills

開発は `02_実装定義書.md` §14 のSprint Aから着手する。最初の技術Spikeは①24–48MP RAW現像の速度とpeak RSS、②GPU+HDR表示面の色変換、③XMP取り込みの部分適用、④破損入力への安全な失敗を推奨する。

## 最優先の設計ポイント
- `minSdk=36`。大画面・折りたたみ対応。プレビューと調整UIはwindow幅ごとに配置変更。
- RAW/色/HDRはKotlin UIから分離。原本は絶対に変更しない。
- `.xmp` / `.lrtemplate` / `.dng`編集設定の読み込みは項目単位の部分対応を許容するが、全項目・全画質の一致は保証しない。
- ローカル完結、高色精度とクラッシュ復旧を優先。高度なAIは任意モジュール。
- モジュールやライブラリの採用は、ライセンス/サイズ/端末動作を実測して確定する。

## 注意
- リリース目標値（起動時間、クラッシュ率、サイズ、処理速度など）は**ベンチマーク実測ではない**。
- 本パックはアプリ本体、試験済みコーデック、RAW対応カメラの完全なリストを同梱するものではない。
- 標準ライブラリ以外のコード/AIモデル/レンズDBの配布条件は出荷前に法務・ライセンス確認する。
- 外部設定ファイルは部分的に読み取れても視覚結果が異なる場合がある。ユーザーへの表示でこの差を隠さない。
