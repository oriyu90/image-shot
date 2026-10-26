# 09 Wave-3 残作業と以降の実装計画（2026-10-25 作成・2026-10-26 更新: PR-27/28a 完了・28b 着手前時点）

**位置づけ**: 正本の進捗は `06_未実装欠陥と実装計画.md` §3.2 と `benchmark/nfr_catalog.md`。手順詳細は `07_Wave3実装計画書.md`。本書は 2026-10-25 時点での現状整理・コード接続点の実地調査・以降の着手順（ユーザー確定判断 4 件を含む）をまとめた作業計画であり、進捗が動く都度 06/07 を PR 単位で更新する。

---

## 1. 現状整理（2026-10-25 作成・2026-10-26 更新）

### 1.1 コミット済み（Wave-3 PR-17〜27・全 8 コミット）

| コミット | 内容 |
|---|---|
| `a968072` / `5014592` | PR-17（NFR-08 bundletool 実寸）・PR-18（NFR-07 しきい値確定・ΔE 決定論確認） |
| `ae63da6`…`3954828` | PR-19（表示名連鎖+実機観察）・PR-20（NFR-11 オフライン+kill 30/30）・PR-17〜20 正本更新 |
| `ea65cae` | PR-21（AGP 9.4 / Gradle 9.6 / built-in Kotlin 2.2.10・全回帰緑） |
| `819a8b7` | PR-22（S-03 HDR spike → ADR-026 Accepted） |
| `0e8463d` | PR-23（A3 stage-1 gainmap policy 実装 + B2 EXIF whitelist pass-through・unit 232/232） |
| `2b753f8` | PR-27（G1 文字列外部化・08 台帳新設・実機 en-US Ja フォールバック確認・lint 3 feature error 0） |

※ 第 9 コミット = **PR-28a**（本節 1.2・2026-10-26・push なし〔⑧〕）。

### 1.2 PR-28a（D1 per-channel curves）— **実装・全検証完了・2026-10-26 本コミットで締め（第 9 コミット）**

実装: payload v1 に `ch`/`r`/`g`/`b` を追加（後方互換・Room schema v1 不変・適用は curve 全量・不正 `ch` は行 PRESERVED_ONLY）・`RecipeToneCurve.buildChannelLuts` per-channel 評価（master 先行・中立/空=恒等 short-circuit・master-only 経路の FP 演算順序不変で既存 golden 決定論保全）・`EditStore.commitCurve` 全量 + `editedChannel`・undo/redo 全量復元・editor chips UI（`CurveChannel` 4 選択・履歴ラベル `%2$s = channel label`）・文言回収（`editor_tab_hint_curve`/`editor_curve_channels_hint` 削除・channel 4 key 追加・title「カーブ」〔08 §5〕）。

| 検証 | 結果 |
|---|---|
| `./gradlew test` | exit 0（EditStore/RecipeToneCurve/RecipeDevelopApplier/GainmapExportPolicy の新規・更新テスト含む全回帰緑） |
| lint / release | `:feature:{editor,export,library}:lintDebug` error 0・`:app:assembleRelease` 緑 |
| `tools/ui_strings_check.py` | **違反 0 件**・例外 29 行（08 §3 に 2026-10-26 実行記録） |
| golden ΔE（実機 TB710FU・4/4 OK 21.15s） | 既存 3 ケース基線一致（non-neutral 0.5077/1.2212・downsampled 0.5255/1.1926・neutral 恒等 0.09996）+ **新規 D1 `per-channel-curve(r-lift,g-empty,b-dip)`: mean 0.5164 / p95 1.1061 < (1.00/2.00)**（ΔE2000 参考 0.3174/0.7408・2048×1365 合成ソース q90・pxCount 2,795,520） |
| 実機機能（SAF import 1600×1200 probe） | r1 R 編集 commit → プレビュー R 127→195・**G/B 不変**（チャンネル分離実測）/ r2 undo 中立ビット復帰 / r3 redo r1 ビット復帰 / r4 master 追加全チャンネル上振れ 246/223/230 / r5 R のみ「中立へ戻す」master 保全 233/223/230 / **force-stop→再起動で r5・履歴 5 件ビット復元** |
| DB payload 実測（run-as+sqlite3） | r1〜r5 全量が payload v1 契約どおり（r2 undo は r を除いた全量・r4 は ch 省略+r 保持・r5 は rgb master のみ）・`currentRevision=5` |

- 異常切り分けの結論（実測で確定）: 先に観察した「カーブタブ プレビュー色ずれ」は**誤り** — r0 中立プレビューは probe 原図と 3×3 グリッド均値 diff 0.0（ビット一致）。probe 原図自体が対角グラデ（左上: 暗・右上: マゼンタ・左下: 緑）で、旧メモの sips 800px 縮小表示が誤認の発生源。追跡不要
- **残置**: ① カーブダブルタップ点削除はコード実装のみで実機未観測（28b で併検）② 48MP proxy 性能記録は 28c（NFR-04 実測）と fixture 待ちに含める

### 1.3 ブロッカー・待機

- **PR-24/25/26 fixture**: 48MP RAW サンプルと 4GB 端末 — ユーザー決定「外部 SD 経由で提供」で調達待ち。到着した時点で PR-24 spike を先行着手
- **ADR-026 stage-2 制限**: カメラ/第三者由来 UltraHDR ソースが未観察（自作合成でのみ実測済）
- **判断待ち⑧（リモート）→ 2026-10-25 確定: 「ローカル継続（push なし）」**。07 §18 Wave-3 完了条件⑤は「理由明記で未対応」として閉じる（完了条件の除外対象・06 §4 も更新）
- **判断待ち⑦（targetSdk 37）**: PR-30 完了後に再評価（Play 要件 2027 半ば見込み TBC）
- **PR-19 残置**: Photo Picker クラウド経路の観察未達（端末/アカウント条件成立時に再観察）

---

## 2. 調査分析（コード接続点の実地確認・2026-10-25）

### 2.1 残欠陥 D1/D4/D2 と現行コードの接続点

| 欠陥 | 現状の接続点（実地確認済み） | PR-28 系での拡張内容 |
|---|---|---|
| **D1** per-channel カーブ | `RecipeToneCurve.buildChannelLuts(develop)`（RecipeToneCurve.kt:114）— LUT 生成は develop 一括。payload は master のみ（同ファイル :132）。DB は payload JSON 演算で Room 影響なし | payload に r/g/b 配列を同構造で追加（`ignoreUnknownKeys` 後方互換）・`buildChannelLuts` 引数拡張・カーブ UI のチャンネル選択 — **✅ 28a で実施済み（2026-10-26・07 §12.1）** |
| **D4** 自由角度・自由クロップ | `GeometryParams.angleDeg` は**既に Double**（EditRecipe.kt:87）・制限は `EditNodeSpecs.isAllowedAngle` + `RecipeGeometry.apply`（rotateCw90×quarterTurns + rect クロップ）。`cropNorm` は正規化矩形でモデル側は最小拡張 | `isAllowedAngle` の解除 + 任意角逆写像（Matrix）新段・エッジ塗り仕様確定・UI（自由角スライダー/最小エッジドラッグ） |
| **D2** ディテール | `SimpleBitmapPhotoRenderer.kt:220` の警告 `"unimplemented nodes ignored: detail, vignette, local masks"` のまま通過 | unsharp 3x3 luminance + 軽量 NR。preview 尺で NFR-04 初回実測。48MP 対応は fixture 後 |

### 2.2 波及分析

- **性能（NFR-04 p95 100ms）**: 初回実測が可能になるのは PR-28c（D2 空間フィルタ）。preview 尺（TB710FU 2000×3200）での実測が即座に可能・48MP proxy は fixture 待ちとして明記する。
- **golden**: ユーザー確定に基づき **D1/D4/D2 それぞれ preview=export ΔE 追加ケース**を作る（PR-18 のしきい値方針〈中立 mean<0.30 / 非中立 mean<1.00 / p95<2.00〉を継承）。
- **編集履歴**: 28a の per-channel 操作は PR-27 で res 化済みの `editor_history_*` 経路を流用。文言接続済みのため追注は res 追加のみ。
- **UI 文言の同時更新義務**: 28a では `editor_tab_hint_curve`（「R/G/B チャンネル別は未実装」）を回収、28c では `editor_detail_*` 警告を回収（08 台帳の res 正本原則を PR 内で閉じる）。
- **DB/Room**: 28a/b/c はすべて payload JSON 拡張（schema v1 維持）。schema v2 対応は PR-29（masks）まで保留のまま。

### 2.3 リスク

1. **D4 の座標意味論**: 回転後正準座標系での cropNorm の解釈を先に確定しないと preview≠export が起きる（JVM 固定値 golden が先行必須）。
2. **D2 性能超過**: ユーザー確定契約 = **超過時は自動降格し、UI に「処理を軽くしたため自動降格しました」明示 + 実測値を NFR-04 行に記録**（07 §12 の失敗時契約を明文化）。
3. **fixture 不在の解約誤防**: RAW 系（PR-24/25/26）は NFR-10 真の実測が不可のまま「未測定」で残す。
4. **stage-2 HDR / mask GPU**: 明示保留の継続（ADR-026 の再検討条件に準拠）。

---

## 3. 今後の実装計画（確定・2026-10-25 ユーザー判断込み）

### Step 0: PR-27 締め — **✅ 完了（commit `2b753f8`）**: lint 3 feature/en-US Ja フォールバック/フォント 2.0+最小幅目視・06 行更新を含む

### Step 1: **PR-28a D1 per-channel curves**（規模 M・28c より先）— **✅ 完了 2026-10-26（実施結果: §1.2・07 §12.1）**

- payload JSON 拡張（r/g/b 同構造・旧 payload 読込み互換テスト）・`buildChannelLuts` 引数拡張・カーブ UI（チャンネル選択・hint 文言回収）
- テスト: JVM 固定値 golden + **ΔE 追加ケース（確定）**・Undo/Redo 往復・DB 復元（kill 注入）
- 受入: preview=export 同一・旧 payload 読込み緑・06/07 更新・1 commit
- **実測**: golden ΔE D1 mean 0.5164/p95 1.1061 合格・実機 undo/redo 往復+force-stop 復元 bit 一致・DB payload r1..r5 契約どおり。**旧 payload 読込み互換は JVM テスト締め・kill 注入（30 回）は 28b 以降の実機回帰に含める**

### Step 2: **PR-28b D4 自由角度・自由クロップ**（規模 M・28a 依存）

- `isAllowedAngle` 解除 + 任意角逆写像（Matrix）+ エッジ塗り仕様確定・UI 最小版・`editor_tab_hint_geometry` 文言回収
- テスト: 逆写像固定値 golden + ΔE 追加（確定）・preview=export 同一・90°既存 payload の保全確認
- 受入: 同上 + NFR-04 候補として preview 尺での frame 実測記録

### Step 3: **PR-28c D2 ディテール**（規模 M/L・28b 依存）

- unsharp 3x3 luminance（preview 尺）+ 軽量 NR。**超過時は自動降格 + UI 明示（確定契約）**
- テスト: 固定値 golden + ΔE 追加（確定）・**NFR-04 初回実測値を nfr_catalog 行へ**（48MP 分は fixture 待ちと明記）
- 受入: 降格経路の確認（自動降格の文言と request 出力）・`editor_detail_*` 文言回収

### Step 4: fixture 到着後 — **PR-24（RAW spike → ADR-027）→ PR-25 → PR-26**

- PR-24: Java `ImageDecoder` DNG 実測（linear/Bayer/opcode 付/ProRAW 様）・寸法/色/gainmap 取り出し・48MP メモリ実測（C-04）・platform vs LibRaw 色差（ΔE）
- PR-25: RAW pipeline 第一段。PR-26: NFR-10（ダウングレード scheduler）+ サムネイル効果の実測（Macrobenchmark 有無比較）
- fixture 不在時は NFR-10 の真の実測を行わない（未測定のまま維持）

### Step 5: **PR-29（D3 マスク・29a/29b/29c 分割）→ PR-30 → Wave-3 完了確認**

- 29a: mask 型 payload（linear/radial/brush+feather・Room migration なし）+ JVM 固定値 golden・29b: mask bitmap cache + blend（CPU・性能実測明記）・29c: overlay UI
- PR-30: G2 availability 更新・G3 insets 統一・**F3 adaptive（targetSdk 37 の 600dp 条件対応が必須）**。完了後に判断待ち⑦ 再評価
- Wave-3 完了チェック（07 §18）: 06 §3.2 確定・nfr_catalog 更新・R8 リリース版実機回帰 2〜3 回・**push は⑧決定により「未対応・理由: リモート無し/ローカル維持（2026-10-25 決定）」を明記して閉じる**

### 並行・後回し候補（着手条件付き）

1. **stage-2 HDR（ADR-026 警告文言の UI 結線を含む）**: カメラ/第三者 UltraHDR ソース入手後
2. **warning key model**: core:storage / core:render 診断文言の res 化再検討（08 台帳 §2 再検討条件）
3. **EN 文言（values-en）**: 型（strings 参照）は整備済み → 文言追加 1 PR
4. **ADR-027 の masks 併記要件**: PR-29 計画時に ADR 軽量記録（07 §17）

### 依存図（更新版・2026-10-25）

```
28a ✅(2026-10-26) ──→ 28b(D4・次・28a 依存) → 28c(D2・NFR-04 実測)
fixture 外部 SD（調達待ち）────→ 24(spike/ADR-027) → 25(RAW pipe) → 26(NFR-10+thumbnail)
Wave-3 完了: 28c と 26 が揃った段階で 29a/b/c → 30 → 完了チェック → ⑦ targetSdk 37 再評価
```

---

## 4. 判断待ちの更新（06 §4 に反映すべき分・2026-10-25）

| 項目 | 状態 | 出所 |
|---|---|---|
| ⑧ git リモート | **確定: ローカル継続・push なし**（ Wave-3 完了条件⑤は理由明記で閉じる） | 本書・2026-10-25 ユーザー決定 |
| ⑥ fixture 調達 | 外部 SD 経由で調達待ち（到着後 PR-24 先行） | 2026-10-25 ユーザー決定 |
| ⑦ targetSdk 37 | PR-30 完了後に再評価 | 07 §14 |
| PR-28 進め方 | **確定: 28a/28b/28c 3 分割** | 本書・2026-10-25 ユーザー決定 |
| golden 追加 | **確定: D1/D4/D2 それぞれ追加** | 同上 |
| D2 失敗時契約 | **確定: p95 超過時 自動降格+UI 明示+実測記録** | 同上 |

---

## 5. 免責・本書で確定していないこと

- 各工数（S/M/L/XL）は 06 §3.2 原則の推定。実際の着手時に行頭に実測値（commit・検証件数） を確定させる。
- NFR-01/02（vitals）は公開前のため未測定のまま維持。
- en ロケール目視・フォント両端目視は **PR-27（`2b753f8`）で実施済**（06 §3.2 PR-27 行に実機記録済み）。
- RAW/HDR の真の性能実測は fixture 到着まで「未測定」として残る（推定を成果と書かない）。
