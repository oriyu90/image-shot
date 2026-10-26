package com.imageshot.core.render

import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.EditNodeSpecs

/**
 * 書き出し時の gainmap (Ultra HDR / ISO 21496-1) 取扱い policy 分岐（ADR-026 stage-1）。
 *
 * 【ADR-026 採用判断（案 B・条件付き pass-through）】
 * - 乗算型 tone 編集のみ（露出 EV / WB 温度利得 / tint 利得）で、かつジオメトリ変換が
 *   実行されていない場合: 源画像の gainmap を**未修正のまま**引き継ぐ（pass-through）。
 *   [実測] PR-22 spike: 全画素 1.27 乗算+gainmap 未修正の compress でマーカ XMP/hdrgm/
 *   ISO 21496/MPF 全存続・再 decode で諸元完全保全（TB710FU / API 36）。
 * - それ以外（コントラスト等の非乗算 LUT・master カーブ・彩度系・HSL・ジオメトリ変換・
 *   非JPEG 出力）は SDR-only（gainmap 剝離・platform compress が全マーカを出力から
 *   消すことを [実測] spike が確認済み）。
 *
 * 【分類の基準 = 実際に画素へ適用される変換の集合】
 * 現行パイプラインで**画素に全く適用されない**ノード（ディテール・レンズ/透視・
 * 局所マスク）は画素内容を変えないため判定に含めない（ADR-014 の unappliedNodeWarnings が
 * 欠落を告知する役割を分担）。将来これらのノードが実装された際は、本分類と
 * [RecipeDevelopApplier]/[RecipeGeometry] の適用集合を必ず同時に更新すること
 * （乖離すると warning の真実性が崩れる）。R/G/B チャンネルカーブは PR-28a で画素適用を
 * 開始した（master 直後・sRGB 表示値ドメイン）ため、非中立のときは本分類で不成立条件に昇格済み。
 * 自由角度回転は PR-28b で画素適用を開始した（リサンプル=非乗算変換）ため、
 * geometry 非中立の判定（[EditNodeSpecs.isNeutralGeometry]）で不成立になる。
 *
 * ジオメトリ・orientation を pass-through の不成立条件とする理由: gainmap content は本実装では
 * 回転/反転/クロップ/正立化の同時再索引を行わないため、座標割れが HDR 表示の不整合になる
 * （回転/反転/クロップは spike 未到達、正立化〔decode 結合〕は platform 側挙動が実測未取得。
 * 理論上の整合を取るのが stage-2）。
 *
 * 警告文言（ADR-026 確定文言・逐語）:
 * - pass-through:  "HDR 出力の gainmap はもとのまま（露出/WB 編集の HDR 側への反映は未対応）"
 * - 剝離:          "HDR 高輝度データはこの書き出しに引き継がない（SDR 出力）"
 */
object GainmapExportPolicy {

    enum class Mode { PASS_THROUGH, STRIP_TO_SDR }

    /** ADR-026 警告文言正本（ADR-014 型・日本語・UI 表示を想定した逐語）。 */
    const val WARNING_PASS_THROUGH: String =
        "HDR 出力の gainmap はもとのまま（露出/WB 編集の HDR 側への反映は未対応）"
    const val WARNING_STRIP_TO_SDR: String =
        "HDR 高輝度データはこの書き出しに引き継がない（SDR 出力）"

    /**
     * 分岐判定（recipe と decode 段で確定済みの画素変換の双方を考慮）。
     * source 側 gainmap の有無は呼出し側（ExportBitmapRenderer）が管理し、
     * 本判定は「その gainmap を引き継いで良いか」のみを返す。
     *
     * - format が "JPEG"（大文字小文字は無視）以外 → STRIP（非圧縮系 PNG の gainmap 併記
     *   encode は未観察。SDR-only で確実に落とす）。
     * - orientationTransformed=true（decode 段の EXIF orientation 正立化〔platform 自動適用
     *   /BitmapFactory 経路の手動正規化。ExportRenderer 側で
     *   `sourceOrientation >= 2 || orientationAppliedByUs` として算出〕）→ STRIP。
     *   理由: 正立化は基底画素の再索引（転置）であり、gainmap content の同時再索引は本実装に
     *   存在しない。platform が gainmap も同時に転置するかは実測未取得のため、未実測領域は
     *   未実測のまま通さない（ADR-026 原則・stage-2 で gainmap 同時再索引を実装後に再開する）。
     */
    fun classify(recipe: EditRecipe, outputFormat: String, orientationTransformed: Boolean): Mode {
        if (orientationTransformed) return Mode.STRIP_TO_SDR
        return classify(recipe, outputFormat)
    }

    /** recipe と format のみでの判定（orientation 変換が無い前提。2 引数版の既定経路）。 */
    fun classify(recipe: EditRecipe, outputFormat: String): Mode {
        if (!outputFormat.equals(FORMAT_JPEG, ignoreCase = true)) return Mode.STRIP_TO_SDR
        return if (isMultiplicativeOnlyPixelOps(recipe)) Mode.PASS_THROUGH else Mode.STRIP_TO_SDR
    }

    /**
     * 画素結合の視点からの「実行された変換が全て乗算型か」。
     *
     * 乗算型に数える（非中立でも成立）: 露出 EV・WB 温度利得（tempGains）・WB tint 利得
     * （tintGains・設計段近似だが利得乗算である点は [RecipeToneCurve.tintGains] どおり）。
     * 不成立にする（非乗算/再索引が画素へ効く）: コントラスト/ハイライト/シャドウ/白/黒レベル
     * （放物線バンプ LUT）・master カーブ（非中立・[RecipeCurve.isNeutral] と同一判定）・
     * R/G/B チャンネルカーブ（非中立・[RecipeCurve.isNeutral] と同一判定・空と恒等点列は通す・
     * PR-28a で画素適用が開始された）・彩度/自然な彩度・HSL（非中立・[RecipeHsl.isNeutral] と
     * 同一判定）・実行されるジオメトリ変換（90°回転/反転/クロップ・
     * [EditNodeSpecs.isNeutralGeometry] と同一判定・不正値は安全側不成立）。
     * 判定しない（画素不変のため）: ディテール・レンズ/透視補正・局所マスク。
     * decode 段の orientation 正立化は [classify] の orientationTransformed 引数で不成立化する。
     */
    private fun isMultiplicativeOnlyPixelOps(recipe: EditRecipe): Boolean {
        val t = recipe.develop.tone
        if (t.contrast != 0.0 || t.highlights != 0.0 || t.shadows != 0.0 ||
            t.whites != 0.0 || t.blacks != 0.0
        ) {
            return false
        }
        // 露出 EV（線形ドメイン乗算）は通す（露出==0 も通る）。WB 温度/tint 利得も同じく
        // 乗算型で通す（tempKelvin/tint は RecipeToneCurve.tempGains/tintGains の利得計算に従い、
        // LUT 内で1回の利得乗算として作用する。mode は見た目用のメタデータで適用挙動に無関係）。
        // master カーブ: 実適用ゲートと同一判定（RecipeCurve.isNeutral）を使う（非中立なら不成立）。
        val masterCurve = recipe.develop.curve.rgb
        if (!RecipeCurve.isNeutral(masterCurve)) return false
        // R/G/B チャンネルカーブ（PR-28a 適用開始）: master と同一判定系。
        // 空配列（未編集）と恒等点列（中立）は画素不変のため通す。
        val curve = recipe.develop.curve
        if (curve.r.isNotEmpty() && !RecipeCurve.isNeutral(curve.r)) return false
        if (curve.g.isNotEmpty() && !RecipeCurve.isNeutral(curve.g)) return false
        if (curve.b.isNotEmpty() && !RecipeCurve.isNeutral(curve.b)) return false
        // 彩度/自然な彩度（画素残差）は非中立なら不成立。
        val c = recipe.develop.color
        if (c.vibrance != 0.0 || c.saturation != 0.0) return false
        // HSL は実適用ゲートと同一判定（RecipeHsl.isNeutral）を使う（非中立なら不成立）。
        if (!RecipeHsl.isNeutral(c.hsl)) return false
        // ジオメトリ: 実行される変換（許可角度回転・反転・クロップ）はいずれも不成立条件。
        // 正規化成功かつ中立（RecipeGeometry.applyIfNeeded が同一 plane 参照で短絡）→ 通す。
        // 正規化失敗（不正角度・不正クロップ等）は applier に未正規化のまま渡る
        // （回転は 0 度落ちで反転/クロップは値のまま実行され得る）→ 内容変化の有無を
        // 本関数から一文で分類できないため不成立（安全側・SDR-only+警告）。
        val g = recipe.geometry
        if (EditNodeSpecs.normalizeGeometry(g) == null || !EditNodeSpecs.isNeutralGeometry(g)) return false
        return true
    }

    private const val FORMAT_JPEG = "JPEG"
}
