# Image Shot（Prism Photo）

Android 16+（API 36）専用の、非破壊・オフライン優先・高色精度な写真編集アプリです。
**Image Shot** is a non-destructive, local-first photo editor for Android 16+ (API 36).

- パッケージ / Package: `com.imageshot.app`
- 作者 / Author: **Yuki_Orita**
- 公式サイト / Website: https://studio-rizi.pages.dev/projects/image-shot/
- Discord: https://discord.gg/x7KXhNTD8M
- X: https://x.com/InovateOfRIZI

## 特長 / Features

- **非破壊編集**: 追記型イベント + スナップショット。undo/redo、強制終了後の復元。
- **豊富な調整**: 露出/コントラスト/ハイライト/シャドウ/白/黒、WB（温度/色合い）、
  自然な彩度/彩度、master + R/G/B カーブ、HSL 8 系統。
- **ジオメトリ**: 自由角度回転（±45°微調整・数値入力）、反転、プリセット/自由クロップ。
- **ディテール**: シャープ + 輝度ノイズ低減（色差保持）。
- **局所補正**: 直線/円形/ブラシのマスク、feather、マスク内の tone/WB/color 補正。
- **書き出し**: JPEG/PNG、品質指定、SAF、EXIF whitelist（GPS 除外）、HDR gainmap policy。
- **UI**: 日本語 / English、edge-to-edge、スマホ縦横・タブレット 2 ペイン対応。
- **プライバシー**: INTERNET 権限なし。原本を上書きしない。広域ストレージ権限なし。

## インストール / Install

1. [Releases](https://github.com/oriyu90/image-shot/releases) から署名済み APK を取得。
2. Android 16 以降の端末で「提供元不明のアプリ」を許可してインストール。
3. 写真は Photo Picker または SAF から取り込みます（クラウド送信なし）。

> デバッグビルドは配布用ではありません。リリース APK は `upload` キーで署名されています。

## ビルド / Build

```bash
# JDK 17+ / Android SDK 36
./gradlew test
./gradlew :feature:editor:lintDebug :feature:export:lintDebug :feature:library:lintDebug
./gradlew :app:assembleRelease :app:bundleRelease
python3 tools/ui_strings_check.py
```

署名は `local.properties` の `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` /
`RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` を読みます。**キーストアとパスワードは
この public リポジトリに含めません**（保守用の private リポジトリで管理）。

構成: AGP 9.4.0 / Gradle 9.6.0 / built-in Kotlin 2.2.10 / KSP 2.3.12 / Room 2.8.5 /
Compose BOM 2025.06.01 / WorkManager 2.10.5。

| モジュール | 役割 |
|---|---|
| `app` | 画面遷移、手動 DI、テーマ、署名設定 |
| `core:model` | 純 Kotlin のレシピ/イベント/マスク仕様 |
| `core:render` | 8bit sRGB CPU パイプライン（preview=export 共有） |
| `core:database` | Room v1 + append-only 編集履歴 |
| `core:storage` | SAF/Photo Picker 取り込み、指紋、可用性 |
| `feature:library` / `feature:editor` / `feature:export` | 画面 |
| `benchmark` | golden ΔE / NFR 計測（androidTest） |

## 既知の制限 / Known limitations

- **RAW 現像は未対応**です。Sony ARW などの非 DNG RAW は「非対応候補」として明示的に拒否し、
  DB へ登録しません（DNG/ARW は ADR-027 / 今後の LibRaw 検討対象）。
- 透視・光学補正、クロップのエッジドラッグ、chroma ノイズ低減は未実装です。
- 48MP 超級の性能・メモリ、4GB 端末、折りたたみ/マルチウィンドウ、数値入力の IME は未検証です。
- 8bit sRGB CPU パイプラインです（float/HDR パイプラインは未実装）。

## ドキュメント

設計・実装の正本はリポジトリ内の以下です。

- `01_製品仕様書.md` / `02_実装定義書.md` / `03_ADR_設計判断記録.md`
- `06_未実装欠陥と実装計画.md` / `07_Wave3実装計画書.md` / `09_Wave3残作業と以降の実装計画.md`
- `10_品質精査と修正記録.md` / `11_Sony_ARW検証とアプリアイコン.md` / `12_多解像度UI精査と適応化.md`
- `08_文字列台帳.md`（日本語/英語の文字列正本と例外）

## ライセンス / License

MIT License — see [LICENSE](LICENSE). Copyright (c) 2026 Yuki_Orita.
