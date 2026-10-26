// feature:editor — 編集画面骨格（プレビュー+スライダー群+履歴）。詳細実装は Sprint C。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.imageshot.feature.editor"
    compileSdk = 36

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:render"))

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    debugApi(libs.compose.ui.tooling)
    api(libs.compose.ui.tooling.preview)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    // PR-29c: マスク編集の写像（fitRect/ハンドル）・間引き・既定値・表示スケール（純関数）の
    // JVM 単体テスト。Robolectric は導入しない（feature:library の AvailabilitySweepTest と同方針）。
    testImplementation(libs.junit)
}
