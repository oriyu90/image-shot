// benchmark — P2: 性能計測基盤（Macrobenchmark）。
// android library + androidx.benchmark 構成。APK のインストール/実行は
// `adb install :app の benchmark 型 + :benchmark の androidTest APK` → `am instrument` で行う
// （targetProjectPath/self-instrumenting 自動配線は com.android.test モジュールで
//   向きに依存がhyper細かく変わり実機で SQLiteDatabase 輸入崩壊を経験したため、手動方式に固定）。
plugins {
    alias(libs.plugins.android.library)
    // 注: androidx.benchmark Gradle plugin は testInstrumentationRunner を plugin 既定
    // （androidx.benchmark.macro.junit4.MacrobenchmarkRunner — 同クラスが 1.4.1 生成物に存在せず
    //  実機で ClassNotFound を実証）に上書きするため適用しない。runner は自前 Stub を使用する。
    // PR-21: kotlin-android → AGP 9 built-in Kotlin（jvmTarget は compileOptions 17 を継承）。
}

android {
    namespace = "com.imageshot.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 36
        testInstrumentationRunner = "com.imageshot.benchmark.MacrobenchmarkRunnerStub"
    }

    buildFeatures {
        buildConfig = false
        compose = false
    }

    // androidx.benchmark plugin は benchmark buildType を自動生成するが、
    // 明示して release 設定を継承させる（targetProject が非 debuggable/profileable で総量測定）。
    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// benchmark モジュールの androidTest 変体を全 buildType で有効にする
// （androidTest 変体は beforeVariants の既定フィルタと干渉するため解除）。

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    androidTestImplementation(libs.androidx.benchmark.macro.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    // PR-5: golden ΔE 計測（プレビュー/書き出し同値契約の device 計測ハーネス用）。
    // core:render は model を implementation で拾うため、androidTest 側では双方を明示参照する。
    androidTestImplementation(project(":core:render"))
    androidTestImplementation(project(":core:model"))
    // SimpleBitmapPhotoRenderer / ExportBitmapRenderer は suspend API → runBlocking 用。
    androidTestImplementation(libs.kotlinx.coroutines.android)
}
