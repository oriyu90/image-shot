# Prism Photo — 開発AI Skill構成・導入ガイド

- 文書ID: PRISM-SKILLS-001 / 2026-10-18
- 対象: OpenCode、Android Studio内エージェント、Agent Skills対応のコーディング支援AI
- 方針: **公開済みの公式Skillを更新可能な基盤とし、このアプリ固有の難しい分野のみ独自Skillにする。**

## 1. 調査結果

### 公式Skill（最優先）
- **Android公式**: https://github.com/android/skills
- **公式説明**: https://developer.android.com/tools/agents/android-skills
- `SKILL.md`（Agent Skills仕様）に準拠し、Android CLIから一覧/導入/更新可能。
- 公式リポジトリの確認されたカテゴリ: Jetpack Compose、navigation、build-system/agp、camera、device-ai、performance/r8-analyzer、profilers/android-profiler、security、testing/testing-setup。
- Adaptive対応の公式導線: https://developer.android.com/develop/ui/compose/build-adaptive-apps
- **注意**: リポジトリ内のディレクトリ名と`android skills add`に渡すインストール名は常に同一とは限らない。実際の一覧は`android skills list`で確認する。

### コミュニティ（補助）
- https://github.com/new-silvermoon/awesome-android-agent-skills
- Compose UI、architecture、ViewModel/StateFlow、data-layer、accessibility、compose performance audit、Gradle performanceなどの学習ガイドに有用。
- ただしコミュニティSkillは更新頻度、権限、依存スクリプト、質が不均一。導入前に`SKILL.md`と同梱scriptsをコードレビューし、公式指示より優先させない。

### 実行エージェントとの接続
- OpenCode公式仕様: https://opencode.ai/docs/skills
- OpenCodeのプロジェクト内スキル配置: `.opencode/skills/<name>/SKILL.md`。エージェントは`skill`ツールで必要なSkillだけをロードする。
- Android Studio標準の`.agent/skills` / `.skills`は独自Skill配置先。OpenCodeとは探索先が異なるため、今回の納品では**OpenCode形式を正本**とし、必要時は対応するディレクトリにコピーする。
- AIに技能を大量常時読込させるより、`AGENTS.md`は短く、個別Skillは必要なときだけ読み込ませる方がコンテキスト効率と検証容易性が良い。

## 2. 推奨インストール

```bash
# Android CLI導入後、プロジェクトで実行
android skills list
android skills add adaptive --project=.
android skills add r8-analyzer --project=.

# 他の候補は一覧に出る正確な名前を確認して選択
# 例: testing, security, navigation, camera, composeの該当スキル
android skills update --all
```

`adaptive`と`r8-analyzer`の導入方法は公式資料で確認されている。`--all`は大量追加になり得るため、画像編集用途ではまず必要なSkillを選別する。OpenCodeを使う場合、公式CLIの展開先が違えば`.opencode/skills`へ適切に配置し、名前重複を避ける。

## 3. Skillレイヤーと担当

| レイヤー | Skill / ソース | 目的 | 優先 |
|---|---|---|---|
| A: Android基盤 | 公式 adaptive | 折りたたみ・大画面・WindowSizeClasses | 最優先 |
| A: Android基盤 | 公式 navigation / testing-setup / security | ナビゲーション、UI/E2E、権限対策 | 優先 |
| A: 性能 | 公式 r8-analyzer / android-profiler | APKサイズ、リソース、CPU/RAM/GPU分析 | 優先 |
| A: optional | 公式 camera/camerax, device-ai | 将来の撮影/オンデバイスAI | 対象機能着手時 |
| B: Android補助 | community compose-ui、android-architecture | Compose状態管理・一方向データフロー | 審査後 |
| B: Android補助 | community accessibility、compose-performance | UI品質と再構成の監査 | 審査後 |
| C: 専用Skill | `prism-raw-pipeline` | RAW、カメラ固有差、デモザイク | 必須 |
| C: 専用Skill | `prism-color-hdr` | 色管理/ICC/HDR/Gainmap | 必須 |
| C: 専用Skill | `prism-render-tiles` | ネイティブGPU/CPU、タイル、メモリ | 必須 |
| C: 専用Skill | `prism-preset-interop` | XMP/旧形式/DNG設定の安全な解釈 | 必須 |
| C: 専用Skill | `prism-adaptive-editor` | プレビュー+編集パネルの適応レイアウト | 必須 |
| C: 専用Skill | `prism-data-safety` | 原本不変/DB/書き出しの回復性 | 必須 |
| C: 専用Skill | `prism-quality-gates` | 回帰/負荷/セキュリティ/ライセンス監査 | 必須 |

## 4. Skillsの設計上のルール

1. **Skillは「いつ使うか」をdescriptionに明記**。画像補正の仕事に画面ナビゲーションSkillを無関係に混ぜない。
2. 計測・参照資料はSkill本体に全コピーせず、`02_実装定義書.md`の章と必要な一次資料を参照する。
3. 完成コードの無条件生成よりも、**契約→テスト→実装→性能検証→判断更新**の順を指示する。
4. 各Skillの出力には変更対象モジュール、テスト計画、データ破損リスク、パフォーマンス影響を必須にする。
5. 未実装のネイティブ関数、OS API、RAW対応カメラを「動く」と宣言しない。最小実機再現を優先。
6. インストールしたSkillの同梱scriptにビルド権限・外部送信・リポジトリ書き換えがないかを確認する。
7. `AGENTS.md`からADR-xxxを参照できるようにし、設計変更はADRを先に改訂する。
8. テスト失敗時にgolden画像を安易に再生成しない。画質差の原因を説明するまで失敗を保持。

## 5. スキルを使った開発フロー

| 作業依頼 | 有効にするSkill | 成果物/合格条件 |
|---|---|---|
| RAW新機種追加 | raw-pipeline, quality-gates | 入力サンプル、CFA/黒レベル検証、速度・容量差 |
| 曲線/彩度ノード作成 | color-hdr, render-tiles, quality-gates | シーンリニアの効果テスト、CPU/GPU同値性 |
| XMPプリセット読み込み | preset-interop, data-safety | 解析結果ステータス、無害化、未知フィールド保存 |
| 端末回転/Tablet UI | adaptive-editor, 公式adaptive | 320–1600dpのスクショと操作性検証 |
| 大型TIFF書き出し | data-safety, render-tiles | kill/空き容量不足/重複ファイル名のE2E |
| APKサイズ削減 | 公式r8-analyzer, quality-gates | 配信ABI別サイズと機能回帰差分 |
| HDR画像修正 | color-hdr, data-safety | 輝度/メタデータ/SDR fallbackの往復確認 |

## 6. AI用役割分担（複数エージェントを使う場合）

**Architect / Reviewer**: ADRとモジュール境界、APIの安定性、ライセンス・リスクを審査し、直接巨大機能を書かない。

**Android UI Agent**: Compose、WindowManager、アクセシビリティ、スクリーンショットテストを担当。ネイティブ色計算には触れない。

**Image Engine Agent**: RAW、ICC、タイル、CPU/GPU、品質テストを担当。Kotlin画面に描画アルゴリズムを埋め込まない。

**Data Safety Agent**: DB履歴、SAF、ストレージ権限、ExportJob復旧を担当。原本無変更を反証的に検証する。

**QA / Fuzz Agent**: 危険なファイル、色空間golden、画面サイズ、Perfetto結果、クラッシュを独立検証する。

**Coordinator**: 機能のスコープを小さいPRに分割し、担当を並列化。色/HDR/永続化のAPIを変える前にADR審査を依頼する。

## 7. OpenCodeでの実際の使い方

以下のようにプロジェクトルートへ配置し、OpenCodeをそのルートで起動する:

```text
project/
├── AGENTS.md
├── .opencode/
│   └── skills/
│       ├── prism-raw-pipeline/SKILL.md
│       ├── prism-color-hdr/SKILL.md
│       ├── prism-render-tiles/SKILL.md
│       ├── prism-preset-interop/SKILL.md
│       ├── prism-adaptive-editor/SKILL.md
│       ├── prism-data-safety/SKILL.md
│       └── prism-quality-gates/SKILL.md
├── docs/
│   ├── 01_製品仕様書.md
│   ├── 02_実装定義書.md
│   └── 03_ADR_設計判断記録.md
└── ...
```

提案プロンプト例:

```text
AGENTS.md、製品仕様書、実装定義書、ADRを確認。
prism-raw-pipeline と prism-quality-gates を使用して、
LibRawのヘッダー検査とサムネイル抽出の最小機能を作成する。
まず変更ファイル・危険な入力ケース・テストと測定手順を提案し、
承認後に実装。RAW対応・画質・速度を推測だけで成功宣言しない。
```

## 8. 維持運用
- 週次: Android公式Skillの更新確認、ネイティブ依存の脆弱性情報確認。
- リリースごと: 各Skillの`description`重複/誤起動を点検。
- 四半期: official Android SDK/AGP/Compose/NDK更新時にSkill参照資料も点検。
- すべてのSkillはこの納品に収録。サードパーティSkillは調査のみで**コードのコピーや未検証スクリプトの同梱はしていない**。
