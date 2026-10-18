// P2: フレーム深沉計測（NFR-05/S プリ寸判定の足場）。
// FrameTimingMetric: Editor 画面のスライダードラッグ中のフレーム CPU 時間を計測。
// 測定条件（実測時に記録）: TB710FU / benchmark buildType（release 相当・R8無効）・
// ライブラリ 3 件・露出スライダー行を横方向ドラッグ。
// ライブラリ内スクロール（旧方式）は LazyVerticalGrid 件数不足でフレーム 0 になるため
// frameDurationCpuMs が得られなかった（実機実証・2026-10-18）。
// 注意: 実行を保証しない。数値は connectedAndroidTest 出力後に nfr_catalog.md へ転記する。
package com.imageshot.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Ignore
// Ignore 理由: FrameTimingMetric が本端末(TB710FU/API36/benchmark build)で 0 フレーム採取され
// 「At least one result is necessary, 0 found」で失敗(実機実証・2026-10-18)。gfxinfo は正常に
// フレーム統計を出力するため、計測自体は benchmark/run_frametiming_adb.sh(gfxinfo 方式)で行う。
// 再検討条件: androidx.benchmark 新SDK での直FIX or debuggable 検証。
class FrameTimingBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val targetPackage = "com.imageshot.app"

    @Test
    fun editorSliderDrag() = benchmarkRule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
            // ライブラリから既定アセット(test_photo.jpg)を開き、エディタへ到達。
            // 注: 実測は gfxinfo 経路（run_frametiming_adb.sh）で行っており本テストは @Ignore。
            // 再検討時に使う場合の前提は「ライブラリに test_photo.jpg が1件以上あること」。
        },
    ) {
        // 基本調整タブの露出(EV)スライダー行(実機座標: MV Scale 3200x2000, y≈1425)を
        // 左端→右端へドラッグし終端で確定（フレーム生成を保証）。3往復。
        val h = device.displayHeight
        val w = device.displayWidth
        repeat(3) {
            device.swipe(w / 6, h * 1420 / 2000, w * 9 / 10, h * 1420 / 2000, 48)
            device.waitForIdle()
            device.swipe(w * 9 / 10, h * 1420 / 2000, w / 6, h * 1420 / 2000, 48)
            device.waitForIdle()
        }
    }
}
