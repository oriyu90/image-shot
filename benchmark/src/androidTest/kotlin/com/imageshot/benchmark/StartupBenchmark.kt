// P2: コールド起動計測（NFR-03）。
// StartupTimingMetric: TTFD/TTID 時間をレポート。測定条件（実測時に記録すべき事項）:
//   対象端末・ビルド型 benchmark（release 相当・R8無効）・アプリ内ライブラリ件数・モード COLD。
//
// 注意: このファイルは「実行」を保証しない。実測値は connectedAndroidTest 実行後に
// benchmark/nfr_catalog.md へ記入すること（AGENTS.md: 検証していない数値を宣伝しない）。
package com.imageshot.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val targetPackage = "com.imageshot.app"

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = {
            // 強制停止しておき、毎イテレーションでコールドスタートさせる。
            pressHome()
        },
    ) {
        startActivityAndWait()
    }
}
