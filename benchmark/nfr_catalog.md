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
| NFR-04 | 調整操作への反応 P95 | 100ms以内 | 代替: `dumpsys gfxinfo` 経路 — `benchmark/run_frametiming_adb.sh`。定本（FrameTimingMetric）は本端末で 0 フレーム採取となり不使用（実機実証・2026-10-18、`@Ignore` 記録済み、再検討条件は androidx.benchmark 新SDK での直FIX） | **frame time p50 9ms / p90 16ms / p95 21ms / p99 32ms**（スライダードラッグ中 504 frames）。**＋PR-28c（2026-10-26・D2 ディテール）: p50 8ms / p90 14ms / p95 19ms / p99 25ms（691 frames・janky 14.18%〔legacy 21.85%〕・GPU p95 4ms）** | 2026-10-18（基線）・2026-10-26（28c） TB710FU / benchmark buildType（非debuggable・R8 有効）/ 基線=露出スライダー 8 往復・28c=ディテール「シャープ」スライダー 12 往復（24 swipe） / gfxinfo reset 後 / 28c 条件: preview 1600×1200・sharpen+lumaNR 通常経路（2.79MP < 12MP 降格閾値） | ✓ 仮目標内（p95 19–21ms ≪ 100ms）。**48MP 級（>12MP の軽量経路）は fixture 待ちで未測定** |
| NFR-05 | 操作中フレーム | 標準端末 30fps 以上 | 代替: gfxinfo 方式（NFR-04 と同経路）。本来条件（タイルプレビュー・色補正5段以上）は M2 未達のため簡易条件で記録 | **janky frames 16.67%（frame deadline missed 基準）/ legacy 29.76% / total 504 frames / GPU p50 3ms** | 同 NFR-04 | △ 継続測定（M2 で Editor ベンチ条件を整える） |
| NFR-06 | 編集レシピの耐障害性（最後の確定操作の損失ゼロ） | プロセス強制終了500回で損失 0 | kill injection ループ（force-stop → am start → 起動確認）＋ DB pull 行数差分（run-as・-wal 対で取得）。**500 反復を 2026-10-18 の 100 反復と 2026-10-19 の 400 反復（277+123・macOS awk systime 修正版スクリプト）で完走**。**PR-21 追加（2026-10-25）: AGP 9.4.0+built-in Kotlin ビルドでの 50 反復（debug・offline_probe_a 由来 asset 3件の DB 継続状態で実施）** | **累計 500/500 PASS**（強制終了→再起動判定全成功・FAIL 0）。ループ終端の DB pull（assets/edit_documents/edit_revisions/edit_snapshots/export_jobs）= **1/1/1/0/1**・レシピ復元（改訂 r1・履歴1件表示）確認・実施期間中の新規 FATAL 0（logcat 全件照合。なお DB 行数は終端時点の取得であり開始直前値との逐次差分は取得していない）。**＋PR-21 追加分 50/50 PASS・FATAL 0** | 2026-10-18（100回）・2026-10-19（277回＋123回・うち277回はモニターシェル断絶後にログから確認・反復ログは完全）+ 2026-10-25（PR-21・50回/AGP 9.4 ビルド）TB710FU / debug ビルド / 1アセット・1レシピ（500時）→3アセット（PR-21時） | ✓ 500反復で達成・PR-21 ツールチェーン更新後も性質不変 |
| NFR-09 | 未編集原本の変更 | 0件 | 取り込み/編集/書き出し後の原本 MD5/SHA-256 比較（故障注入含む）。**追加（2026-10-25・オフライン経路）**: 機内モード下での同一検証（PR-20 併設） | **0件: /Download/test_photo.jpg MD5 4d2a05ff… 変更なし**（書き出し2回後・kill注入100反復後）。**＋2026-10-25: /Download/offline_probe_a.jpg MD5 cc6d76fc… が機内取り込み→SAF 書き出し→kill 30 反復後も不変**（出力 62,985 B は別ファイル生成） | 2026-10-18 TB710FU（従来）+ 2026-10-25 機内モード（追加） | ✓ |
| NFR-07 | プレビューとフル出力の一致 | 色差・補正誤差を定量基準内 | golden ΔE ハーネス（`:benchmark` androidTest・**CIE ΔE76**・実機）。条件: 合成ソース 2048x1365 JPEG q90（グラデーション+象限アクセント）/ 書き出し JPEG q95 フル解像度 / `SimpleBitmapPhotoRenderer`+`ExportBitmapRenderer` 同一ノード契約。**PR-18（2026-10-25）: 同一 APK による 5 連続実行+実測分布から固定** | **① 中立（全既定）: mean 0.100 / p95 0.823 / p99 1.450 / max 11.461。② 非中立（+0.35EV, contrast+0.2, 5600K, vibrance+0.15）: mean 0.508 / p95 1.221 / p99 1.814 / max 14.862。③ 非中立+ダウンサンプル（sample=2・1024x683→upscale比較）: mean 0.525 / p95 1.193 / p99 1.697 / max 10.798**（pxCount 2,795,520 を3ケース共通）。**2026-10-25 の 5 連続実行は report JSON が全件同一 SHA-1（ビット完全一致・σ=0）で、2026-10-19 初回基線とも同一値 → 完全決定論が確認された**。**固定しきい値（PR-18・GoldenPreviewExportDeltaETest に定数固定済み）: 中立 mean<0.30／非中立・ダウンサンプル mean<1.00・p95<2.00**（実測 mean の約 2–3 倍。端末上の完全決定論を前提に、OS/Skia・コーデック状態変化やソース差し替え時の変化吸収マージンとして設定。逸脱時は機器環境変化の検出信号）。**補助指標 CIEDE2000（2026-10-25・記録専用/assert なし・PR-18）: 中立 mean 0.0617／非中立 0.295／ダウンサンプル 0.313（p95 0.41–0.76・pxCount 同上）— max は ΔE76 と同様に孤立画素外れ値の水準**。**＋PR-21（2026-10-25）: AGP 9.4.0 + built-in Kotlin 2.2.10 + KSP 2.3.12 ビルドでの 1 run も ΔE76 全統計が基線とビット一致（画質経路の無変化をツールチェーン更新に対して実証）** | 2026-10-19（基線）+ 2026-10-25（5+1 実行・AGP8.13）+ 2026-10-25（AGP9.4 ビルド1回） / TB710FU(API36) / debug androidTest APK / 合成ソース・実機デコード ImageDecoder(sRGB ターゲット) 経由 / max 値は孤立画素（JPEG 再圧縮端など）の外れ値で mean/p95 とは別の水準 | ✓ **しきい値固定済み（PR-18・暫定解除）**。全ケース実測値が固定値を十分下回る（実測最大 mean 0.525 < 1.00）。CIEDE2000 は傾向確認のみで ΔE76 を主契約として継続 |
| NFR-08 | 標準インストールサイズ | AAB 機種配信後 45MB以下 | `./gradlew :app:bundleRelease` 出力 `.aab` 未展開サイズ + **bundletool get-size 実寸（手法確立・2026-10-25・07 計画書 PR-17）**: `get-device-spec`(TB710FU) → `build-apks`（全寸法 / `--device-spec` / `--mode=universal`）→ `get-size total`。R8: release/benchmark 両型で minify+shrinkResources 有効（ADR-025）。参考: release APK 3,004,509 B（AGP 8.13.2）→ 2,903,319 B（AGP 9.4.0） / mapping.txt 32.2MB（AGP 8.13.2 時点） | **AGP 9.4.0+built-in Kotlin 2.2.10（2026-10-25・PR-21）: AAB 未展開 4,104,469 B / TB710FU device-spec = 1,402,931 B／universal = 1,416,719 B／全構成 min–max = 1,401,969–1,403,322 B**。〔前回 AGP 8.13.2・2026-10-25 PR-17: AAB 3,972,133 B / device-spec 1,412,427 B / universal 1,425,367 B / 全構成 1,411,444–1,412,820 B — AAB +132,336 B・配信後実寸 −8,6〜9,5 KB の変動。依存同一（差はツールチェーン生成物）・45MB 基準 impact なし。**要因の断定はしない**（記録のみ）〕。実機ランタイム確認（R8版）: 取り込み→エディタ→UI 書き出し COMPLETE→原本 MD5 不変 `5daed919…`（2026-10-19・PR-7）+ **AGP 9.4 版リリース APK スモーク 2026-10-25（取り込み OK→エディタ→書き出し COMPLETE `c7e4314c…`→原本 MD5 `cc6d76fc…` 不変・FATAL 0）** | 2026-10-25 / AGP 8.13.2（PR-17）→ **AGP 9.4.0（PR-21）** / bundletool 1.18.3（releases/download/1.18.3・jar SHA-256 `a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`） / device-spec=`benchmark/fixtures/device_tb710fu.json`（ramBytes 12,122,214,400 ≈ 11.3 GiB・**PR-21 で再生成・バイト同一確認**） | ✓ AAB・配信後実寸とも基準を大きく下回る（新版 4.1MB）。機内 install-size 実測は未実施（get-size は転送サイズ算出のみ・署名不一致のため既存インストールは維持） |
| NFR-10 | メモリ超過からの復帰 | 低品質プレビューへ降格、UI 操作可能 | 4GB RAM 端末 × 48MP RAW 手動検証（未整備）。※TB710FU device-spec RAM 実効 ≈11.3 GiB（2026-10-25 実測）— 4GB 級実端末は別途必要（07 計画書 判断待ち⑥） | 未測定（基盤のみ整備） | ― | 未測定 |
| NFR-11 | オフライン作業の完全性 | 標準機能が完全動作 | 機内モード + 初回起動後の手動検証。**手法確定（2026-10・PR-20）**: `adb shell cmd connectivity airplane-mode enable/disable`（両方向とも実機動作確認）・フローは uiautomator 手順。構造的根拠: app 自身の `AndroidManifest.xml` は uses-permission 宣言無し・merge 後 release manifest の権限 5 件はすべてライブラリ由来（WorkManager 系 4＋androidx 系 1）で **INTERNET なし**（ACCESS_NETWORK_STATE は状態読み取りのみ） | **機内モード下 全フロー実測 緑（2026-10-25）**: SAF 取り込み `offline_probe_a.jpg`（取り込みOK）→ エディタ表示（改訂r0・保存済み）→ SAF 書き出し COMPLETE（ジョブ d4e749f4…・出力 62,985 B 生成）→ 原本 MD5 不変 `cc6d76fc6aa8dd97b16b4f7e6bf2d96e` → **kill 注入 30/30 PASS**（fail 0）・終端 DB assets 3 / edit_documents 1 / edit_revisions 0 / edit_snapshots 0 / export_jobs 1・thumbnails 6（=2×assets）・実施中 FATAL 0 → 機内解除（default network 復帰を確認） | 2026-10-25 / TB710FU(API36) / 機内 ON 状態で連続実施 / debug ビルド（PR-19 込み） / ※端末にロック認証あり（adb 解除不可・途中再起動とユーザー手動解除を記録）。通信試行 0 は静的根拠（INTERNET なし）+ ping 不通で帰結 | ✓ 機内モード下の全標準フロー緑。**残置**: クラウド picker 経路はこの端末では表示されず未観察（2026-10-25 実観察・07 計画書 §19 継続） |

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
# ΔE2000 は GoldenDeltaE2000 タグにも出る（記録専用・assert なし・PR-18。report JSON の
# metricsCiede2000 フィールド追加・既存キー不変）
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
