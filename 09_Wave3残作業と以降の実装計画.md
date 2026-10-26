# 09 Wave-3 残作業と以降の実装計画（2026-10-27 更新: PR-28b/28c・PR-30 完了／PR-29 は fixture ゲート待ち）

**位置づけ**: 正本の進捗は `06_未実装欠陥と実装計画.md` §3.2 と `benchmark/nfr_catalog.md`。手順詳細は `07_Wave3実装計画書.md`。本書は PR-28a（`1355191`）完了を受けた 2026-10-26 の全面更新版であり、2026-10-25 版の現状整理と調査分析を置き換える（Steps の番号割付・判断待ちの確定事項は引き継ぐ）。進捗が動く都度 06/07 を PR 単位で更新する。

---

## 1. 現状整理（2026-10-26 時点）

### 1.1 コミット済み（Wave-3 関連 12 コミット・push なし〔⑧〕）

| コミット | 内容 |
|---|---|
| `7c3b9d9` / `2898efa` | Wave-3 計画書作成（06 §3.2 詳細・07 詳細・2026-10-20） |
| `a968072` / `5014592` | PR-17（NFR-08 bundletool 実寸）・PR-18（NFR-07 しきい値確定・ΔE 決定論確認） |
| `426c03e` / `3954828` | PR-19（表示名連鎖+実機観察）・PR-20（NFR-11 オフライン+kill 30/30） |
| `ae63da6` | PR-17〜20 正本更新（06 §3.2・台帳 NFR-09/11・07 §19） |
| `ea65cae` / `819a8b7` / `0e8463d` | PR-21（AGP 9.4/Gradle 9.6/Kotlin built-in 2.2.10）・PR-22（S-03 HDR spike→ADR-026）・PR-23（A3 stage-1+B2 EXIF pass-through） |
| `2b753f8` | PR-27（G1 文字列外部化・08 台帳新設・lint 3 feature error 0・実機 en-US Ja フォールバック確認） |
| `1355191` | **PR-28a（D1 per-channel curves・golden ΔE 4 ケース化）** |
| `3023051` | **PR-28b（D4 自由角度/自由クロップ）+ PR-28c（D2 ディテール）**（実装連続のため 1 コミットに統合・golden 5→6 ケース化） |
| `006d850` | PR-28c 12MP 超軽量経路テスト・06/09 同期 |
| `bff70c1` | **PR-30（G2 availability・G3 inset・F3 adaptive）** |

### 1.2 PR-28a（D1 per-channel curves）実施結果 — **commit `1355191`・2026-10-26**

| 検証 | 結果 |
|---|---|
| `./gradlew test` | exit 0（EditStore/RecipeToneCurve/RecipeDevelopApplier/GainmapExportPolicy 更新含む全回帰緑） |
| lint / release | `:feature:{editor,export,library}:lintDebug` error 0・`:app:assembleRelease` 緑 |
| `tools/ui_strings_check.py` | 違反 0 件・例外 29 行（08 §3 に 2026-10-26 記録） |
| golden ΔE（実機 TB710FU・4/4 OK 21.15s） | 基線 3 ケース一致（0.5077/1.2212・0.5255/1.1926・0.09996）+ **新規 D1 `per-channel-curve(r-lift,g-empty,b-dip)`: mean 0.5164/p95 1.1061 < (1.00/2.00)**（ΔE2000 参考 0.3174/0.7408・2048×1365 合成ソース q90・pxCount 2,795,520） |
| 実機機能（SAF import 1600×1200 probe） | r1 R 編集 → プレビュー R 127→195・**G/B 不変**（チャンネル分離実測）/ r2 undo 中立ビット復帰 / r3 redo r1 ビット復帰 / r4 master 追加（全チャンネル 246/223/230）/ r5 R のみ「中立へ戻す」（master 保全 233/223/230）/ **force-stop→再起動で r5・履歴 5 件ビット復元** |
| DB payload 実測（run-as+sqlite3） | r1〜r5 が payload v1 契約どおり・`currentRevision=5` |

- 異常切り分けの結論（実測で確定・閉結）: 先に観察した「カーブタブ プレビュー色ずれ」は**誤り** — r0 中立プレビューは probe 原図と 3×3 グリッド均値 diff 0.0（ビット一致）。probe 原図自体が対角グラデ（左上: 暗・右上: マゼンタ・左下: 緑）。旧メモの sips 800px 縮小表示が誤認の発生源。追跡不要
- **28a 残置**: ① カーブダブルタップ点削除はコード実装のみ・実機未観測 ② 28b 以降の実機回帰に kill 注入 30 回（旧 payload 読込み互換の実機側確認を含む）③ 48MP proxy 性能記録は 28c + fixture 待ち

### 1.5 PR-28b（D4 自由角度・自由クロップ）実施結果 — 2026-10-26

| 検証 | 結果 |
|---|---|
| `./gradlew test` / lint / 台帳 | exit 0・`:feature:{editor,export,library}:lintDebug` error 0・`ui_strings_check.py` 違反 0（例外 29 行） |
| golden ΔE（実機・5/5 OK） | 既存 3 基線**完全一致** 0.507666/1.221216・0.525469/1.192629・0.099962/0.823092 + D1 0.516351/1.106067 + **D4 12.5°+crop: mean 0.276837 / p95 1.218100 / p99 2.238108 / max 42.5651（2,607,120 px）** < (1.00/2.00) |
| 実機 UI | 45°（黒縁）→0°→90°→微調整 +9.545°=99.5°・1:1 クロップの移動/縮小・undo/redo・force-stop 復元（r19・履歴 19 件）・DB 19 行 payload 正準（angle [0,360)）・旧 r1..r5 完全一致・integrity ok・FATAL 0 |
| kill 注入 | **30/30 PASS**・終端 DB edit_revisions=19 / documents=1 / assets=1・新規 FATAL 0 |
| JVM 固定値 | 30°→5×4 の画素配列・周期性・90° 厳密経路・外周黒・適用順・角度正準化・fineAngle 境界（46→−44/315→+45）・crop/NaN 拒否 |

- 残置: カーブのダブルタップ点削除は実機未観察（コードのみ）。48MP proxy の性能/画質は fixture 待ち（D2 の降格閾値は preview 実測で校正する）。

### 1.6 PR-28c（D2 ディテール）実施結果 — 2026-10-26

| 検証 | 結果 |
|---|---|
| unit / lint / 台帳 / release | 全緑・lint error 0・`ui_strings_check` 違反 0（例外 29 行）・`:app:assembleRelease` 緑 |
| golden ΔE（実機・6/6 OK 32.5s） | 既存 3 基線ビット一致 + D1 0.516351/1.106067 + D4 0.276837/1.218100 + **D2（sharpen0.5/lumaNR0.25）mean 0.116023 / p95 0.830512 / p99 1.4773（2,795,520px）** |
| UI（r20 起点） | シャープ +47（r21）→ 輝度NR +33（r22）→ undo（r23 NR0）→ redo（r24）→ force-stop 復元（r24・履歴24） |
| DB | detail payload v1（path/value/op）・currentRevision 48・integrity ok |
| NFR-04 | **p95 19ms（p50 8/p90 14/p99 25・691 frames・benchmark buildType・ディテールスライダー 12 往復）** < 100ms |
| kill 注入 | **30/30 PASS**・48 revisions/1 document/2 snapshots・再起動後 r48/+13/+33 復元・FATAL 0 |
| 副次修正 | 履歴リストの潰れ（20 件超）を高さ上限+内部スクロールで修正（外側パネルのスクロールで全件到達） |

- 残置: 48MP 級（>12MP）の軽量経路は fixture 待ちで未測定。preview が閾値を超えない限り降格文言は出ない（export は issueNotes 記録のみ）。

### 1.7 PR-30（G2/G3/F3）実施結果 — 2026-10-27

| 項目 | 結果 |
|---|---|
| G2 availability | `AvailabilitySweep`（純関数・JVM 8 件）+ `verifyVisibleAvailability()`（表示時・ok のみ・IO・単一ジョブ・3s 下限）。失効/切断を DB 反映し Room Flow で一覧即時更新。書き出し `URI_REVOKED` を日本語化 |
| G3 inset | `enableEdgeToEdge()` + ホスト一括 padding 撤去 → library/editor/export の 3 画面が `Scaffold(WindowInsets.safeDrawing)` で自己管理。Manifest `adjustResize`。inset modifier の二重適用 0 件（grep） |
| F3 adaptive | 840dp 以上で 2 ペイン（library: 一覧+360dp 詳細 / editor: プレビュー+360dp パネル）。狭幅は従来 1 ペイン。Nav3/ListDetailPaneScaffold は不使用（navigation-compose 維持）。`GridCells.Adaptive(112.dp)` は既存 |
| 実機（TB710FU/API36） | landscape 3200×2000: 2 ペイン・選択→詳細→「開いて編集」・編集 2 ペイン・inset 正常。portrait 2000×3200（941dp）: 2 ペイン・回転で選択タブ保持・二重 inset なし。狭幅 1280×800（602dp）: 1 ペインで従来動作。IME: 検索欄フォーカスでキーボード表示（mInputShown=true）時も検索欄/一覧が隠れない。export: Scaffold inset 目視 |
| Lead 修正 | 360dp パネルでタブ 6 個が折返し/画面外 → タブ行を横スクロール化。2 ペイン選択セルに枠線追加 |
| 回帰 | `./gradlew test :feature:{editor,export,library}:lintDebug :app:assembleRelease` exit 0（unit 277・lint error 0）。`ui_strings_check` 違反 0（例外 29 行）。R8 release APK を debug 鍵で再署名して実機スモーク（ライブラリ 2 ペイン描画・FATAL 0・プロセス生存） |
| 未検証 | 折りたたみ/マルチウィンドウ、数値入力ダイアログの IME、URI 失効の実機観察（原本削除は破壊的なため未実施・JVM/コードで担保）。ヒンジ領域 |

### 1.3 実機・証跡の現状

- **TB710FU（`HA2EPG4J`・API 36）**: アプリ DB に doc `currentRevision=5`・履歴 5 件（master 3 点(0.4605,0.8941)+R 未編集・g/b 空）が残留 — **PR-28b の実機検証はこの状態を起点にできる**（同 doc 上で r6 以降を追加し undo/redo の延長検証が可能・新 import による別 doc 検証も可）
- 一次証跡: opencode テンポラリ（`/private/var/folders/…/T/opencode`）配下に s_r0〜s_restore.png（6 枚）・golden_report.json・probe.jpg・imageshot2.db・ui*.xml 保存済み
- golden ΔE 基線は **4 ケース化** — 28b/28c 追加時の回帰正本（追加は既存 4 ケース基線一致を前提に実施）

### 1.4 ブロッカー・待機

- **PR-24/25/26 fixture**: 48MP RAW サンプルと 4GB 端末 — 外部 SD 経由で調達待ち（到着後 PR-24 spike 先行）
- **ADR-026 stage-2**: カメラ/第三者 UltraHDR ソース未観察（自作合成のみ実測済）
- **判断待ち⑦（targetSdk 37）**: PR-30 完了後に再評価
- **PR-19 残置**: Photo Picker クラウド経路の観察未達

---

## 2. 調査分析（2026-10-26・explore 3 本の実地調査・file:line は `1355191` 時点）

### 2.1 D4（PR-28b 自由角度・自由クロップ）接続点

| 領域 | 実態（調査確定値） | 28b での変更 |
|---|---|---|
| モデル | `GeometryParams`: `cropNorm`(List<Double> 4)/`angleDeg`(Double)/`mirrorX`/`mirrorY`/`lensProfileId`/`perspective`（EditRecipe.kt:83-93）・中立は null ではなく既定値（cropNorm=[0,0,1,1]・angleDeg=0・mirror false） | 角度の連続値化・payload 互換 |
| ゲート | `ALLOWED_ANGLE_DEG=[0,90,180,270]`（EditNodeSpecs.kt:62）・`isAllowedAngle`:67-68・`normalizeGeometry`:75-86（角度不正=null=FAIL・crop はクランプ+span>0）・`isNeutralGeometry`:89-94 | isAllowedAngle 解除・mod 360 正準化・不正値 FAIL の維持 |
| 適用 | `RecipeGeometry.apply()`:105-111 — **固定順「回転→反転→クロップ」**・90°回転はピクセル再索引 `rotateCw90Once`(:37-51)・任意角は現在 quarterTurns が 0 に落ちる(:24-27)・crop は正規化座標×寸法を roundToInt+coerce で配列抽出(:84-99・エッジ塗りは存在しない=クロップ即リサイズ方式)・Matrix 不採用の既定(:13-16)・中立短絡 `applyIfNeeded`:114-134 | 任意角逆写像新段（Matrix 採否は JVM 測定で決める）・crop の端数丸め処理の両経路統一 |
| render 経路 | preview: SimpleBitmapPhotoRenderer.kt:176-209（getPixels→EXIF 正規化 185-192→**geometry 194-200**→develop 202→setPixels 206-208・per-pixel 直処理で Canvas clip 不使用）/ export: ExportRenderer.kt:258-296（decode→EXIF 278-282→**geometry 283-290**→develop 292→resize 298） | 正本「回転→反転→クロップ」順序を両経路で **維持**（座標系の正準解釈が変わらない） |
| UI/VM | GEOMETRY タブ: 回転ボタン/反転トグル/状態行/比プリセットチップ（EditorScreen.kt:788-853）・`onGeometryRotate`:339-346（±1 quarter mod4=90°畳み込み・**置換対象**）・`onGeometryFlip`:349-353（実装済・本 PR 範囲外）・`onGeometryCrop`:356-371 / commit: `commitGeometryNormalized`:470-474→`editStore.commitGeometry`:248-277（正規化 FAIL=262-266） | 角度 UI（最小版）追加・自由 crop の UI 最小版 |
| FAIL 文言 | **`editor_save_geometry_failed` 専用 key は存在しない**〔実地確認〕— commit 失敗は汎用 `editor_save_failed`（"`<code>`: `<detail>`"・EditorScreen.kt:190-192）・Degraded=`"degraded: <code>"`(:453-459)・`resyncAfterFailure`:549 | 新 key 不要・要点は FAIL 経路の既存契約を保持 |
| 文言回収 | `editor_tab_hint_geometry`(strings.xml:26・「未実装」明記)・`editor_crop_unimplemented`(:72・「自由角度・エッジドラッグ・透視未実装」)・`editor_crop_header`(:70・90/270 自動入替注記)・`editor_geometry_status`(:69) | 26/72 は撤去・69/70 は書式調整可能性（08 台帳同梱） |
| DB payload | `{"geometry":{"angleDeg","mirrorX","mirrorY","crop":[x0,y0,x1,y1]}}`（`crop` キー・EditEvent.kt:109-119・EditStore.kt:267-275 書/698-714 読） | Room schema v1 不変・既定キー保持で 28a 前例どおり |
| pass-through ゲート | `GainmapExportPolicy.kt`:109-114 — geometry 非中立で pass-through 不成立 | 自由角=リサンプルなので自動的に STRIP（安全側契約の自然維持・新コード不要） |
| テスト更新対象 | RecipeGeometryTest（回転/反転/crop/順序/quarterTurns/develop 可換）・SourceOrientationTest:19-140・**EditStoreTest:733-738（自由角度 3.5° 拒否テスト=動作変更対象）**・GainmapExportPolicyTest:155-162/186-191（自由角 37.5° ブロック契約 — 継続確認へ更新）・EditStoreInstrumentedTest:66-83/342-346 | 上記を 28b 仕様へ更新 |

### 2.2 D2（PR-28c ディテール）接続点

| 領域 | 実態（調査確定値） | 28c での変更 |
|---|---|---|
| ノード定義 | `EditRecipe.Detail(sharpen,lumaNoise,chromaNoise)`（EditRecipe.kt:75-80・コード唯一の実装語）・`EditEventKind.DETAIL(id="detail", RESERVED_M2)`（EditEvent.kt:100-105）・**AdjustmentId 未定義**（AdjustmentSchema.kt:36-69 に detail 系なし） | AdjustmentId 追加（01 §5.1 のコード単一スキーマ規律） |
| 未適用証明 | `RENDER_WARNINGS` "unimplemented nodes ignored: detail, vignette, local masks"（SimpleBitmapPhotoRenderer.kt:218-222）・検出は `RecipeDevelopApplier.unappliedNodeWarnings`:89-101（detail 3 値≠0・自由角・lens=perspective・masks 非空） | detail 未適用の原因消去（実装後・文言精査） |
| 挿入点 | preview: **develop 適用 :202 直後〜Bitmap 生成 :206 の前**・export: **develop :292 直後・resize :298 前** — 順序契約「develop 後・resize 前」（両経路同型・ExportRenderer.kt:94/269/292） | 空間 stage 追加はこの位置に固定 |
| 処理構造 | per-pixel Int ループ+768 段 LUT 合成（RecipeDevelopApplier.kt:35-64）・ARGB_8888 明示(SimpleBitmapPhotoRenderer.kt:179/206-207)・sRGB target 既付与(:104-106/:23-28)・**計時 hook なし**（core/render に nanoTime 0 件） | luminance 平面限定の unsharp 3x3+軽量 NR（現行ループ構造に調和） |
| warnings 流れ | preview→`EditorState.renderWarnings` まで成形（EditorViewModel.kt:92/171-189/683-707）・Screen 未消費（ADR-014 既知制限）/ export→`ExportWorker.kt:188-194` issueNotes→request ファイル | **28c の自動降格明示のみ UI 初結線**（限定結線・全面警告は stage-2） |
| benchmark | golden 追加は `GoldenPreviewExportDeltaETest` :63-111 同型（しきい値定数 :548-554・report :437-447）・frame 実測の正経路=gfxinfo `benchmark/run_frametiming_adb.sh`:14-24（percentile 抽出）・`FrameTimingMetric` は本端末 0fr の `@Ignore`（FrameTimingBenchmark.kt:21-24） | D2 ΔE ケース同型追加・gfxinfo 504 frames 前例準拠 |
| nfr_catalog 現状 | NFR-04: p50 9/p90 16/p95 21/p99 32 ms（スライダードラッグ 504fr・gfxinfo 代替・10-18 測定）✓仮目標内（:16）/ NFR-07/08/09/11 ✓・NFR-10 未測定（fixture 待ち） | NFR-04 行に D2 実測を追記（測定条件必添） |

### 2.3 文書ドリフト（軽量回収候補・PR-28b/c の docs 更新に同梱）

1. 06 §4 ④「schema v2」のまま — 07 §13 が最新（**schema 変更なし・payload JSON 追加フィールド**・schema JSON 固定寄託のみ先整備）→ 06 行更新 **✅ 2026-10-26 反映済**
2. 07 §19 の「`Bitmap.compress` gainmap 保全: 未検証」行 — **PR-22 で実測済み**（乗算+compress=完全保全）→ §19 行更新 **✅ 2026-10-26 反映済**
3. 05 §F'-1 が 2026-10-18 値のまま（master カーブ・基本ジオメトリ実装済みを未反映）→ **✅ 2026-10-26 更新済（28a/28b/28c 反映）**
4. README ビルド情報が PR-21 実態（AGP 9.4.0 / built-in Kotlin 2.2.10）に未追随（README は AGP 8.13.2 / Kotlin 2.2.21 記載のまま）→ **✅ 2026-10-26 更新済**
5. 02 §5.2 引用表記（07/09 他・複数箇所）の妥当性 — 本日実地確認で **02 に §5.2 が無く D コードの本質仕様は 01 側**を確認。引用表記の統一は次回 PR の docs 更新で処理（正本差替は行わない）

### 2.4 リスク→契約（28b/28c 共通の確定事項）

1. **D4 座標意味論**: `cropNorm` は「回転・反転後正準向き」基準（RecipeGeometry.kt:11-12・EditRecipe.kt:84）を冒頭で確定しないと preview≠export が起きる。任意角では crop 計算が連続値化し、丸め差（ExportRenderer.kt:264-265 に既存明示の ±1px 扱い）が両経路差に転化し得る → **JVM 固定値 golden で両経路の同時固定が先行必須**
2. **D4 pass-through**: 自由角=リサンプル発生 → GainmapExportPolicy の現契約「geometry 非中立=STRIP」が自然成立 — 新計算不要・テスト更新のみ（ADR-026 stage-2 とは接しない）
3. **D2 計時方針**: render 内 hook 追加か gfxinfo 代替か — 前例（504 frames・p95 21ms）がある gfxinfo が低摩擦で正本。NFR-04 行追記方式・実装時判断は留保
4. **D2 性能超過契約**（ユーザー確定の引継・07 §12 失敗時契約）: p95 超過=自動降格+UI 明示「処理を軽くしたため自動降格しました」+実測記録。降格方式（sample 縮小・半径縮小等）は実装・実測で決める
5. **未検証の継続**: 48MP（NFR-10・性能記録）は fixture 待ちのまま「未測定」基準を維持（推定を成果と書かない）
6. **UI 警告の全面結線はしない**: ADR-014 既知制限のまま — 28c は「自動降格明示」限定結線

---

## 3. これからの実装計画（確定）

### Step 2: **PR-28b D4 自由角度・自由クロップ**（規模 M・✅ 完了 2026-10-26・証跡 §1.5/07 §12.2）

- **着手順**:
  1. **JVM 固定値 golden 先行**: RecipeGeometry 任意角段の固定値化（入力角度/寸法→出力寸法・正準 crop のマッピング・エッジ塗り様式を含めて数値固定化）—「回転→反転→クロップ」正本順序・`isAllowedAngle` 解除後の mod-360 正準化・crop は正規化矩形+span>0 を維持
  2. `normalizeGeometry` 更新+`EditEvent.kt:109-119` payload 拡張（Room schema v1 不変・後方互換〔28a 前例〕）
  3. VM: `onGeometryRotate` の置換（連続角度）+GEOMETRY パネルの角度 UI（最小版）・自由 crop の UI 最小版〔判断事項①②参照〕
  4. **文言回収**: `editor_tab_hint_geometry`・`editor_crop_unimplemented` 撤去〔2 key〕+角度/crop の新規 key〔08 台帳 §1/§3/§5 同梱・PR-28a 同様〕
  5. テスト: RecipeGeometryTest 拡張・GainmapExportPolicyTest 155-162/186-191（現契約継続確認）・EditStoreTest 733-738 を新仕様へ・UndoRedo 往復+DB 復元・**kill 注入 30 回実機（28a 残置②〔旧 payload 読込み互換の実機側確認〕を消化・ダブルタップ点削除の実機観察〔残置①〕を併検）**
  6. **golden ΔE 追加**: 任意角+crop の preview=export ケース（既存 4 ケース基線一致を前提・しきい値は :548-554 準拠）
- **受入**: preview=export 同一（新 ΔE 緑）・undo/redo 往復+force-stop 復元・旧 payload 読込み緑・kill 30/30・06/07/08 更新・1 コミット（push なし〔⑧〕）

### Step 3: **PR-28c D2 ディテール**（規模 M/L・✅ 完了 2026-10-26・証跡 §1.6/07 §12.3）

- **着手順**:
  1. AdjustmentId 追加（sharpen/lumaNoise/chromaNoise・01 §5.1 規律）+editor 状態+`editor_detail_*` 文言（title/help/降格文言・08 台帳同梱）
  2. `RecipeDevelopApplier.applyToPixels` 直後に luminance 平面限定の unsharp 3x3+軽量 NR stage を追加（前項の挿入点契約・preview/export 同経路）
  3. **計時+自動降格**: p95 超過 → 処理軽量化 → UI 明示（EditorScreen 結線・初の UI 警告経路）+issueNotes 記録
  4. **実測**: preview 尺 gfxinfo（504fr 前例準拠）→ `nfr_catalog.md`:16 NFR-04 行に測定条件付き追記・48MP 分は fixture 待ち明記
  5. golden ΔE D2 追加+unit/undoRedo+kill 注入 30 回（28b 残分と併せて回収）
- **受入**: 同上+NFR-04 行実測・`editor_detail_*` 回収・1 コミット

### Step 4: fixture 到着後 — **PR-24（RAW spike → ADR-027）→ PR-25 → PR-26**（着手条件: SD 到着・計画内容は現状のまま）

### Step 5: **PR-29（D3 マスク・29a/29b/29c）→ PR-30 → Wave-3 完了チェック**（PR-30 ✅ 2026-10-27／PR-29 は PR-26 完了が前提のため fixture 待ち）

- 29 系は 07 §13（schema 変更なし・payload 追加フィールド）が最新正本（06 §4 ④ のドリフト回収を 29a 前に実施）
- 30: G2 availability 更新・G3 insets・F3 adaptive（targetSdk 37 の 600dp 条件対応必須）— **✅ 完了 2026-10-27（§1.7）**
- 完了チェック（07 §18）の残分: 「06/07 反映 ◐」（残 PR 分）・「nfr_catalog 対応行の更新」（28c で NFR-04・26 で NFR-10）・「R8 リリース版実機回帰 2〜3 回・最終回未」（30 完了後）・push「理由明記の未対応 ✅」

### 文書保守（軽量・Step 2 以降に挟む）

- §2.3 の 5 項を回収（PR-28b docs 更新に同梱・独立 docs commit も可）

### 判断事項（本計画の着手に向けてユーザーが確定する・着手阻害にはならない）

1. **自由角度の範囲と UI 形式**: 例: ±30°スライダー / −180..180 連続 / ボタン+ドラッグ（既定値・保存値と一貫した mod 正準化が並行条件）
2. **回転外周エッジ塗りの仕様**: 黒固定 / 近傍色反射 / クロップで消解（ADR 直系の決定事項）
3. **自由クロップの UI 最小版**: 既存プリセット維持+オフセット移動のみ / エッジドラッグ実装（含めない分は PR-29b へ持ち越し可）
4. **D2 の NR 範囲**: sharpen のみ / sharpen+lumaNR 限定版 / 軽量 chromaNR まで（fixture 待ちを阻害しない範囲で 28c 内に収める）

### 依存図（更新版・2026-10-26）

```
28a ✅(1355191) ──→ 28b(D4・次) → 28c(D2・NFR-04 実測)
fixture 外部 SD（調達待ち）────→ 24(spike/ADR-027) → 25(RAW pipe) → 26(NFR-10+thumbnail)
Wave-3 完了: 28c と 26 が揃った段階で 29a/b/c → 30 → 完了チェック → ⑦ targetSdk 37 再評価
```

---

## 4. 判断待ちの現状（2026-10-26 時点）

| 項目 | 状態 |
|---|---|
| ③ RAW spike 方式 | 未決継続 — PR-24 spike 後に ADR-027（platform DNG primary + LibRaw fallback / DNG-only 二択・リサーチ反映済） |
| ④ D3 masks | 07 §13 方針（payload JSON・schema 不変）が最新・**06 §4 ④ はドリフト（§2.3-1）** |
| ⑤ HDR stage-1 | ✅ 解決 2026-10-25（ADR-026 条件付き pass-through） |
| ⑥ fixture | ✅ 外部 SD 経由確定・調達待ち |
| ⑦ targetSdk 37 | PR-30 完了後に再評価 |
| ⑧ git push | ✅ ローカル継続確定（理由明記の未対応で 07 §18⑤ 閉じ済み） |

---

## 5. 免責・本書で未確定のこと

- 各規模（S/M/L/XL）は 06 §3.2 原則の推定・着手時に実測値で確定
- §2 の file:line は **2026-10-26・`1355191` 時点**の実物調査（explore サブエージェント 3 本）にもとづく — 着手 PR 時に行頭で参照整合を再確認（既署名 PR・実測値の記載とは区別）
- NFR-01/02（vitals・公開前）/ NFR-10（fixture 待ち）・D4/D2 の受入数値（ΔE・frame p95）は**未測定**（推定を成果と書かない）
- 判断事項 4 項はユーザー確定待ち — 確定まで Step 2 設計の選択肢を閉じない
