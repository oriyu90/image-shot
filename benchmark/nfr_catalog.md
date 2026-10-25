# NFR 計測カタログ（P2: 性能・画質計測基盤）

正本は `01_製品仕様書.md` §9「非機能要件（NFR・測定前の受入目標）」。
本表は計測の計画（Plan）と現状を機械/人間が突き合わせるための作業台帳であり、仕様の上書きではない。
「現在の実測値」の列は **検証して実行した値のみ** 記入する（AGENTS.md 禁止事項: 未検証数値の報告禁止）。

基盤が備える計測手段：
- Macrobenchmark（`:benchmark` モジュール、androidx.benchmark 1.4.1）— connectedAndroidTest で実行。
- 強制再起動ループスクリプト（`benchmark/run_kill_injection.sh`）— adb ベース、UI 自動化なし。

| NFR id | 内容（指標） | 目標値（仮の合格目標） | 測定方法（Plan） | 現在の実測値 | 検証日時・条件 | 判定 |
|---|---|---|---|---|---|---|
| NFR-01 | クラッシュフリーセッション率 | 99.95%以上 | 公開後: Google Play Console / Android vitals（手動確認）。公開前は未測定不能 | 未測定（基盤のみ整備） | ― | 未測定 |
| NFR-02 | ANR率 | 0.1%未満（目標） | Google Play Console / Android vitals（手動確認）。補助: Macrobenchmark 実行時の ANR 検出自動失敗 | 未測定（基盤のみ整備） | ― | 未測定 |
| NFR-03 | コールド起動 P95 | 3.0秒以内 | `:benchmark` StartupBenchmark.coldStartup (StartupTimingMetric, COLD, 5反復). 条件: 6–8GB RAM 代表端末、ローカル写真一覧 | **TTID median 233.8ms / min 225.5 / max 260.7 / σ14.6 (5反復)** — P95相当は未抽出(個別trace必要) | 2026-10-18 TB710FU(API36) / :app benchmark buildType(非debuggable·profileable·R8無効) / ライブラリ空 / COLD | ✓ 仮目標内(233.8ms ≪ 3000ms) |
| NFR-04 | 調整操作への反応 P95 | 100ms以内 | 代替: `dumpsys gfxinfo` 経路 — `benchmark/run_frametiming_adb.sh`。定本（FrameTimingMetric）は本端末で 0 フレーム採取となり不使用（実機実証・2026-10-18、`@Ignore` 記録済み、再検討条件は androidx.benchmark 新SDK での直FIX） | **frame time p50 9ms / p90 16ms / p95 21ms / p99 32ms**（スライダードラッグ中 504 frames） | 2026-10-18 TB710FU / benchmark buildType / ドラッグ 8 往復（各往復=2 swipe） / gfxinfo reset 後 | ✓ 仮目標内（p95 21ms ≪ 100ms） |
| NFR-05 | 操作中フレーム | 標準端末 30fps 以上 | 代替: gfxinfo 方式（NFR-04 と同経路）。本来条件（タイルプレビュー・色補正5段以上）は M2 未達のため簡易条件で記録 | **janky frames 16.67%（frame deadline missed 基準）/ legacy 29.76% / total 504 frames / GPU p50 3ms** | 同 NFR-04 | △ 継続測定（M2 で Editor ベンチ条件を整える） |
| NFR-06 | 編集レシピの耐障害性（最後の確定操作の損失ゼロ） | プロセス強制終了500回で損失 0 | kill injection ループ（force-stop → am start → 起動確認）＋ DB pull 行数差分（run-as・-wal 対で取得）。**500 反復を 2026-10-18 の 100 反復と 2026-10-19 の 400 反復（277+123・macOS awk systime 修正版スクリプト）で完走** | **累計 500/500 PASS**（強制終了→再起動判定全成功・FAIL 0）。ループ終端の DB pull（assets/edit_documents/edit_revisions/edit_snapshots/export_jobs）= **1/1/1/0/1**・レシピ復元（改訂 r1・履歴1件表示）確認・実施期間中の新規 FATAL 0（logcat 全件照合。なお DB 行数は終端時点の取得であり開始直前値との逐次差分は取得していない） | 2026-10-18（100回）・2026-10-19（277回＋123回・うち277回はモニターシェル断絶後にログから確認・反復ログは完全）TB710FU / debug ビルド / 1アセット・1レシピ | ✓ 500反復で達成 |
| NFR-09 | 未編集原本の変更 | 0件 | 取り込み/編集/書き出し後の原本 MD5/SHA-256 比較（故障注入含む） | **0件: /Download/test_photo.jpg MD5 4d2a05ff… 変更なし**（書き出し2回後・kill注入100反復後） | 2026-10-18 TB710FU | ✓ |
| NFR-07 | プレビューとフル出力の一致 | 色差・補正誤差を定量基準内 | golden ΔE ハーネス（`:benchmark` androidTest・**CIE ΔE76**・実機）。条件: 合成ソース 2048x1365 JPEG q90（グラデーション+象限アクセント）/ 書き出し JPEG q95 フル解像度 / `SimpleBitmapPhotoRenderer`+`ExportBitmapRenderer` 同一ノード契約 | **① 中立（全既定）: mean 0.10 / p95 0.82 / p99 1.45 / max 11.46。② 非中立（+0.35EV, contrast+0.2, 5600K, vibrance+0.15）: mean 0.51 / p95 1.22 / p99 1.81 / max 14.86。③ 非中立+ダウンサンプルプレビュー（sample=2・1024x683→upscale比較）: mean 0.53 / p95 1.19 / p99 1.69 / max 10.80**（pxCount 2,795,520 = 2048×1365 を3ケース共通） | 2026-10-19 TB710FU(API36) / debug ビルド / 合成ソース・実機デコード ImageDecoder(sRGB ターゲット) 経由 / max 値は孤立画素（JPEG 再圧縮端など）の外れ値で mean/p95 とは別の水準 | ✓ 一次基線確立（契約: 中立 mean<1.0 / 非中立暫定 mean<3.0・p95<6.0 — 暫定しきい値は本表の記載の通り、 golden の正当化は次回以降の測定で再確認） |
| NFR-08 | 標準インストールサイズ | AAB 機種配信後 45MB以下 | `./gradlew :app:bundleRelease` 出力 `.aab` 未展開サイズ + **bundletool get-size 実寸（手法確立・2026-10-25・07 計画書 PR-17）**: `get-device-spec`(TB710FU) → `build-apks`（全寸法 / `--device-spec` / `--mode=universal`）→ `get-size total`。R8: release/benchmark 両型で minify+shrinkResources 有効（ADR-025）。参考: release APK 3,004,509 B / mapping.txt 32.2MB | **AAB 未展開 3,972,133 B（2026-10-25 HEAD 再ビルド・PR-8 込み。旧記録 3,954,663 B は PR-7/PR-8 コミット前の 10/19 19:12 ビルドのため置換）**。**機種配信後の実寸（`get-size total` 出力・公式ドキュメントは転送時圧縮を想定した推定 DL サイズと記載〔文書〕・表記は出力列 MIN/MAX に従う）: TB710FU device-spec（arm64-v8a / density400 / SDK36 / en）= 1,412,427 B／universal = 1,425,367 B／全構成 min–max = 1,411,444–1,412,820 B**（密度 split なし=密度 列空。言語 split: ja 1,418,200 B vs en 1,412,427 B〔default リソース=ja を反映〕）。実機ランタイム確認（R8版）: 取り込み→エディタ描画→UI 書き出し COMPLETE→原本 MD5 不変 `5daed919…`（2026-10-19・PR-7 段階で実施） | 2026-10-25 / AGP 8.13.2 / bundletool 1.18.3（releases/download/1.18.3・jar SHA-256 `a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`） / device-spec=`benchmark/fixtures/device_tb710fu.json`（ramBytes 12,122,214,400 ≈ 11.3 GiB） | ✓ AAB・配信後実寸とも基準を大きく下回る。機内 install-size 実測は未実施（get-size は転送サイズ算出のみ・署名不一致のため既存インストールは維持） |
| NFR-10 | メモリ超過からの復帰 | 低品質プレビューへ降格、UI 操作可能 | 4GB RAM 端末 × 48MP RAW 手動検証（未整備）。※TB710FU device-spec RAM 実効 ≈11.3 GiB（2026-10-25 実測）— 4GB 級実端末は別途必要（07 計画書 判断待ち⑥） | 未測定（基盤のみ整備） | ― | 未測定 |
| NFR-11 | オフライン作業の完全性 | 標準機能が完全動作 | 機内モード + 初回起動後の手動検証（未整備） | 未測定（基盤のみ整備） | ― | 未測定 |

## 実行コマンド（実施時に追加記録する）

コールド起動（NFR-03）:
```sh
./gradlew :benchmark:connectedBenchmarkAndroidTest --console=plain
# 出力: benchmark/build/outputs/connected_android_test_additional_output/
# 実測値をここへ転記する（未検証の値は書かない）
```

フレーム計測（NFR-05, 個別実行）:
```sh
./gradlew :benchmark:connectedBenchmarkAndroidTest --console=plain \
  -Pandroid.testInstrumentationRunnerArguments.class=com.imageshot.benchmark.FrameTimingBenchmark
```

強制再起動 N 回 + DB 整合（NFR-06, UI 自動化無し/プロセス再起動の検証）:
```sh
benchmark/run_kill_injection.sh 400   # 2026-10-19: macOS awk(systime) 対応の固定 sleep 修正版で 400 反復・累計500を実施
```

golden ΔE（NFR-07, `:benchmark` androidTest・MacrobenchmarkRunnerStub・`-e class` 指定）:
```sh
adb install -r benchmark/build/outputs/apk/androidTest/debug/benchmark-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -e class com.imageshot.benchmark.GoldenPreviewExportDeltaETest \
  com.imageshot.benchmark.test/com.imageshot.benchmark.MacrobenchmarkRunnerStub
adb logcat -d | grep GoldenDeltaE
# 成果物: /data/user/0/com.imageshot.benchmark.test/files/golden_dE_report.json
```

AAB 配信後サイズ（NFR-08, bundletool・get-device-spec は端末接続必須）:
```sh
benchmark/run_nfr08_sizes.sh <bundletool.jar>
# 1.18.3 検証済み（URL・SHA-256 はスクリプト先頭コメント）。端末依存部は
# benchmark/fixtures/device_tb710fu.json に保存・コミット済み（2026-10-25）
```

decode/orientation/ICC・Room androidTest（`-e class` 指定・単独実行可）:
```sh
adb shell am instrument -w -e class com.imageshot.core.render.ExifOrientationPipelineTest \
  com.imageshot.core.render.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.imageshot.core.database.EditStoreInstrumentedTest \
  com.imageshot.core.database.test/androidx.test.runner.AndroidJUnitRunner
```
