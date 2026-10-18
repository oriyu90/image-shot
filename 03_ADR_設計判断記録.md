# Prism Photo — Architecture Decision Records（ADR）

文書ID: PRISM-ADR-001 / 2026-10-18 / v1.0

ADRは「背景→判断→代替案→効果・代償→検証方法→再検討条件」の書式をとる。`Accepted` は本設計上の暫定採用であり、実装実測・法務確認を終えた意味ではない。

## ADR-001 — Android 16以降を下限とする
- **状態**: Accepted
- **背景**: 古いOSへの対応を維持すると画像/HDR API・権限・テストの分岐が増える。
- **判断**: `minSdk=36`。ビルドSDKとtargetSdkは出荷時に検証済み安定版（現時点では37候補）へ上げる。大画面の向きとリサイズ制約に依存しない。
- **代替**: minSdk=26や34で対象端末を増やす／API36固定。
- **代償**: 利用可能端末数の減少。OSアップデートごとにwindow/camera/HDRの差分検証が必要。
- **検証**: 36/37の実機とエミュレーター、折りたたみ、マルチウィンドウ回帰。
- **再検討**: サポート端末分布が大幅に変化した場合。

## ADR-002 — Compose UIと独立ネイティブ画像エンジン
- **状態**: Accepted
- **背景**: UIの適応性と16-bit RAW処理に求められる技術が異なる。
- **判断**: UI/永続化はKotlin+Compose、画像演算はC++ NDK。JNI境界は少数の安定APIに絞り、バッファ転送を最小化する。
- **代替**: Kotlin/Bitmapのみ、Flutter全面採用、Rust全面採用。
- **代償**: NDK/メモリ安全/ABI管理が複雑。代わりに画像エンジンの長期再利用と高負荷処理最適化が可能。
- **検証**: JNI allocation、トレース、native unit test、CPUメモリ安全検証。
- **再検討**: Rust採用で境界の保守負荷が十分減り、移行による画質差が許容できる場合。

## ADR-003 — GPUはGLES先行、CPUを必須fallback
- **状態**: Provisional
- **背景**: Vulkanは制御力が高いが端末実装差、初期開発コストが大きい。
- **判断**: GLES 3.1を第一候補とし、必要な浮動小数点レンダーターゲットとHDRサーフェス能力を起動時probe。未対応ならCPUへ降格。UI層はバックエンド非依存。
- **代替**: Vulkan先行 / CPUのみ / RenderScript（新規用途として不採用）。
- **代償**: 細粒度のGPU資源制御、計算性能ではVulkanの利点を取り切れない可能性。
- **検証**: Snapdragon/Exynos/Dimensity系、Mali/Adreno系で色差・フレーム・Context lossを比較。
- **再検討**: 大半の出荷端末でVulkanによる確実な改善が得られた場合。

## ADR-004 — RAW読込と現像処理を抽象化
- **状態**: Accepted (implementation spike required)
- **背景**: カメラごとの圧縮/センサー特性が多様。デコーダーに現像品質まですべて依存すると拡張が難しい。
- **判断**: LibRawを初期候補とし、`RawDecoder`契約で読み込みとデモザイク/色変換を分離する。RawSpeedはベンチマーク比較対象。
- **代替**: RawSpeed専用の完全自作現像系、各メーカーSDKの集約。
- **代償**: 対応カメラ差、RAWプレビューとの差、ライセンス管理。モデル別テストコーパスが必要。
- **検証**: 主要機材のRAWごとに圧縮方式/演算結果/ピークメモリ/エラーを集計。
- **再検討**: LibRawが未対応機材・処理速度・ライセンス上の重大制限をもたらす場合。

## ADR-005 — 色演算の正本はシーンリニア広色域
- **状態**: Accepted
- **背景**: 8-bit sRGBで繰り返し処理すると階調が壊れ、RAWの広い情報を損なう。
- **判断**: `Linear Rec.2020, D65` を基準とするfloatパイプライン。ICC v2/v4変換はLittle CMS、色順応・ガマットマッピングは明示ノード。
- **代替**: 8-bit sRGB全面処理、P3だけを作業空間に採用。
- **代償**: RAM/GPUメモリ負担、知覚空間演算での変換コスト。
- **検証**: ColorChecker、ICC往復、10/12/16bit勾配、負値と範囲外入力のテスト。
- **再検討**: 可視品質を保ちつつ低メモリの別精度を一貫して採用できる場合。

## ADR-006 — Gainmapを独立したデータとして管理
- **状態**: Accepted
- **背景**: 基底画像だけを変更してGainmapを古いまま維持するとHDR表示時に輝度不一致が生じる。
- **判断**: HDR基底画像、gain map、転送特性を分離し、編集結果と同期して必要に応じ再生成。未対応端末はSDRトーンマップの正しい表示を優先。
- **代替**: HDR画像を常にSDRに変換／常に既存Gainmapを再利用。
- **代償**: 高度編集時の出力フォーマット実装、端末別HDRキャパビリティ検査。
- **検証**: Android公式のUltra HDR編集ガイダンスに沿う比較、輝度テスト、JPEG/HEIC gainmapの端末別roundtrip。
- **再検討**: OSが色空間/トーンマップ付きHDRレンダリングの安定した高水準APIを提供した場合。

## ADR-007 — 非破壊レシピはイベント永続化
- **状態**: Accepted
- **背景**: 頻繁なスライダー変更と突然の終了がある。巨大画像の再保存では安全性が保証できない。
- **判断**: 原本不変、編集イベントappend-only、Room WAL transaction、定期snapshot。Undoは履歴カーソル/イベントで表現。
- **代替**: 全画像の再エンコード、編集毎に巨大JSON上書き、直近のUI状態だけ保持。
- **代償**: Schema migration、スナップショット正当性、取り消し分岐の設計。
- **検証**: ランダムkill注入、500回再起動、DB upgrade/downgradeポリシーと破損復旧。
- **再検討**: 復旧容易な同等以上の永続ストアが確認された場合。

## ADR-008 — 外部設定は変換レイヤと部分一致ステータスを備える
- **状態**: Accepted
- **背景**: XMP・旧形式・DNGメタデータには公開規格と処理系固有の設定が混在し、単純なキー変換で画像が同一になるとは限らない。
- **判断**: 入力形式別パーサ→検証→中間表現→個別変換→適用結果を出す。EXACT/APPROXIMATE/PRESERVED/UNSUPPORTED/INVALIDを区別。UIには一般的な「プリセット読み込み」を表示。
- **代替**: 直接XMPを内部設定JSONとして扱う、任意フィールドは黙って無視する。
- **代償**: パラメータマッピングが長期保守対象になる。高度なプロファイル/マスクの完全再現はできないケースが残る。
- **検証**: 許諾済み外部設定コーパスの解析率、見た目差、壊れたデータ、未知field preservation。
- **再検討**: 外部フォーマットの公開仕様変更や入力件数の実測。

## ADR-009 — ライブラリ管理はSAF/Photo Picker、全写真複製なし
- **状態**: Accepted
- **背景**: 原本を大量複製すると保存容量と取り込み時間が増え、権限リスクも高い。
- **判断**: URI参照+永続権限+選択的キャッシュ。必要なRAWだけprivate stagingへコピーし、完了後掃除。
- **代替**: 全メディア一括コピー、広範なストレージ権限要求。
- **代償**: URI失効、低速クラウドProvider、ランダムアクセス不可への対応が必要。
- **検証**: Local DocumentsProvider/remote provider/USB/missing permissions/巨大ファイル。
- **再検討**: 原本保全のためユーザーが明示的にローカルコピーを選べる機能を加える場合。

## ADR-010 — 書き出しを再開可能ジョブとして設計
- **状態**: Accepted
- **背景**: 大容量TIFF/JXLエンコード中にアプリが中断し得る。
- **判断**: DB永続ExportJob + staged output + VERIFYING/PUBLISHING + WorkManager等のOS適合ジョブ方式。MediaStoreの`IS_PENDING`を活用。
- **代替**: ActivityのCoroutineだけで保存、直接最終ファイルに書き込む。
- **代償**: provider制約により完全な原子性は一般には保証できないため、半端な公開物の後処理が必要。
- **検証**: ストレージ不足、kill、強制再起動、失効URI、重複ファイル名で故障注入。
- **再検討**: 新しいOS標準の長時間データ転送Job APIが要件に優れる場合。

## ADR-011 — マルチ解像度タイルと動的品質
- **状態**: Accepted
- **背景**: 48MPのRGBA16F 1バッファが約384MB。複数バッファを保持すると一般的な端末でOOMとなる。
- **判断**: tiled DAG evaluation、可視領域優先、階層化プレビュー、モバイルのGPU/CPUメモリ別LRU。
- **代替**: 全画像フル解像度の毎フレーム再計算、低解像度だけで書き出す。
- **代償**: tile seam/halo/キャッシュ無効化の実装が複雑。
- **検証**: 画像端の連続性、Mask境界、4GB RAM端末、システムtrim、100MP入力。
- **再検討**: ドライバと端末上で効率のよいテクスチャ/virtual memory方式が確認できた場合。

## ADR-012 — 軽量化は段階配信と測定で実現する
- **状態**: Accepted
- **背景**: RAW/各画像形式/AI一式を静的同梱するとAPKが巨大化する。
- **判断**: 最低限の編集機能はbase module、JXL/EXR/高度AIはoptional。ABI splits、不要リソース排除、R8、遅延ロードを行う。
- **代替**: 単一すべて入りバイナリ、初期版からクラウド処理へ外出し。
- **代償**: オプション形式の初回導入にはネットワークが必要になる場合がある。オフライン利用向けに追加モジュールを事前取得できるUIを用意。
- **検証**: Play配信の実際のdownload bytes、ネイティブシンボル、ABIごとのサイズ、初期ロード時間。
- **再検討**: オプション利用率が高く初回体験悪化が明らかな場合。

## ADR-013 — 高度AIは基礎編集と別モジュール
- **状態**: Accepted
- **背景**: 大型モデルはアプリサイズ・RAM・熱・ライセンスのリスクが高い。
- **判断**: 初期版には必須とせず、将来のマスク/ノイズ軽減機能を`AiFeature`インターフェース下に隔離。結果はレシピに再現用のモデル識別子/キャッシュ参照を保存。
- **代替**: 最初から端末間で同一の大型モデルを必須配布、画像の外部アップロードを既定にする。
- **代償**: 初期版の自動編集機能は限定されるが、安定性とオフライン性能を優先。
- **検証**: RAM、発熱、デバイス別NN推論、オフライン再現性と権利確認。
- **再検討**: 軽量モデルが十分な品質と端末普及率に達した場合。

## ADR-014 — 音のない障害より説明可能な機能縮退を優先
- **状態**: Accepted
- **背景**: エラー隠蔽は不正な色、勝手なSDR変換、途中までの保存を成功扱いさせる。
- **判断**: `Result`型でエラーを分類し、CPU fallback / preview-only / SDR出力/URI再取得を明示的に提示する。
- **代替**: 失敗時に自動的にデフォルトを使い無通知で続行。
- **代償**: エラーメッセージと状態管理は増える。
- **検証**: 主要エラーコードとUI復旧導線、障害復帰E2E。
- **再検討**: 自動回復が画質/整合性を失わず可能になった場合。

## ADR-015 — フォーマットプラグインのライセンスと入力安全性を先行審査
- **状態**: Accepted
- **背景**: 画像デコーダーは非信頼入力を扱い、異なるライセンスを持つ多数のライブラリが関わる。
- **判断**: 各ライブラリの選択ライセンス/Notice/サブ依存/脆弱性情報/更新方法を`third_party.lock`に固定。パーサFuzz、ネイティブHWASan、入力上限とtimeoutを出荷ゲートにする。
- **代替**: 公開GitHubライブラリの無審査コピペ、すべてのデコーダーを自作。
- **代償**: 導入の時間はかかるが、法的・セキュリティ・保守面の事故を予防。
- **検証**: SBOM、ライセンス監査、Fuzz実績、脆弱性監視、ビルド再現。
- **再検討**: より安全で低負荷な標準OSのコーデックが安定提供された場合。

## ADR-016 — 特定の既存製品に依存しない製品表現とUX
- **状態**: Accepted
- **背景**: 写真編集の操作体系を参考にしても、製品の独立性とUI・商標・素材の権利保護が必要。
- **判断**: 情報設計・アイコン・文言・配色・コンポーネントを独自設計する。外部形式インポートを一般的機能名として示し、承認・提携・完全互換の主張を行わない。
- **代替**: 他製品の画面構成/資産の逐語的複製、宣伝に互換保証を使用。
- **代償**: 独自のUX検証コスト。基準となる操作効率はタスクベースのユーザーテストで評価する。
- **検証**: 最初の写真編集に至る操作回数、アクセシビリティ、意匠レビュー、文言監査。
- **再検討**: 法的・製品上のレビューで不足が見つかった場合。

## ADR-017 — クラッシュ/プライバシー指標の分離
- **状態**: Accepted
- **背景**: 写真アプリではデバッグ情報に個人情報が入りやすい。
- **判断**: 性能/障害イベントは写真の内容を含まない匿名統計のみ。詳細診断はオプトイン。オフラインでも完全動作する。
- **代替**: 常時ログ転送、画像とRAWを自動アップロードして原因分析。
- **代償**: 原因追跡の一部が難しくなる。
- **検証**: HTTPリクエスト監査、写真名/パス/GPSのフィールド自動検知、権限検査。
- **再検討**: ユーザー主導の診断添付機能を追加する場合。

## ADR-018 — DI は手動コンテナ（Hilt不採用・雛形段階）
- **状態**: Provisional
- **背景**: Sprint A〜C の原型構築でビルド要件（KSP/kapt追加）を減らし検証速度を上げる。仕様書§1はDI構成を強制していない。
- **判断**: `AppContainer` + 手動Factoryで統一。Hiltへの移植は構造（feature→core依存方向）を壊さない形で可能なまま保つ。
- **代償**: 依存グラフの静的検査・テスト置換の利便が失われる。
- **検証**: feature全モジュールのビルド通過、ViewModelテストでの手動注入。
- **再検討**: モジュール数が増え、注入ミスが検出困難になった場合。

## ADR-019 — ナビゲーションは安定版 navigation-compose
- **状態**: Accepted (provisional)
- **背景**: Navigation 3は新世代APIだが本構築時点で安定性と学習コストの評価が未完。
- **判断**: `androidx.navigation:navigation-compose`を採用。仕様書§4.2のレベントはWindowSizeClass側で吸収し、ナビゲーション構造に持ち込まない。
- **代替**: Navigation 3の即時採用。
- **代償**: 誌面 (adaptive list-detail) の2nd pane遷移をNav3が提供するScenesで置き換える将来の移行作業。
- **検証**: library→editor→exportの戻る挙動、プロセス再作成後の状態回復。
- **再検討**: Navigation 3安定版との差分確認後。

## ADR-020 — レシピpayloadはJSON+schemaVersionで暫定保存
- **状態**: Provisional
- **背景**: 02_実装定義書§3.2はCBOR/Proto相当のバイナリを「許容」とするが、雛形段階での安定CBOR実装（kotlinx-serialization-cbor実験版）はschema互換性の検証不足。
- **判断**: RoomにはJSON文字列+`schemaVersion`を保存。読み出しはstrictな既知field検証+unknownExtensions隔離（§3.2正規構造と同一）。
- **代替**: 最初からCBOR/Proto。
- **代償**: payloadは参照人可読のため改変リスク増加（checksum列で遮蔽、§3.1）。サイズ増。
- **検証**: schema evolutionテスト（旧版読み出し→移行→再保存、§3.2必須）。
- **再検討**: CBOR移行時はdouble encode/decode reference testを行ってから。

## ADR-021 — Room の SQLiteDriver（BundledSQLiteDriver）採用を見送る
- **状態**: Accepted（採用見送りの判断として確定。実機での障害実証込み）
- **背景**: Room 2.8 系では SQLiteDriver API が正本経路で、未指定時は端末 framework SQLite（classNames の AAR 依存）に暗黙依存する。ADR-007 の WAL/トランザクション要件を端末差で揺らさないため、SQLite 実装を APK 同梱で固定する候補が検討された（ImageShotDatabase の TODO）。試作 PR で `setDriver(BundledSQLiteDriver())`（androidx.sqlite:sqlite-bundled 2.6.2）を実装した。
- **判断**: **採用せず、既定の framework factory 経路を維持する。** 試作を端末（TB710FU, API 36）に向けたところ、EditStore の確定操作が使う `androidx.room.withTransaction`（room-ktx, Room 2.8.5 の Android 実装）が `RoomDatabase.getOpenHelper()` で SupportSQLiteOpenHelper(.Factory) を要求し、setDriver 版は
  `IllegalStateException: Cannot return a SupportSQLiteOpenHelper since no SupportSQLiteOpenHelper.Factory was configured with Room.`
  （2026-10-18 実機クラッシュ、ジオメトリ確定操作で再現）で実運用不能と実証された。setDriver 併存でも room-ktx の withTransaction Android 実装が driver 経路に接続しないことが根拠。`JournalMode.WRITE_AHEAD_LOGGING` とトランザクション検証は既定経路で維持する。
- **代替**: EditStore の確定操作を driver 経路の SQLiteConnection API で自前トランザクション化（不採用: Room DAC 検証済み経路を外れ、commit 原子性の再実装規模が大きい）。"param" → 未指定のまま framework に委ねる（採用: 本判断）。
- **代償**: SQLite 実装は端末 framework 品質に依存する（API 36 専用 minSdk のため差異リスクは小さい但し非零）。DB ポータビリティ（JVM テスト実体・他プラットフォーム）は当分得られない。
- **テスト**: desktop JVM のユニットテストで Room 実体を開く経路は本モジュール構成では成立しないことを確認済み（① Room の android 変体に Context 無しの builder 無し、② jvm 変体への属性切替は自モジュール投影と不整合）。DAO・WAL・スキーマ検証は既存の実機検証と将来の instrumented androidTest で担保。
- **再検討条件**: Room の後継 SDK で withTransaction が SQLiteDriver 経路を正規サポートした場合。実機での framework SQLite の WAL/整合問題が実測された場合。

## ADR-022 — EditEvent 正本列挙と payload 永続形式の確定（ADR-020 を暫定から確定へ更新）
- **状態**: Accepted
- **背景**: 01_製品仕様書 §5.1–5.3 の操作群（基本調整/ジオメトリ・光学/局所補正）と 02 §3.3 改訂イベント。M0 雛形では全スライダー調整を `eventType="param"` で雑に保存していたため、種別の正本が不在で、将来の種別追加時に未知行の取り扱いが未定だった。ADR-020 は JSON+schemaVersion を「暫定」保存としていた。
- **判断**:
  1. **種別正本列挙**: `EditEventKind`（英語安定ID・閉じた列挙・新規IDの再利用禁止）を導入する。M1 実装済み（IMPLEMENTED_M1）: `tone`（露出/コントラスト/ハイライト/シャドウ/白レベル/黒レベル）、`wb`（温度/色合い）、`color`（自然な彩度/彩度）、`curve`（master トーンカーブ 2..16点・単調。R/G/B チャンネル別カーブは M1 不適用・保存は規定済み）、`hsl`（8系統×色相/彩度/明るさ）、`geometry`（90°単位回転/水平・垂直反転/アスペクト比プリセット中央寄せクロップ。自由角度・自由クロップ・透視・光学補正は未実装）。`detail` は RESERVED_M2、`local`（マスク）は RESERVED_M3 — RESERVED は形状未確定であり生成も解釈もしない。eventType は payload の path から `AdjustmentId.kind` で一意に導出する。
  1b. **追加 payload 形状（v1・追記互換）**: `curve` は `{"curve":{"rgb":[[x,y],...]}, "op":"apply"|"undo"}`（絶対状態ノード方式）、`geometry` は `{"geometry":{"angleDeg":0|90|180|270,"mirrorX":bool,"mirrorY":bool,"crop":[l,t,r,b]}, "op":...}`（crop は回転・反転後の正則方位の正規化座標）、`hsl` は既存スライダー標準 payload（`develop.color.hsl.<band>.<param>`、value −1..1）を共用する。適用順の契約: 幾何補正は develop 前に適用（develop の per-pixel 処理は座標非依換のため可換）、カーブは per-LUT のスライダー map 後・HSL はカーブ後に適用する（Core/render `RecipeDevelopApplier` KDoc 正本）。
  2. **未知イベントの処理**: 未知種別・RESERVED 種・未知パス・種別と path のドメイン不整合は **PRESERVED_ONLY** — 行は無改写、履歴には表示するが、適用せず既定値に落とさない（§7.1 哲学のレシピへの拡張）。適用/保持/未対応/破損の集計は `EditDecodeSummary`（total/applied/preservedOnly/unsupportedVersion/invalid）として state に明示する。チェックサム不一致と JSON 破損は §3.3 どおり再生から除外し `invalid` に数える。
  3. **schemaVersion 契約**: payload の `payloadSchemaVersion` が未対応（現在 > 1）の行は同様に保持のみ（`unsupportedVersion`）。codec は未対応バージョンで既定値へフォールバックせず明示失敗する。旧雛形の `"param"` は読み込み互換エイリアスとして path から復元し、新規書込みには使わない。
  4. **payload 永続形式**: JSON + schemaVersion を **DORMANT な恒久形式として確定**（ADR-020 の本文は変更せず、本ADRで確定性を引き受け、ADR-020 の 再検討条件 を引き継ぐ）。CBOR/Proto への移行は「payload 平均 >16KiB の実測」もしくは「schema 自由度需要の実測」が出た場合のみ、かつ double encode/decode reference test を実装した後でのみ行う。
- **代替**: CBOR/Proto 即移行／未知種別を既定値やスキップで無音に続行（不採用: 無通知画質破綻の源、ADR-014 に反する）。
- **代償**: 未知行が DB に残り続ける（展示/集計で訴求する運用に依存）。payload が人可読なため改変リスクは checksum 遮蔽（§3.1）だけが防衛。種別IDの改称・再番号は永続データとの互換を壊す。
- **検証**: EditStoreTest に追加: 正本ID書込み（tone/color/wb）、未知種別の保持+集計+連続確定、RESERVED 種の保持、未対応 schemaVersion の保持+codec 失敗、旧 "param" エイリアス適用。M1 で `wb` の tint はレシピ保存・UI・イベント経路は有効だが、現行レンダラーは tint を未適用（既知の機能限界、ADR-014 により無通知にしない）。P4 反映（2026-10-18）: curve/hsl/geometry 実装済み化、payload 形状追記、新種別の保存→decode 往復・undo/redo・レンダリング適用は core:render/database の JVM テスト（計25件追加分を含め緑）と実機検証（回転90°→書き出し: 出力 900×1200・develop 適用確認）で機能。
- **再検討条件**: payload 平均サイズの実測、kotlinx-serialization-cbor 安定版の継続実測、外部プリセット映射（§7）で保存フィールド数が実測的に拡大した場合。

## 変更管理

1. ADRを更新する場合、元判断を消さず`Superseded by ADR-xxx`を記録する。
2. 変更を引き起こした測定結果（画像・端末・ビルド・数値・ライセンス判断）をPRに添付。
3. 原本/色/HDR/永続化に関する決定変更は、実装前に最小試作とgolden testを必須とする。
4. カメラRAW対応追加は「拡張子の増加」ではなく機種/圧縮方式の対応実測を根拠にする。
