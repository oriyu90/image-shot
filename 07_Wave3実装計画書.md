# 07 Wave-3 実装計画書（PR-17〜30 詳細）

作成: 2026-10-20 / 前提コミット: `7c3b9d9`（Wave-1 完了・kill 500/500・R8 実機回帰 緑後）
正本関係: 計画の正本は `06_未実装欠陥と実装計画.md` §3.2。**本書はその展開（実装粒度）であり、本書と 06 §3.2 が矛盾した場合は 06 を正とする。** 仕様は `01`/`02`、判断記録は `03`、調査記録は `05`。

---

## 0. 共通ルール（全 PR 適用）

### 0.1 検証区分の凡例
本書の記述は次の3区分で管理する（矛盾した主張を作らないため。AGENTS.md 禁止事項の運用）:
- **[実測]** — 本プロジェクトの実機（TB710FU・API 36）やテストで確認済みの事実。
- **[文書]** — 外部公式文書に根拠を持つ事実（日付・出典併記、端末実測は未実施と明言）。
- **[計画]** — 本書で設計した内容。実装と実測で確定するまで「実装済み/達成」と書かない。

### 0.2 失敗時契約の様式
各 PR の設計には「失敗時どうなるか」を必ず1行で書く。既存の準拠例: STAGING_DISCARD / FAILED_PERMANENT（書き出し）、SourceDecode の null 返却・未知は unknown 保持。

### 0.3 測定方法の様式
測定値には測定条件を必ず添える（端末/ビルド型/入力ソース/プロセス数）。既存の台帳例: `benchmark/nfr_catalog.md` NFR-06/07/08 の測定条件列。未測定項目は「未測定」のまま維持する。

### 0.4 開発環境の既知制約
- ビルドは `--offline` 系を基本とし、ネットワーク取得が必要な PR（bundletool jar・LibRaw ソース・サンプル画像）では入手手段を手順に明記する。
- androidTest の実行様式（[実測] 既知手順）:
  - `adb shell am instrument -w -e class <setName> com.imageshot.core.render.test/androidx.test.runner.AndroidJUnitRunner`
  - `... com.imageshot.core.database.test/androidx.test.runner.AndroidJUnitRunner`
  - benchmark モジュールのみ `com.imageshot.benchmark.test/com.imageshot.benchmark.MacrobenchmarkRunnerStub`
- kill injection 中の端末占有: 作業者は単一。並行 automation と衝突させない（2026-10-19 教訓）。

### 0.5 モジュール地図（当て先）
| 領域 | 現存ファイル（[実測] Wave-1 完了時点） |
|---|---|
| 復号 | `core/render/src/main/kotlin/com/imageshot/core/render/SourceDecode.kt`（ImageDecoder 共有・`SourceDecodePolicy.PLATFORM_APPLIED` 確定）/ `SourceOrientation.kt`（BitmapFactory 退避用の自前転置） |
| 色管理 | `SourceDecode.kt`（setTargetColorSpace(SRGB)・`colorSpaceConverted` ゲート）/ `JpegIcc.kt`（`MinimalSrgbIcc`・APP2 検出/挿入） |
| develop/tonal | `RecipeToneCurve.kt`（`tintGains`・`buildChannelLuts`）/ `ExportRenderer.kt`・`SimpleBitmapPhotoRenderer.kt`（同一ノード契約） |
| 取り込み/サムネイル | `core/storage/ContentUriImportRepository.kt` / `core/storage/ThumbnailGenerator.kt`（level0=256px/level1=1024px・JPEG q90・Semaphore(2)・冪等 [実測]） |
| DB | `core/database/Entities.kt`・`Daos.kt`・`ImageShotDatabase.kt`（**schema version 1**・schema 変更は原則 PR-29 までしない。PR-29 も payload JSON 方式なら schema v1 継続） |
| UI | `feature/library/.../LibraryScreen.kt`+`LibraryViewModel.kt`（`thumbnailPaths`・`onLibraryVisible` [実測]）/ `feature/editor` 2ファイル / `feature/export/.../ExportScreen.kt`+`ExportViewModel.kt` |
| DI/Wiring | `app/src/main/kotlin/com/imageshot/app/AppContainer.kt`・`MainActivity.kt` / `app/proguard-rules.pro` |
| 計測 | `benchmark/`（GoldenPreviewExportDeltaETest・Startup/FrameTimingBenchmark・`nfr_catalog.md`・`run_kill_injection.sh`・bundletool 寸法用スクリプトは本書で新設） |

---

## 1. PR-17: bundletool サイズ実測（NFR-08 補完）

- **目的**: NFR-08 の残部（機種別配信後の実寸）を台帳化する。欠陥当て先: 06 の F1/E 系線・NFR-08。
- **根拠**: [文書] bundletool 公式: `get-device-spec` → `build-apks` → `get-size total` は `--dimensions=SDK,ABI,SCREEN_DENSITY,LANGUAGE`（min/max）と端末指定の両方を取れる（developer.android.com/tools/bundletool・2026-09 更新確認）。
- **設計（[計画]）**: 新規スクリプト `benchmark/run_nfr08_sizes.sh`:
  1. bundletool jar を公式リリースから取得（Apache-2.0・バージョンと取得 URL を同じ台帳に記録）。JDK17 は既存開発環境で動作前提。
  2. `java -jar bundletool.jar get-device-spec --output=benchmark/fixtures/device_tb710fu.json`（接続端末の実寸 JSON を保存・コミット対象は寸法だけ含む形にする）。
  3. `:app:bundleRelease` → `build-apks --bundle=... --mode=default`（全寸法）と `--device-spec` 版の2系統。
  4. `get-size total --dimensions=ALL`（min/max）・端末指定版・`--mode=universal` の3数値を NFR-08 行へ追記。
- **測定注意**: get-size の「download」と「install」の別分類の呼び方は実行時出力に合わせて記録する（用語の断定をしない）。
- **受入条件（[計画]→実測で確定）**: 3数値+条件が `nfr_catalog.md` NFR-08 行に記載され、app コードに差分 0。
- **リスク**: bundletool の Java バージョン要件（用に応じ JDK17 環境手当）。失敗時契約: スクリプトは非 0 終了で中止し台帳に未測定を維持。
- **工数**: S（当日可）/ **依存**: なし。

## 2. PR-18: golden しきい値の暫定化解除（NFR-07）

- **目的**: NFR-07 の「暫定しきい値」を実測分布に基づく固定値に昇格。
- **根拠**: [実測] 現行基線（中立 mean 0.10 / 非中立 0.51 / ダウンサンプル 0.53・p95<1.3・孤立領域の max 11〜15 は外れ値）。[計画] 分布の安定性が確認されるまで固定値を決めない。
- **設計（[計画]）**:
  1. `GoldenPreviewExportDeltaETest` を 5 連続実行（同一ソース・同一端末）→ 各統計量（mean/p95/p99/max）の変動幅を記録。
  2. しきい値案 = 実測 mean の上限値に有意なマージンを加算した値。根拠付きで `nfr_catalog.md` NFR-07 行を固定値化。
  3. ΔE2000 を補助指標として試験的に計算・比較（純 Kotlin 実装）。**主指標は ΔE76 を継続**（契約連続性・[計画]）。採否判断のみ。
  4. （併存する改善）P3 合成ソース golden ケース・追加検討: P3DecodeRoundTripTest で変換効率は実測済のため、golden に P3 を追加するかは分布結果で判断。
- **受入条件**: NFR-07 行から「暫定」文言が消え、根拠（5 実測分布）が引用される。
- **リスク**: 変動が大きい場合（sample 決定の非決定性など）は契約の再検討（仕様 `01` §NFR-07 の原義に立ち返る）。失敗時契約: 固定値化は中止し台帳は現行のまま。
- **工数**: S / **依存**: なし（PR-21 と非干渉）。
- **ADR**: 不要（判定基準の台帳化のみ）。

## 3. PR-19: 表示名完全化（C2・PR-4 の乗り継ぎ）

- **目的**: Photo Picker 経路（ローカル/クラウド）を含む表示名取得の保証。
- **根拠**: [文書] Photo Picker はクラウド合流（Google Photos・CloudMediaProvider）が進行し、URI がクラウド提供になり得る → OPENABLE_COLUMNS の DISPLAY_NAME が常にローカル実名とは限らない（developer.android.com・2026-08 更新）。[実測] SAF 経路は `OpenableColumns.DISPLAY_NAME` 照会済みで正常（2026-10-19 ライブラリ観察）。
- **設計（[計画]）**: `core/storage/AssetDisplayNameResolver.kt`（新規・純 Kotlin+resolver 隔離）:
  1. 連鎖: `OpenableColumns.DISPLAY_NAME` → (document URI の場合は) 投影列 `Document.COLUMN_DISPLAY_NAME` 照会 → MediaStore 系 `_display_name` → `uri.lastPathSegment` の正規化（数字 ID のみならプレースホルダ形式 `img-<hash8>.jpg`）。※計画時に想定した `DocumentsContract.getDocumentName` は SDK 公開 API に存在しないことを実装時に確認（2026-10-25・android-36 stub 実測）— 実装は等価の投影列照会を採用。
  2. 返却は `(name, fullyResolved: Boolean)`。`fullyResolved=false` の際は既存 ADR-014 型の警告表示（「表示名を復元できません」）を library ヘッダに1回だけ表示（schema 変更不要・表示名は既存 `Asset.displayName` 列に取り込み時点で保存）。
  3. **DB/Schema 不変**（重要）。
- **手順（実装順）**: 純関数抽出 → JVM テスト（fake resolver） → `ContentUriImportRepository` 結線 → 実機 Photo Picker 観察（uiautomator1回通し。ローカル+可能ならクラウド URI）→ 台帳記録。
- **テスト**: JVM: 連鎖の各段+数字 ID フォールバック・SecurityException 時の抑制。androidTest: fake provider 連携は既存 `core:database` 流儀に合う形で追加検討。
- **受入条件**: SAF/Picker 両方の取得名観察が台帳に記録・JVM テスト緑・警告文言の表示整合。
- **リスク**: クラウド URI が環境に無い場合の観察欠（未観察として台帳記載）。失敗時契約: 連鎖全滅時は プレースホルダ名+警告（取り込み失敗にしない）。
- **工数**: S / **依存**: なし。

## 4. PR-20: オフライン完全性（NFR-11）

- **目的**: 機内モード下での全フロー緑+通信試行ゼロの実証。
- **根拠**: [計画] 製品仕様 `01` のオフライン優先設計。権限面は manifest 静的確認で裏取り。
- **設計/手順**:
  1. `AndroidManifest.xml` の declares-permission に `INTERNET` が無いことを確認・記録（構造的根拠）。
  2. `adb shell cmd connectivity airplane-mode enable` の可否実測（可否を記録）。不発時は `svc wifi disable`+（モバイル構成なら）`svc data disable`。
  3. 機内モード下で: 取り込み→エディタ描画→UI 書き出し COMPLETE→kill injection 30回 → 復帰後 disable。
  4. 台帳 NFR-11 行に測定条件（どの手段で機内化したか）併記。
- **テスト**: androidTest は ADB 接続下で実行されるため機内モードと独立（端末内の無通信状態の検証に使用）。
- **受入条件**: オフライン下全フロー緑・新規 FATAL 0・NFR-11 行が「未測定」から実測へ。
- **リスク**: 機内モード切替が automation で不可の場合は手動手順書を残す（手動+記録で受入）。失敗時契約: 書き出しは既存分類（STAGING_DISCARD/FAILED_PERMANENT）に統一。
- **工数**: S / **依存**: なし。

## 5. PR-21: AGP 9.4 更新（16KB 既定化込み）

- **目的**: RAW/NDK 作業の足場整備・Play 要件（16KB・[文書] 2027-02-01 更新不可化）への先行適合。
- **根拠**: [文書] AGP 9.0.1 stable（2026-01）→ 9.4.0（2026-09）。AGP9 は **built-in Kotlin**（同モジュールで kotlin-android と混在不可・`android.builtInKotlin=false` の opt-out は AGP10 で廃止）、Gradle 9.6 / JDK17 / BuildTools 36 / **既定 NDK 28.2.13676358（16KB 初回適合）**・R8 `proguard-android.txt` 退役（本プロも optimize 版使用中で準拠済み）。AGP9.0 の新 R8 option `processkotlinnullchecks` は unset で既定動作。
- **設計/手順**:
  1. `gradle/libs.versions.toml`（AGP/Gradle wrapper/Kotlin）→ wrapper Gradle 9.6。
  2. Android 系モジュールの `kotlin-android` プラグイン除去（built-in Kotlin 化）。`core:model` は `kotlin("jvm")` のまま（[実測] 純 JVM）。
  3. `enableAppCompileTimeRClass` 既定 flip（false→true）: R class 定数参照の switch 相当の `when` で問題が出た場合のみ改修・全コードは Kotlin なので影響評価は当該 PR 内。
  4. リグレッション一式: 全 JVM/androidTest（golden 3）緑・`assembleRelease` 成功・APK/AAB サイズ比較（PR-17 の寸法と接続）・kill 注入 50 回・リリース版実機スモーク（2026-10-19 手順を踏襲）。
- **受入条件**: 上記リグレッション全緑+16KB 適合（r28 既定で本来適合・`readelf -h` 利用可能な場合は記録）。
- **リスク**: built-in Kotlin と既存 Compose compiler の結合が意外な警告を出す可能性 → 併せて Kotlin/Compose BOM を当該 AGP 対応バージョンに引き上げ。失敗時契約: 更新は revert 可能にし、revert 事由を台帳に記録。
- **工数**: M / **依存**: なし（先着推奨・上位依存 PR-22/24 の足場）。
- **ADR**: 更新自体は ADR 不要（環境基盤の追随）。

## 6. PR-22: S-03 HDR spike → ADR-026（A3 stage-1 判断）

- **目的**: A3（Gainmap 未処理。06 棚卸し A3・`01` C-03）の stage-1 policy を実測で決定し ADR-026 に記録。
- **根拠**: [文書] platform `Bitmap` 基本操作（回転・スケールなど）は gainmap を自動保全・高度編集は「gainmap が不適になり得る」→ `getGainmap()`→編集→`setGainmap()` が公式案内（developer.android.com/media/grow/ultra-hdr/edit）。Ultra HDR v1.1: ISO 21496-1 併記を本体側で encode（v1+1.1 の両立）・decode は ISO 21496-1 優先（hdr-image-format v1.1）。Coil/Glide の表示対応の公式案内あり。
- **fixture（[計画]）**: 自作サンプルを AOSP libultrahdr CLI（Apache-2.0）で生成（SDR jpg+gainmap jpg+metadata → UltraHDR jpg）。**端末撮影は TB710FU 対応未確認** → 未対応なら生成物1本で進め、台帳に限界を明記。
- **spike 項目（androidTest `GainmapProbeDeviceTest` を core:render に追加）**:
  1. decode: UltraHDR ソース → `bitmap.hasGainmap` 検出・gainmap 寸法/`maxDisplayBoost` の値観察。
  2. 編集+compress: 乗算フィルタのみ適用（WB gains/露出相当の LUT）→ `compress(JPEG)` → 再 decode → gainmap 有無・寸法保全・ICC 有無を確認（**compress での保全可否は端末実測・断定しない**）。
  3. SDR-only 出力: gainmap 除去経路（新 Bitmap への複写+XMP `hdrgm`/ISO 剥離）のヘッダ確認。
  4. 非乗算フィルタ（コントラスト S カーブ・HSL）適用時に gainmap 未修正だと生じる不整合を観察（理論上の妥当性を反証する観察）。
- **ADR-026 判断構造**:
  - **案 A（SDR-only 明示）**: 編集済み出力は SDR のみ+告知。実装は最も安全・小さい。HDR 情報は消失する（ただし原本非破壊）。
  - **案 B（条件付き pass-through）**: 乗算型 LUT（WB/露出）且つ gainmap 無修正の場合に限り gainmap を保全した出力。spike ② で compress 保全緑が前提。
  - ADR-026 に「stage-2（gainmap 同時編集）は後続・座標系（ISO 21496-1 優先 decode）を踏襲」の再検討条件を記す。
- **受入条件**: ADR-026 確定・spike の実測表を台帳化・警告文言の草案。
- **リスク**: compress 保全が不可なら案 B は libultrahdr NDK 依存が発生 → その時は依存を持ち込まないため案 A を stage-1 とする。
- **工数**: M / **依存**: PR-21（NDK 利用時）・fixture 生成は macOS で可。

## 7. PR-23: A3 stage-1 実装 + B2 EXIF pass-through

- **目的**: spike 結論の反映。HDR の最低処理・書き出し EXIF の最小 pass-through。
- **設計（[計画]）**:
  1. `SourceDecode` の Result に `hasGainmap: Boolean` を追加（[実測] Gainmap は ImageDecoder decode で Bitmap に付くため検出は decode 後・**DB schema 変更なし**（schema は PR-29 でも payload JSON 方式のため変更しない・schema v1 継続）。
  2. `ExportRenderer`: ADR-026 の policy 分岐。案 B 採用時は乗算型判定を `RecipeToneCurve` の 乗算型フィルタの型分類（WB/露出系のみ stage-1 の保全対象）を明示。
  3. **B2 EXIF**: 書き出し時に `ExifInterface`（既存依存 **1.4.2**〔[実測] 2026-10-25: `saveAttributes()` が UltraHDR の APP2/XMP/hdrgm/ISO 21496/MPF マーカを共存させる。androidx 定数名正本: TAG_ISO_SPEED_RATINGS・ORIENTATION 等〕）で whitelist copy（Make/Model/LensModel/DateTime/DateTimeOriginal/FNumber/**ISO〔TAG_ISO_SPEED_RATINGS〕**/FocalLength/ExposureTime・orientation は正立済みのため 1 を書き込み・GeoTag は権利上書かない（既定 OFF・台帳に記録）。`hasGainmap=false` 化の出力では XMP `hdrgm`/GainMap 系も剥がす。
  4. UI 警告文言（ADR-014 型）の整備（stage-1 policy 整合）。
- **テスト**: JVM: whitelist copy の純ロジック/除外タグ検証。androidTest: EXIF 付与後の読み戻し・gainmap policy 両案・全回帰（golden 3・8 DB・kill 30）。
- **受入条件**: policy 分岐の実機確認・EXIF 読み戻しの台帳記録・golden ΔE 回帰なし。
- **リスク**: JPEG 再圧縮位相での EXIF 書込が ICC APP2 を壊すかは既存の Skia ICC 埋め込みとの順序（encode→ExifInterface 書込み→必要なら既存 JpegIcc 経路と順序統合）で確認。失敗時契約: EXIF 書込失敗時も書き出し自体は継続・ジョブ状態は VERIFYING で EXIF 欠落を警告に落とす（FAILED にしない: 撮影情報欠落は品質劣化ではなく情報劣化）。
- **工数**: M / **依存**: PR-22（ADR-026）。

## 8. PR-24: C1 RAW Spike S-01 → ADR-027

- **目的**: RAW（`01` C-01・06 棚卸し C1）の処理方式を確定。
- **根拠**: [文書] NDK ImageDecoder 対応形式に "Digital negatives (via the DNG SDK)"（developer.android.com・2026-09）。Java 側 ImageDecoder が同一エンジンで DNG 復号が見込めるが端末実測は未 → 本 spike で確認。[文書] LibRaw 0.22.2（LGPL/CDDL 選択・16KB は NDK r28 既定適合）。
- **fixture 調達（[計画]・判断待ち⑥連動）**:
  1. 自前生成: DngCreator（CameraX RAW_SENSOR）で TB710FU 撮影が可能か確認（可能なら3条件撮影: 逆光/暗部/測光条件）。
  2. 収集: ライセンス明示のある現実サンプル（raw.pixls.us 等・ライセンス文書を当該台帳に記録）・Apple ProRAW 様 DNG（HDR gainmap 内蔵 DNG）の存在確認。
  3. ライセンスの許諾範囲（どこまで repo に含めるか）を台帳に明示（第三者ブランド・互換素材禁止の AGENTS 規定に照会）。
- **spike 項目**:
  1. Java `ImageDecoder` で DNG 実測: 復号可否・寸法（activeArea 対応）・`outColorSpace`（DNG は linear 持ってくるか・成分コンバート済み sRGB か）・`hasGainmap`・48MP メモリ実測（C-04 併用）。
  2. LibRaw 0.22.2 の NDK 統合 PoC（1カメラ分）: `dcraw_make_mem_thumb`・`rawdata.raw_image` の全体時間・メモリ・so サイズ（16KB 適合確認）。
  3. platform vs LibRaw の色比較（小画像 ΔE）。
- **ADR-027 判断構造**: 案 α: platform DNG primary（NDK 依存ゼロ・LibRaw 不採用・非DNG は未対応警告）； 案 β: platform DNG primary + LibRaw fallback（サイズ増+NDK 管理あり）； 案 γ: LibRaw primary（true develop）。stage-1 は α or β を基本とし「[実測] 端末間差=TB710FU 1台」の限界を明記。
- **受入条件**: ADR-027 確定・DNG 種別別の実測表（fixtures 台帳化）・LibRaw 採用なら NDK/16KB/サイズの実数値。
- **リスク**: platform DNG 復号が機種/OS 依存の可能性（Samsung/Quram 系などベンダ独自実装の存在は文書観察あり [実測外]・公式 ImageDecoder 経由なら共通）→ 台帳に「TB710FU 一台での事実」と明記し、他端末観察を次回実施項目へ。失敗時契約: 復号不可ソースは既存契約（unknown にしない・警告+取り込み不可を明示）に統一。
- **工数**: L（1〜2 セッション）/ **依存**: PR-21（NDK 28.2 既定）・fixture 調達は判断待ち⑥。

## 9. PR-25: RAW pipeline 第一段（ADR-027 の選択に従う）

- **目的**: RAW の取り込み→プレビュー→書き出しを契約テストで締める。
- **設計（[計画]・ADR-027 α シナリオ例）**: `SourceDecode` に DNG 認識 path（mime/ColorSpace フラグ・gainmap 有無）を追加・develop pipeline 上の取り扱いは「DNG=端末内で最終生成済みの高ビット深度画像」扱い（stage-1・真のリニア develop は γ シナリオ・後続）:
  1. 取り込み時の fingerprint（既存正規経路）と RAW ラベルの台帳化。
  2. develop/preview/export 全経路が既存 `RecipeToneCurve`・`ExportRenderer` 変更なしで結線するかを確認（複数解像度・sample 対応・ICC 経路）。
  3. 書き出し形式: stage-1 は JPEG（sRGB・ICC）のみ・RAW である旨を UI 表示。
- **テスト**: androidTest: DNG fixture の取り込み→書き出し完全経路・golden ΔE ケース追加（RAW 取扱い）。
- **受入条件**: 上記契約緑+`UNKNOWN` 色空間ルートへの誤落下の排除・NFR-02/04（メモリ・FPS）において 48MP proxy の実測値台帳化。
- **リスク**: ADR-027 β で LibRaw fallback が絡む場合は `dcraw_make_mem_thumb` 統合と 16KB/署名/`libraw` 版管理を含む・工数+M。失敗時契約: RAW decode 失敗=取り込み不可+警告（AGENTS: デコード可能性根拠なき断定禁止に対応）。
- **工数**: L / **依存**: PR-24。

## 10. PR-26: NFR-10 メモリ超過対策 + PR-8 効果測定

- **目的**: メモリ超過→低品質 preview 降格（NFR-10）の実装と、サムネイル特性の初回数値。
- **設計（[計画]）**:
  1. `PreviewDecodeScheduler`（core:render）: pixel budget（仕様 `01` NFR-02 に基づく上限値）・source pixel 数 → `SourceDecode.decode(sample)` の sample 段階決定。
  2. `AppContainer`/MainActivity に `onTrimMemory` 接続（`TRIM_MEMORY_RUNNING_LOW` で sample+1 段階の re-decode 依頼）。
  3. 同時 preview bitmap の保持数≤2（decode キャッシュの既存契約は変更しない）。
- **測定**:
  1. 48MP proxy（自生成 JPEG 8000x6000 sample/RAW は fixture 調達後）で正常表示+降格動作+FATAL 0（kill injection 30 回と併用）。
  2. **PR-8 効果測定**（[計画]）: `benchmark` に LibraryDisplayBenchmark 追加（1 asset・サムネイル有無を run-as DB 操作で手動切替）・cold 起動→library 初期表示の TTID/framework gfxinfo・meminfo 取得。
- **受入条件**: 上記動作+台帳（NFR-10 は**部分達成**: 低品質降格は実装・「4GB 端末×48MP RAW」の真の実測は端末調達まで）・サムネイル効果数値の台帳記載。
- **リスク**: proxy での検証は RAM 制約の実確認にはならない（誠実に明記）。失敗時契約: 降格失敗（sample 上限に達した場合）を UI で可視化・render 中断は crash ではなくステータス通知。
- **工数**: M / **依存**: fixture 調達（RAW proxy 用途・判断待ち⑥）。

## 11. PR-27: G1 文字列外部化

- **既存計画継承**: 06 旧 PR-10 の再編成。
- **設計（[計画]）**: ①feature 画面の直書き文言 strings.xml 化（jp デフォルト・en は別 PR）②警告文言（ADR-014 型）の全一元化（`R.string.*` 一覧性を lint で担保）③availability/切り分け文言の表示時間の統一。
- **テスト**: `feature:*` compile+strings 参照 lint・英語環境での layout 溢れ目視（大型・最小幅の両端）。
- **受入条件**: コードに文言ハードコードが残らない（grep 台帳）・動作変化なし。
- **工数**: M / **依存**: PR-23 の警告文言併合後に実施推奨（二重手入れを避ける）。
- **実施結果（2026-10-25・完了）**: 台帳チェッカー `tools/ui_strings_check.py` **違反 0・例外 29 行**（`08_文字列台帳.md` §2 構造的例外 6 ファイル / §3 実行記録）。`feature:*` lintDebug 3 モジュール error 0 / unit 232/232 / `:app:assembleRelease` 緑 / androidTest に HSL_BANDS 型変更参照 0。実機 TB710FU は端末既定ロケールが **en-US** のため、en 文言未作成下のフォールバック挙動（default res = ja 表示）をライブラリ/編集（基本調整・HSL タブ）のスクリーンショット目視で確認・FATAL 0。フォント最大 2.0 目視: タブ+hint 行の高さ増で編集パネル初回 viewport にスライダーが画面外になるがスクロールで到達・文字切れ/クラッシュなし（**PR-30 F3 の改善候補**: パネル初回表示位置の調整）。最小幅相当（`wm size 800x1280` = 横 602dp・試験後 reset）目視: タブ hint 折返し正常・ライブラリ導入チップ行は横スクロール構造で全ボタン完全表示・編集コンテンツはスクロール到達可・FATAL 0。文言変更 2 件（ExportScreen 支援注記の PR-23 実装反映 等）は 08 台帳 §5 に記録。

## 12. PR-28: D1/D4・D2（M2 edward 拡張）

- **既存計画継承**: 06 旧 PR-14+15 の再編成。仕様: `02` §5.2 D1（R/G/B 個別カーブ）/D4（自由角度・自由クロップ）/D2（ディテール）。
- **設計要点（[計画]）**:
  1. **D1 per-channel curves**: payload JSON を拡張（master 曲線型の同構造で r/g/b を追加・kotlinx.serialization ignoreUnknownKeys の後方互換で DB 不変）・`buildChannelLuts` の引数拡張。
  2. **D4**: 自由角度（任意角 rotation・エッジ塗り仕様）、自由クロップ（任意矩形）。逆写像（Matrix）・ジオメトリ_payload 変更（既存 90°/crop 型の構造を extension で保護）。
  3. **D2 ディテール**: unsharp（3x3 luminance・処理量制限・48MP ではタイル処理 or 導入判断）、NR 軽量版（限定的）。
- **テスト**: JVM: 各計算純関数+サンプル行列の golden的固定値・androidTest: preview=export 同一（ΔE76 追加ケース）・48MP proxy 性能記録。
- **受入条件**: 各機能で preview=export 同一+Undo/Redo 往復+DB 互換性（payload JSON 互換）を検証。
- **リスク**: 空間フィルタは develop 性能に直接当たる（NFR-04 の p95 100ms との境界明示）。失敗時契約: 性能が超過の際は「処理が重いため自動降格しました」明示を UI に。
- **工数**: M/L（分割可能）/ **依存**: schema 不変の確証（payload JSON 互換）検証が先行チェック。

### 12.1 PR-28a 実施結果（✅ 2026-10-26・1 コミット〔push なし・⑧〕）

**実装**:

1. **`RecipeToneCurve.buildChannelLuts`**: master を先に評価した後、表示ドメインで各チャンネルカーブを評価して合成。空/中立チャンネルは null（恒等 short-circuit）・不正形（非単調/範囲外）は null。**master-only 経路面の浮動小数点演算順序を不変に保ち**、既存 golden 3 ケースのビット決定論を保全（下記基線一致が実測根拠）。
2. **payload v1 拡張**（Room schema 不変・`ignoreUnknownKeys` 後方互換）: `{"curve":{"ch"?:"r"|"g"|"b","rgb":[[x,y]...],"r"?:[...],"g"?:[...],"b"?:[...]}`。`rgb` 必須・非中立チャンネルのみ格納・`ch` は履歴表示用の補助メタ（**適用は curve 全量**・不正 `ch` は行全体 PRESERVED_ONLY=中立フォールバック〔実測: EditStoreTest/RecipeToneCurveTest〕）。旧 payload（rgb のみ）読み込み互換テスト緑。
3. **EditStore**: `commitCurve` は Curve 全量スナップショット + `editedChannel`。undo/redo は全量復元（undo 後も `previous ?? last.curveChannel` の選択保持）。
4. **editor UI**: `CurveChannel`(マスター/R/G/B) chips 行（`editor_curve_channel_*` 4 key 新規）・`editor_tab_hint_curve`/`editor_curve_channels_hint` 撤去（実装済み化）・title「カーブ（master）」→「カーブ」・履歴書式 `%1$s: %2$sカーブ %3$d点`（%2$s は channel ラベル・master=「マスター」で**旧文言「マスターカーブ」と出力一致**）。文言台帳は 08 §1/§5。

**全自動検証**: `./gradlew test` exit 0 / `:feature:{editor,export,library}:lintDebug` error 0 / `:app:assembleRelease` 緑 / `tools/ui_strings_check.py` 違反 0（例外 29 行）。

**golden ΔE（実機 TB710FU `HA2EPG4J`・API 36・4/4 OK 21.15s・2048×1365 合成ソース q90・JPEG sRGB IEC61966-2.1・pxCount 2,795,520）**:

- 既存 3 ケース基線（決定論維持の実証）: non-neutral 0.5077/1.2212・downsampled 0.5255/1.1926・neutral 恒等 0.09996
- **追加 D1 `per-channel-curve(r-lift,g-empty,b-dip)-non-neutral`: mean 0.5164 / p95 1.1061 < (1.00 / 2.00)**・ΔE2000 参考 0.3174/0.7408・レシピ r=3 点/g=空/b=2 点

**実機機能検証（全て実測: screencap 画素値 + uiautomator + run-as DB dump）**:

- 取り込み: SAF（DocumentsUI）で `offline_probe_a_rev0.jpg` 1600×1200 → editor r0
- **r0 中立プレビューは probe 原図と 3×3 グリッド均値 diff 0.0（ビット一致）**。先に観察した「カーブタブのプレビュー色ずれ」は**誤り** — probe 原図自体が「左上: 暗・右上: マゼンタ・左下: 緑」の対角グラデ（旧メモの sips 800px 縮小表示が誤認の発生源）。追跡不要
- r1: R chip → canvas tap 3 点(commit) → プレビュー R 127→195・G/B 不変 = **チャンネル分離の実測**
- r2 undo → 中立へビット復帰 / r3 redo → r1 とビット一致（履歴 3 件）
- r4: master chip → 点追加 → 全チャンネル上振れ 246/223/230（master+R の積み上げ整合）
- r5: R chip → 「中立へ戻す」 → **R のみ未編集化・master 保全 233/223/230**
- force-stop → 再起動 → r5・履歴 5 件・**プレビュー ビット一致で復元**（233/223/230 等）

**DB payload 実測**（`run-as` + sqlite3・edit_revisions r1〜r5 全量）:

```
r1 apply {"ch":"r","rgb":[中立 2 点],"r":[[0.0,0.0],[0.4992,0.7681],[1.0,1.0]]}
r2 undo {"ch":"r","rgb":[中立]}            ← 編集チャンネル r を除いた全量戻し
r3 redo {"ch":"r","rgb":[中立],"r":[3 点]}  ← r 再付与
r4 apply {"ch" 省略,"rgb":[0.4605,0.8941] を含む 3 点,"r":[3 点保持]}  ← master 編集
r5 apply {"ch":"r","rgb":[master 3 点のみ]}  ← R のみリセット
```

payload v1 契約どおり・`edit_documents.currentRevision=5`。

**残置/未確認**: カーブダブルタップ点削除はコード実装のみで**実機未観測**（adb 短間隔 tap で未再現）→ 28b の実機作業で併検。48MP proxy 性能記録は 28c（D2・NFR-04 実測）と fixture 待ちに含める。

### 12.2 PR-28b 実施結果（✅ 2026-10-26・28c と 1 コミットに統合〔push なし・⑧〕）

**変更（モデル/レンダー/VM/UI/文言）**:

- `EditNodeSpecs`: 角度ホワイトリスト（`ALLOWED_ANGLE_DEG`/`isAllowedAngle`）を撤去し、
  `canonicalAngleDeg`（有限のみ・[0,360)・±1e-12 は 0）へ置換。`fineAngleDeg` は
  「90°基準からの ±45°」を返す純関数（境界 45/135/225/315 は +45 側=基準を下侧に取る）。
  `normalizeGeometry` は任意の有限角度を受け付け、crop は span>0・[0,1] クランプを維持。
- `RecipeGeometry`: 任意角の逆写像（CW 回転の解析式・双一次補間・外周 `0xFF000000`）+
  bbox 拡張 `outputSize`（ceil）。90° 倍数は従来のピクセル再索引を厳密経路として維持
  （補間誤差ゼロ）。適用順は不変「回転→反転→クロップ」。
- `EditorViewModel`: `onGeometryRotate` は加算 ±90、`onGeometryFineAngle`（基準維持）、
  `onGeometryAngleReset`、`onGeometryCropNudge`（span 保持・クランプ）、
  `onGeometryCropScale`（中心固定・CROP_MIN_SPAN..1）。履歴表示は `formatAngleDeg`。
- `EditorScreen`: GEOMETRY タブに微調整スライダー（−45..45）+ 数値入力ダイアログ + 0° 戻し、
  クロップ移動 4 矢印 + 縮小/拡大。プレビュー canvas は出力 bbox を反映。
- 文言: `editor_tab_hint_geometry`・`editor_crop_unimplemented` を撤去（2 key）、
  角度/移動/サイズ 9 key を追加（08 台帳 §1/§5）。payload 形状は v1 のまま
  （`angleDeg` の値域のみ連続化・旧行はそのまま読める）。

**JVM 固定値**: 任意角 30° の出力寸法 4×2→5×4 と画素配列の完全固定・周期性
（30°+360°=同一）・90° 厳密経路一致・外周黒・適用順・`applyIfNeeded` 恒等。
角度正準化（3.5→3.5・−90→270・360→0）・fineAngle（46→−44・315→+45）・
crop クランプ/span 拒否・NaN 拒否を追加。

**実機検証（TB710FU・API 36・`HA2EPG4J`・screencap/ uiautomator / run-as DB）**:

| 検証 | 結果 |
|---|---|
| golden ΔE 5/5 | 既存 3 基線**ビット一致**（0.507666/1.221216・0.525469/1.192629・0.099962/0.823092）+ D1 0.516351/1.106067 + **D4（12.5°+crop 0.1/0.1/0.9/0.9）mean 0.276837 / p95 1.218100 / p99 2.238108**（2,607,120 px・2048×1365 合成 q90）< しきい値（mean 1.00 / p95 2.00） |
| 自由角度 UI | 45° 適用（黒縁回転をスクショ確認・r6）→ 0°（r7）→ 90°（r8）→ 微調整 +9.545° で表示 99.5°（r9）。角度は DB で [0,360) 正準 |
| 自由クロップ UI | 99.5° 状態で 1:1 → 0/9/100/91%（x0=0 のため ← はクランプ）→ 0° 復帰後 1:1 13/0/88/100%（r14）→ ←2 回 9/0/84/100%（r16）→ 縮小 12/5/80/95%（r17）→ undo r18（9/0/84/100%）→ redo r19（12/5/80/95%） |
| undo/redo/再起動 | force-stop→再起動で r19・履歴 19 件・同クロップを復元。DB 19 行すべて payload 形状正常・r1..r5 は事前スナップショットと完全一致・integrity ok・FATAL 0 |
| kill 注入 | **30/30 PASS**（終端 `edit_revisions=19` / `edit_documents=1` / `assets=1`・db pull OK・新規 FATAL 0） |

**未確認/残置**: カーブのダブルタップ点削除は実機未観察（コードのみ・adb 短間隔 tap で未再現）。
48MP proxy の性能/画質記録は fixture 待ち（28c の 12MP 降格閾値は preview 実測で校正し、
48MP 実測は未測定のまま明記）。

---

### 12.3 PR-28c 実施結果（✅ 2026-10-26・28b と 1 コミットに統合〔push なし・⑧〕）

- 統合理由: 28b の実機検証中に 28c の実装を進めたため、28b 単独で緑の履歴を作れない
  （共有ファイルの hunk 分離は検証不能な中間状態を生む）。両 PR の受入証跡は上表/12.2 のとおり
  別々に取得済み。

**変更（モデル/レンダー/UI/文言）**:

- `AdjustmentId`: `DETAIL_SHARPEN` / `DETAIL_LUMA_NOISE`（0..100 → レシピ 0..1）を追加。
  `EditEventKind.DETAIL` を RESERVED_M2 → IMPLEMENTED_M1・payload v1（スライダー共通形式）へ。
  chromaNoise はモデル互換のため残し、未適用を警告。
- `RecipeDetailApplier`（新規・core:render）: 輝度平面のみの unsharp（3x3 ガウス）+ 輝度 NR。
  `L' = L + (sharpen×2.0 − lumaNoise×0.8)·(L − blur)`、RGB は `L'/L` 倍で色差保持・α 不変。
  適用位置は develop 後・Bitmap 生成/resize 前（preview/export 同一関数・同一順序）。
- **自動降格**: `width×height > 12,000,000` で 4 近傍ラプラシアンの軽量経路へ決定論的に切替
  （時間計測に依存しない=同一寸法で preview=export が崩れない）。warning
  `RecipeDetailApplier.DEGRADED_WARNING` を render warnings / export `issueNotes` に記録し、
  EditorScreen が一致時に「処理を軽くしたため自動降格しました」を表示（ADR-014 の初結線・限定）。
- メモリ: 輝度平面は全画素保持せず 256 行+上下 1 行ハローの帯処理（帯境界は前帯最終行の
  元輝度を carry して段差を防止・JVM テストで境界一致を固定）。
- `EditorScreen`: ディテールタブ実装（2 スライダー+説明）。文言は `editor_tab_hint_detail`
  撤去・`editor_detail_help`/`_degraded_notice`/`editor_adjust_detail_*` 追加（08 台帳 §1/§5）。
- **副次修正**: 履歴 20 件超でリストが潰れるとの実機観測に対し、履歴リストを
  `heightIn(max=168.dp)`+内部スクロール化（外側パネルのスクロールで全件到達可能）。
  履歴は「外側パネルを下へスクロール→履歴を表示」で開く（8px はパネル viewport による
  クリップであり、リスト自体の高さではなかった）。

**JVM 固定値**: 中立 no-op・エッジ両側のコントラスト増加（暗側減/明側増）・NR で分散低下・
色比保存/α 不変・同一入力の決定論・帯境界（y=255/256/511/512/590 が内部行と一致）・
閾値判定（2048×1365=通常 / 4000×3000=通常 / 4001×3000=軽量 / 8000×6000=軽量）・
sharpen と NR の相殺・非有限値の無視。

**実機検証（TB710FU・API 36）**:

| 検証 | 結果 |
|---|---|
| golden ΔE 6/6（32.5s） | 既存 3 基線**ビット一致** + D1 0.516351/1.106067 + D4 0.276837/1.218100 + **D2（sharpen0.5, lumaNR0.25）mean 0.116023 / p95 0.830512 / p99 1.4773（2,795,520px・preview=export 1836×1420）** < しきい値 |
| UI（r20 起点） | シャープ +47（r21）→ 輝度NR +33（r22）→ undo（r23: NR 0）→ redo（r24: +47/+33）→ force-stop 再起動で r24・履歴 24 件・同値を復元 |
| DB payload | `eventType=detail`・`{"path":"develop.detail.sharpen"|"develop.detail.lumaNoise","value","op":"apply"}`・schema v1・currentRevision=48（計測ドラッグ 24 回を含む） |
| NFR-04 | **p50 8 / p90 14 / p95 19 / p99 25 ms（691 frames・benchmark buildType）** — しきい値 100ms に対し余裕。通常経路（2.79MP）で自動降格は発動しない |
| kill 注入 | **30/30 PASS**・DB 48 revisions / 1 document / 2 snapshots / assets 1・WAL 424,392 B・再起動後 r48/+13/+33 を復元・FATAL 0 |

**未測定/残置**: 12MP 超（48MP 級）の軽量経路は実画像 fixture 待ちで未測定。UI の降格文言は
preview が閾値を超える場合のみ表示される（現行 preview は 1600px 級のため通常は非表示・
export 側は issueNotes に記録）。UI 警告の全面結線は ADR-014 の既知制限どおり未実施。

---

## 13. PR-29: D3 マスク（M3・schema/演算/UI 分割）

- **既存計画継承**: 06 A3 判断待ち④。`02` §5.2 D3。
- **重要設計変更（[計画]）**: **Room schema 変更を伴わない**。mask 型（linear/radial/brush・feather・値域）を **payload JSON の追加フィールド** として表現する（マスクは recipe revision 履歴に含むべき物で rev の不変性に合致。「schema v2+migration」は今後の本格需要時まで見送る・Room `exportSchema=true` の有効化+schema JSON 固定寄託は本 PR で先行整備）。
- **分段**:
  1. **29a（payload+JVM）**: mask 構造型・serialization・同一 LUT を mask 重みで blending する純関数（JVM 実行で検証可能・golden 固定値）。
  2. **29b（core）**: mask bitmap cache（preview 尺・サイズ変更時の再構築）・`RecipeToneCurve` 系を mask blend に交える新段（[計画] GPU は stage-2 保留・CPU 版と性能実測明記）。48MP での計算上限は tile+降格連携（PR-26 の scheduler 共通）。
  3. **29c（UI）**: overlay エディタ（ハンドル操作・feather・マスク可視化レイヤ）・editor 画面遷移の改訂。
- **テスト**: JVM（29a 固定値・serialization 互換）・androidTest（29b: preview=export ΔE・29c: Undo/Redo+DB 復元）・golden ΔE 追加ケース。
- **受入条件**: mask 編集の Undo/Redo/DB 復元が kill injection 100 回で崩れない・preview=export 同一検証緑・perf 記録。
- **リスク**: CPU での mask blending は重く、NFR-04 と両立する設計を要る（48MP ではなく preview 尺での計算分割）。失敗時契約: mask 計算負荷からの防御として「リージョン数上限+重み上限（値は実測後に台帳化）」を明示。
- **工数**: XL（3分割推奨）/ **依存**: PR-28（補正型・per-channel 共通の LUT 拡張を先に）。

## 14. PR-30: G2 availability・G3 inset・F3 adaptive

- **既存計画継承**: 06 旧 PR-11/12/13 の再編成。
- **設計要点（[計画]）**:
  1. **G2**: URI 失効検知（`ContentResolver.openInputStream` 失敗等）→ availability=LOST・library 表示/書き出し再接続の喚起（`02` §5）。失効検出は全 asset の poll ではなく表示時/書き出し時に限る（バッテリー配慮）。
  2. **G3**: MainActivity 系全体の Scaffold/Insets 統一（EdgeToEdge 系整合）。
  3. **F3**: WindowSizeClass+リスト詳細 2ペイン（material3.adaptive/N3）。**Android 17（target 37 から 600dp+ で fixed orientation opt-out 廃止 [文書] 2026-09）** に先立つ適応が受入条件の必須部とする。
- **テスト**: 大型/折りたたみ/回転の UI 検証+全回帰+availability 失効の実機確認（fixture: ローカルファイル削除で LOST 観察）。
- **受入条件**: 上記+targetSdk 37 採用の準備状況台帳（判断待ち⑦）。
- **工数**: M / **依存**: なし（Wave 3 末尾で必須化）。
- **実装結果（2026-10-26・コード完了・実機検証済〔下記〕）**:
  1. **G2**: 表示・再表示時に `availability == ok` の asset だけを IO ディスパッチャで
     `probeAvailability`（= `openInputStream` の成否）に掛け、失効/切断のみ DB へ書き戻す
     `LibraryViewModel.verifyVisibleAvailability()` を追加（単一ジョブ + 下限間隔 3s・常時ポーリングなし）。
     対象決定と遷移判断は純粋関数 `AvailabilitySweep` に切り出し JVM テスト 8 件で固定。
     既存の「可用性確認」ボタン（全件走査・回復検出）と再接続導線（SAF 再選択 → 指紋照合 →
     `updateSourceUri`）は温存し、書き出し失敗理由の `URI_REVOKED` を日本語文言へ写像した。
  2. **G3**: `MainActivity` の一括 `systemBarsPadding()` を削除し `enableEdgeToEdge()` を追加、
     Manifest に `windowSoftInputMode="adjustResize"` を設定。各 feature 画面のルートは
     `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)` で inset を自己管理する
     （safeDrawing は IME を含むため `imePadding` 併用の二重掛けをしない）。
  3. **F3**: `BoxWithConstraints` の幅 840dp で分岐（WindowSizeClass の依存追加はオフライン
     解決リスクを避けて見送り・`material3-window-size-class` 未追加）。ライブラリは
     「一覧 + 選択中アセットの詳細/プレビュー」、編集は「プレビュー + ツールパネル横並び」。
     狭幅は従来 1 ペイン。Navigation 3 / `ListDetailPaneScaffold` は使わない。グリッドは既存の
     `GridCells.Adaptive(112.dp)` を維持（広幅で列数が増える）。
  4. **実機検証（TB710FU/API36・2026-10-26/27）**: landscape 3200×2000（density override 340・幅 1505dp）
     でライブラリ 2 ペイン（一覧+360dp 詳細: 選択→「開いて編集」）・編集 2 ペイン（プレビュー+
     360dp ツールパネル）を目視。タブ 6 個が 360dp に収まらず「ディテール」が縦折返し・
     「局部補正」が画面外になったため、**タブ行を横スクロール化**して全タブ到達を確認（Lead 修正）。
     2 ペインの選択セルに枠線を追加（Lead 修正）。狭幅（`wm size 800x1280` → 602dp）は 1 ペインで
     従来動作。portrait 2000×3200（941dp）でも 2 ペイン・inset 二重掛けなし・回転で選択タブ保持。
     検索欄フォーカスで IME 表示（mInputShown=true）時も検索欄・一覧が隠れないことを確認。
     書き出し画面の Scaffold inset も目視（ステータスバー重なりなし）。
     **未検証**: 折りたたみ/マルチウィンドウ、数値入力ダイアログの IME（既知の未対処）、
     URI 失効の実機観察（原本削除は破壊的なため実施せず・JVM 8 件とコードで担保）。
  5. **targetSdk 37 準備状況**: 600dp+ で fixed orientation opt-out が廃止されても、
     本 PR で幅基準のレイアウト分岐（向き非依存）と inset 自己管理へ移行したため、横向き固定を
     前提とした画面構成は残っていない。残作業は (a) targetSdk/compileSdk 37 への更新と
     依存（Coil/Compose）の同時更新、(b) 折りたたみ・マルチウィンドウ・大画面の実機回帰
     （本 PR はコードのみ・実機未検証）、(c) Play 要件時期（2027 半ば見込み・TBC）の再確認で
     あり、判断待ち⑦は引き続き「PR-30 完了後に再評価」のまま据え置く（targetSdk 値は未変更）。

---

## 15. 依存図・着手順（推奨）

```
PR-17 (sizes) ─┐
PR-18 (golden) ├─ 並行可（S群・相互独立）
PR-19 (name)  ─┤   PR-20 (offline) ┐
PR-21 (AGP9) ──┴─→ PR-22 (HDR spike → ADR-026) → PR-23 (A3+b2 EXIF) → PR-27 (strings)
              └─→ PR-24 (RAW spike → ADR-027) → PR-25 (RAW pipe) → PR-26 (NFR-10+thumbnail bench)
                                                                                        └→ PR-28 (D1/D4/D2) → PR-29 (D3 マスク)
PR-30 (adaptive) — targetSdk 37 採用（判断待ち⑦）の直前に必須・それ以前にも着手可
```
- **臨界経路**: PR-21 → PR-22/24 → PR-25/26 → PR-28/29（NDK と CPU 演算量の両判断がここに集中）。
- **fixture の早期調達**（判断待ち⑥）: DNG サンプル・UltraHDR 生成物は PR-22/24 の前に片付ける。

## 16. 受入マトリクス（NFR × PR）

| NFR | 対応 PR | 達成形態 |
|---|---|---|
| NFR-04 (editing FPS) | PR-28/29 | 実測（p95・条件付） |
| NFR-06 | 継続運用 | 500/500 [実測] 済・継続 kill 併用 |
| NFR-07 | PR-18/25/28/29 | しきい値固定・追加ケース |
| NFR-08 | PR-17 | bundletool 端末実寸+min/max |
| NFR-09 | 全 PR | 毎回原本 MD5 再確認 |
| NFR-10 | PR-26 | 実装部分達成・4GB 端末での真の実測は端末調達後（判断待ち⑥） |
| NFR-11 | PR-20 | 実測 |

## 17. ADR 予定の内容アウトライン

- **ADR-026（HDR stage-1 policy）**: 案 A/B の比較・spike S-03 の実測表・compress 保全の可否・stage-2 の再検討条件（ISO 21496-1 優先 decode 併記・Android 17 の HDR brightness 調整機能との整合）。
- **ADR-027（RAW pipeline）**: α/β/γ 比較・platform DNG の端末実測結果・LibRaw 0.22.2 の 16KB/サイズ・fixture ライセンス記録・48MP メモリ（C-04）実測値。
- 併せて記録すべき追加判断: PR-29 の **masks= payload JSON**（Room migration を伴わない）、PR-23 の **EXIF whitelist**（pass 対象の明確化）は ADR に軽量記録（026/027 内の付記で足りる見込み）。

## 18. Wave-3 完了チェックリスト（定義）

各 PR が以下を満たした時点で Wave-3 完了とする:
1. 06 §3.2 の PR 表が実測ベースで確定・`07` の本書各節に結果を反映（未達は理由と期限明記）
2. `benchmark/nfr_catalog.md` 対応 NFR 行の更新（測定条件必添・未測定は未測定のまま維持）
3. R8 リリース版の実機回帰（Wave-3 の対象 PR 数に応じ 2〜3 回・2026-10-19 手順踏襲）
4. **debug 環境への復帰・再取り込み**の維持（2026-10-19 手順を踏襲）
5. Push/backup（判断待ち⑧）の対応

## 19. 本書内の未検証・外部依存リスト（誠実性管理用）

| 項目 | 状態 | 確定経路 |
|---|---|---|
| Java `ImageDecoder` の DNG 実機復号 | 未検証（[文書] のみ） | PR-24 spike |
| `Bitmap.compress` による gainmap 保全の可否 | **✅ 実測済（2026-10-25・PR-22 spike）**: 全画素 1.27 乗算+gainmap 未修正の compress でマーカー XMP/hdrgm/ISO 21496/MPF 全存続・再 decode で諸元完全保全（TB710FU/API 36）。→ ADR-026 stage-1 条件付き pass-through の根拠 | PR-22 spike【更新 2026-10-26】 |
| TB710FU の HDR 撮影（RAW/PRO）対応 | 未確認 | PR-22/24 の fixture 調達時に実測 |
| bundletool jar の要取得（ネットワーク） | 最新版番号は取得時に台帳化 | PR-17 |
| AGP 9.4 への Kotlin/Compose バージョン随伴 | **解決（2026-10-25・PR-21）**: built-in Kotlin は AGP 9.4.0 が Kotlin 2.2.10 を同梱し独立変更不可のため、KGP 由来プラグイン（jvm/serialization/compose）を 2.2.10 に統一。kotlin-android は適用禁止化・jvmTarget は compileOptions 既定継承で明示不要を公式確認済み（[文書] 2026-09-15 更新版） | 実装済み（gradle/libs.versions.toml コメントに根拠記載）。再検討は Kotlin 3.x 正本化（built-in Kotlin の移行先）時 |
| Photo Picker クラウド URI の表示名挙動 | **部分観察（2026-10-25）**: ローカル経路は観察済み（com.google.android.photopicker・オフライン・数字 ID のみ → プレースホルダ `img-<hash8>.png` 正推論）。**クラウド経路は TB710FU（このアカウント）では Picker 上にクラウド項目が表示されず観察未達** — ネットワーク復帰後の再観察も同じ。挙動は端末/アカウント側の実装依存のため、本項は未解決のまま残置 | 完了済み PR は 06 §3.2 行に達成と明記していない（観察達成の一部のみ）。以後の端末/アカウント条件が揃った時に再観察（PR-19 の残置項目） |
| push 対応（リモート不在 [実測]） | リモート不在 [実測] | 判断待ち⑧ |
| PR-30 の実機検証（2ペイン/回転/折りたたみ/IME/URI失効） | **コード完了・実機未検証（2026-10-26）**: G2/G3/F3 を実装し JVM テスト（availability 8 件）・lint・assembleDebug は緑。TB710FU（landscape 3200×2000）での 2 ペイン目視、IME 表示時の検索欄/数値ダイアログ、ファイル削除による失効検出は Lead の実機確認待ち | 07 §14「実装結果」4 項の実機確認項目 |

---
変更管理: 本書の節ごとの更新は PR 単位で行う。結果の正本は `benchmark/nfr_catalog.md` と `06`/`03`（ADR）に必ず反映し、本書はその詳細の手順書として使う。
