package com.imageshot.feature.editor

import com.imageshot.core.model.EditRecipe

/**
 * 局所マスク（PR-29c）の UI → [EditorViewModel] 配線を 1 箇所にまとめた束。
 *
 * `EditorScreen` はスライダー/カーブ/ジオメトリと同じく「コールバックを受けて表示するだけ」で、
 * マスクの確定・履歴・レンダー要求の判断は持たない（すべて VM 側）。引数 13 個を
 * 画面シグネチャへ平坦に並べると呼出し側（app）の可読性が落ちるため、束にして渡す。
 *
 * - 各コールバック名と意味は [EditorViewModel] の同名 API に一致する。
 * - 値はレシピドメイン値ではなく **UI 表示値**（adjustments は -100..100 等、
 *   radial の angleDeg は -180..180）。レシピ値への変換は VM が行う。
 */
class EditorMaskActions(
    /** マスク追加（"linear" / "radial" / "brush"）。1 追加 = 1 履歴イベント。 */
    val onAdd: (String) -> Unit,
    /** 選択マスクの切替（null = 非選択）。UI セッション状態で履歴は作らない。 */
    val onSelect: (String?) -> Unit,
    /** 選択マスクの削除（1 削除 = 1 履歴イベント）。 */
    val onRemove: (String) -> Unit,
    /** ドラッグ中のプレビュー（履歴に書かない・DRAFT 再描画のみ）。 */
    val onPreview: (List<EditRecipe.MaskNode>) -> Unit,
    /** ドラッグ終了時の確定（1 操作 = 1 履歴イベント・楽観排他）。 */
    val onCommit: (List<EditRecipe.MaskNode>, String?) -> Unit,
    /** adjustments スライダーのドラッグ中（path・表示値）。 */
    val onAdjustPreview: (String, String, Double) -> Unit,
    /** adjustments スライダーの確定（path・表示値）。 */
    val onAdjustCommit: (String, String, Double) -> Unit,
    /** 選択マスクの adjustments を全消去（= 0 に戻す）。 */
    val onResetAdjustments: (String) -> Unit,
    /** feather / angleDeg スライダーのドラッグ中（key・表示値）。 */
    val onParamPreview: (String, String, Double) -> Unit,
    /** feather / angleDeg スライダーの確定（key・表示値）。 */
    val onParamCommit: (String, String, Double) -> Unit,
    /** brush の 1 点追加プレビュー（ストローク間引きは UI 側）。 */
    val onBrushAddPreview: (String, EditRecipe.BrushStroke) -> Unit,
    /** brush の 1 点確定（ジェスチャ終了時に 1 回 = 1 履歴イベント）。 */
    val onBrushAddCommit: (String, EditRecipe.BrushStroke) -> Unit,
    /** brush の半径（ツール設定・マスク内容ではないため履歴を作らない）。 */
    val onBrushRadius: (Double) -> Unit,
)
