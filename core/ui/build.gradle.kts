// core:ui — 共通デザイントークン（Color/Type/Shape/Spacing）と共有コンポーザブル。
// 画面側は MaterialTheme.* と MaterialTheme.spacing だけを参照する（DESIGN_RULES.md）。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.imageshot.core.ui"
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
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    debugApi(libs.compose.ui.tooling)
    api(libs.compose.ui.tooling.preview)
}
