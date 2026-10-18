package com.imageshot.core.model

/**
 * 確定済み編集イベント（ADR-007: append-only 永続化）。
 * - ドラッグ中のプレビュー値はここに入らない（onDragEnd で正規化した1件のみ確定）。
 * - Undo は履歴カーソル/新規イベントで表現し、既存イベントを削除しない。
 */
@kotlinx.serialization.Serializable
data class EditEvent(
    val documentId: String,
    /** 1から始まる単調増加の改訂番号。 */
    val revisionNo: Long,
    val eventType: String,
    /** 正規化済みイベント内容。ペイロードのスキーマは eventType ごとに固定。 */
    val payload: kotlinx.serialization.json.JsonObject,
    val committedAt: Long,
)

/**
 * 旧雛形世代（M0）の eventType エイリアス。
 * すべてのスライダー調整を eventType="param" で保存していた旧形式。再生時は payload の
 * "path" から AdjustmentId.kind で種別を復元する（解決の正本は永続層 EditStore）。
 * 新規書込みには使わない。
 */
const val LEGACY_EVENT_TYPE_PARAM = "param"

/**
 * イベント種別の正本列挙（ADR-022、01_製品仕様書.md §5.1–5.3 / 02_実装定義書.md §3.3 の用語順）。
 *
 * - `id` は EditRevision.eventType 列に書込む安定型ID（英語・永続形式）。将来の形式変更でも
 *   id を再利用しない。
 * - IMPLEMENTED_M1: tone/wb/color（スライダー）+ curve（全体/master カーブ）+ hsl（8系統）+
 *   geometry（90°回転/反転/プリセット・クロップ）。detail は RESERVED_M2、local は RESERVED_M3。
 *   RESERVED は形状未確定であり、適用も解釈もしない（PRESERVED_ONLY、§7.1）。
 * - payload 形式は保存列側で `payloadSchemaVersion` を伴う JSON で保存する（ADR-022 確定）。
 */
enum class EditEventKind(
    /** EditRevision.eventType に書込む安定型ID。 */
    val id: String,
    /** 01_製品仕様書 §5 の正本日本語名（UI とは別の仕様書語彙）。 */
    val label: String,
    val status: EditEventStatus,
    /** payload 形状スキーマ確定済みの種別のバージョン。未確定（RESERVED）は null。 */
    val payloadShapeSchemaVersion: Int?,
) {
    /** 基本調整スライダー群（AdjustmentId の tone 系: 露出/コントラスト/ハイライト/シャドウ/白レベル/黒レベル）。 */
    TONE(
        "tone",
        "基本調整: 露出/コントラスト/ハイライト/シャドウ/白レベル/黒レベル",
        EditEventStatus.IMPLEMENTED_M1,
        PAYLOAD_SHAPE_SCHEMA_VERSION_1,
    ),

    /** ホワイトバランス（温度/色合い）。（※現行レンダラーは色合いを未適用 — 既知の限界。） */
    WHITE_BALANCE(
        "wb",
        "ホワイトバランス: 温度/色合い",
        EditEventStatus.IMPLEMENTED_M1,
        PAYLOAD_SHAPE_SCHEMA_VERSION_1,
    ),

    /** 彩度系スライダー（自然な彩度/彩度）。 */
    COLOR(
        "color",
        "彩度系: 自然な彩度/彩度",
        EditEventStatus.IMPLEMENTED_M1,
        PAYLOAD_SHAPE_SCHEMA_VERSION_1,
    ),

    /**
     * トーンカーブ（全体/master カーブのみ）。
     * payload 形状 v1: `{"curve":{"rgb":[[x,y]...]},"op":...}`。
     * R/G/B チャンネル別カーブは M1 では実装しない（保存形式だけ存在・適用対象外、RESERVED）。
     */
    CURVE(
        "curve",
        "トーンカーブ: RGB+R/G/B",
        EditEventStatus.IMPLEMENTED_M1,
        PAYLOAD_SHAPE_SCHEMA_VERSION_1,
    ),

    /**
     * HSL/カラーミキサー（標準8系統×色相/彩度/輝度）。
     * payload 形状 v1: スライダー共通形式 `{"path":"develop.color.hsl.<band>.<param>","value","op"}`。
     */
    HSL(
        "hsl",
        "HSL/カラーミキサー: 標準8系統×色相/彩度/輝度",
        EditEventStatus.IMPLEMENTED_M1,
        PAYLOAD_SHAPE_SCHEMA_VERSION_1,
    ),

    /** ディテール（シャープ/テクスチャ/明瞭度/かすみ/ノイズ軽減等）。payload 未設計。 */
    DETAIL(
        "detail",
        "ディテール: シャープ/テクスチャ/明瞭度/かすみ/ノイズ軽減等",
        EditEventStatus.RESERVED_M2,
        null,
    ),

    /**
     * ジオメトリ。M1 実装範囲: 90°刻み回転 / 水平・垂直反転 / アスペクト比プリセット・
     * センター基準のクロップ。payload 形状 v1:
     * `{"geometry":{"angleDeg","mirrorX","mirrorY","crop":[x0,y0,x1,y1]},"op":...}`
     * （絶対状態スナップショット: undo/redo は payload を前状態へ差し替えて表現）。
     * 自由角度（傾き補正）・透視・光学補正・自由クロップ（エッジドラッグ）は未実装。
     */
    GEOMETRY(
        "geometry",
        "ジオメトリ: 回転/反転/トリミング/傾き/透視（+光学補正）",
        EditEventStatus.IMPLEMENTED_M1,
        PAYLOAD_SHAPE_SCHEMA_VERSION_1,
    ),

    /** 局所補正（マスク）。payload 未設計（M3 対応予定）。 */
    LOCAL(
        "local",
        "局所補正（マスク）",
        EditEventStatus.RESERVED_M3,
        null,
    ),
    ;

    companion object {
        /**
         * eventType 文字列からの逆引き。未知IDは null（再生時は PRESERVED_ONLY で保持する、§7.1）。
         */
        fun byId(id: String): EditEventKind? = entries.firstOrNull { it.id == id }
    }
}

/** パラメータ系 payload（`{"path","value","op"}`）形状のバージョン定数。既定の第1版。 */
const val PAYLOAD_SHAPE_SCHEMA_VERSION_1 = 1

/**
 * 実装状態（ADR-022）。RESERVED は列挙を閉じた保存用の種別であり、該当世代では生成しない。
 */
enum class EditEventStatus {
    /** M1 で「UI→イベント→保存→再生→レシピ→描画」の経路が接続済み。 */
    IMPLEMENTED_M1,

    /** M2 対応予定。実装時に payload 形状を確定する。 */
    RESERVED_M2,

    /** M3 対応予定。 */
    RESERVED_M3,
}

/**
 * レシピ全体スナップショット（20–50イベントごとに作成、§3.3）。
 * 起動時は最終スナップショットに、正しいチェックサムのイベントだけを再生する。
 */
@kotlinx.serialization.Serializable
data class EditSnapshot(
    val documentId: String,
    val revisionNo: Long,
    val recipe: EditRecipe,
    /** スナップショット整合性検証用のチェックサム。 */
    val checksum: String,
)

/** 編集文書（Asset 1:1）。currentRevision はトランザクションで更新する。 */
@kotlinx.serialization.Serializable
data class EditDocument(
    val id: String,
    val assetId: String,
    val schemaVersion: Int = RecipeSchema.CURRENT,
    val currentRevision: Long,
    val sourceFingerprint: String,
    val lastCommittedAt: Long,
    val checksum: String,
)
