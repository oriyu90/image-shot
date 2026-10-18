package com.imageshot.benchmark

import androidx.test.runner.AndroidJUnitRunner

/**
 * androidx.benchmark 1.4.1 の MacrobenchmarkRunner はライブラリ同梱ではなく
 * androidx.benchmark Gradle plugin 生成物のみ。手動 adb install + am instrument 方式では
 * ClassNotFound になる（実機実証・2026-10-18）。本プロジェクトは手動実行のため
 * AndroidJUnitRunner 直継ぎの空 runner を計測 runner として使用する。
 */
@Suppress("unused")
class MacrobenchmarkRunnerStub : AndroidJUnitRunner()
