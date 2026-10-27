# 11 Sony ARW 検証とアプリアイコン（2026-10-27・コミット ）

## 1. 入力

| ファイル | 内容 |
|---|---|
| `ARW (sony)/DSC00001.ARW` | 24,969,216 B / `file`: TIFF little-endian, SONY ILCE-6000, compression=JPEG(old), 2026:10:20 07:41 |
| `ARW (sony)/DSC00003.ARW` | 25,100,288 B / 同 ILCE-6000, 07:49 |
| `アイコン.ico` | 256×256 RGBA 1 フレーム（黒の角丸四角 + 白の写真フレーム/スライダー） |

## 2. 検証環境

- 実機 TB710FU は USB 切断で `adb devices` に現れず、ユーザー判断により **エミュレータ**で検証した。
- AVD `Medium_Phone_API_36.1`（Android 16 / Play Store arm64）を `-wipe-data -memory 4096 -cores 4 -gpu host` で起動。
  初回は boot/setup と system_server が不安定だったが、wipe 後の cold boot で SAF 操作可能な状態に到達した。
- エミュレータは随所で SystemUI/Launcher の ANR が出るため、画面操作は最小手順に限定した。
- 実機（TB710FU）での再確認は未実施。**エミュレータ結果であり、端末差（provider/MIME/ANR）は未評価**。

## 3. 実動作の結果（2026-10-27）

| # | 操作 | 結果 |
|---|---|---|
| 1 | ライブラリ →「ファイル（SAF）」（修正前の `image/*`）→ Downloads/ARW | フォルダは見えるが ARW が一覧に出ず「Can't load content at the moment」。**RAW を選択できない不具合を再現** |
| 2 | 修正後（`*/*` + RAW 拡張子候補）→ 同じ SAF | Recent に `DSC00001.ARW` / `DSC00003.ARW` が **ARW の埋め込みプレビュー付き**で表示され、選択可能 |
| 3 | `DSC00001.ARW` を選択 | ライブラリに `取り込み失敗: image:19 → UNSUPPORTED_CODEC 非対応候補: Sony ARW（RAW 現像は未対応）: format=TIFF_FAMILY TIFF家族: DNG/カメラRAW。デコーダー検証未達のため非対応候補（機種/圧縮方式別に確定予定）` と `取り込み完了: 0 / 1 件` を表示 |
| 4 | DB 確認（`run-as` pull） | `assets=0` / `edit_documents=0`。**非対応ファイルは登録されない**（部分登録なし） |
| 5 | アプリプロセス/クラッシュ | プロセス生存。アプリ由来 FATAL なし（記録された crash は emulator の vendor radio service） |
| 6 | 2 回目（修正後メッセージ）| SystemUI ANR のため再操作を中断。`image:19` の代わりに実ファイル名を出す修正は unit テストで固定（§4-3） |
| 7 | アイコン | 起動スプラッシュに新しいアイコン（白タイル+黒グリフ）が表示された（`shots/arw_splash_icon.png`） |

証跡: [ARW 拒否メッセージ](shots/arw_rejected.png) / [新アイコンのスプラッシュ](shots/arw_splash_icon.png)

**重要な観察**: DocumentsUI は ARW のサムネイル（埋め込み JPEG プレビュー）を表示できるが、これは
`ImageDecoder` が RAW を現像できる証拠ではない。本アプリは sniff 段階で非対応候補として拒否しており、
ARW の画素をデコードする経路は実行していない。

## 4. 検出した問題と修正

### P1: SAF の `image/*` フィルタで ARW が列挙されず、選択もできない

- 症状: `ACTION_OPEN_DOCUMENT` の MIME が `image/*` のため、provider が ARW を
  `application/octet-stream` として返すと一覧に出ない（エミュレータで再現）。
- 修正:
  - `LibraryScreen` の SAF ファイル/再関連付けピッカーを `SAF_PICK_MIME_TYPES = arrayOf("*/*")` に変更
    （非画像は取り込み時に拒否される。ユーザーが RAW を選んで理由を確認できる）。
  - フォルダー取り込みは `ImportSniffer.isImportCandidate(mime, fileName)` を追加し、
    `image/*` に加えて既知の RAW 拡張子（ARW/CR2/CR3/NEF/RAF/RW2/ORF/PEF/SRW/DNG）を列挙対象にした。
    列挙は拡張子補助、対応可否は従来どおり sniff/register が決める。
- 検証: エミュレータで ARW が Recent に表示され選択可能になったこと（§3-2）。JVM テスト
  `isImportCandidate(octet-stream, DSC00001.ARW)=true` / PDF=false。

### P2: 失敗メッセージのファイル名が `image:19`（MediaStore ID）になる

- 症状: `library_msg_import_one_failed` の `%1$s` は URI の `lastPathSegment` のため、
  DocumentProvider が `content://media/.../image:19` を返すと内部 ID が表示される。
- 修正: `unsupportedImportDetail` に `probe.displayName` を含め、
  `非対応候補: Sony ARW（RAW 現像は未対応）: DSC00001.ARW format=…` と実ファイル名を明示。
- 検証: `ImportSnifferTest` で `DSC00001.ARW` を含むことを assert。
  **実機/エミュレータでの最終文言確認は SystemUI ANR で未完了**（次回 1 操作）。

### P2: ARW 自体を開けない（仕様上の制約）

- Sony ARW は非 DNG のカメラ RAW。本アプリは RAW 現像未実装（`01` §6 / `07` PR-24/25）で、
  `ImageDecoder` の ARW 対応も未検証。非 DNG は LibRaw（0.22.2・LGPL/CDDL）統合が必要。
- 今回の 2 ファイルは **PR-24 spike の入力候補**になる。実装には ADR-027 と PR-25 が必要で、
  本ターンでは行っていない。現状は「非対応候補」として明示拒否し、DB へ登録しない（無通知失敗なし）。

### P1（アイコン）: アプリアイコン未設定

- 修正: `アイコン.ico` から legacy/round（48/72/96/144/192）・adaptive v26・monochrome v33・
  前景 432×432（白グリフ/透明・セーフゾーン 60%）を生成し、Manifest に
  `android:icon` / `android:roundIcon` を設定。背景色 `#000000`。
- 検証: `:app:assembleDebug` 緑、`aapt2 dump badging` が `mipmap-anydpi-v33/ic_launcher.xml` を返す、
  APK 抽出画像の目視、起動スプラッシュでの表示。**ランチャーの適応マスク/テーマアイコンは未確認**。

## 5. 修正プラン（残作業）

1. **ARW 最終文言の再確認（5 分）**: 実機または安定したエミュレータで 1 件選択し、
   `非対応候補: Sony ARW（RAW 現像は未対応）: DSC00001.ARW …` / `0 / 1 件` / assets 0 を確認。
2. **フォルダー取り込みの実確認（5 分）**: 「フォルダー（SAF）」で `Downloads/ARW` を選び、
   2 件が列挙され、それぞれ非対応拒否されること（`isImportCandidate` の実機経路）。
3. **RAW 対応可否の spike（PR-24）**: `/sdcard/Download/ARW/DSC00001.ARW` を
   `ImageDecoder`/`BitmapFactory` でデコードできるか instrumented test で確認。
   復号できる場合でも「埋め込み JPEG プレビュー」か「RAW 現像」かを切り分け、
   後者でなければ LibRaw 統合（ADR-027）へ。24MP/25MB のメモリ・色再現・圧縮 RAW を実測。
4. **アイコンの実機確認**: ランチャー通常/丸/テーマアイコン、セーフゾーン（必要なら前景 56% へ）。
5. ドキュメント: PR-24 の fixture 欄に本 ARW 2 件を追記（07/06 更新済み）。

## 6. 検証コマンド

```
./gradlew :core:storage:test :app:assembleDebug
# エミュレータ（実機が使えない場合）
emulator -avd Medium_Phone_API_36.1 -wipe-data -memory 4096 -cores 4 -gpu host
adb push "ARW (sony)/DSC00001.ARW" /sdcard/Download/ARW/
# ライブラリ → ファイル（SAF）→ ARW を選択 → メッセージ/DB 確認
```
