// feature:library — 写真グリッド + PhotoPicker取り込み（01_製品仕様書.md §4.1, U01）
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.imageshot.feature.library"
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
    implementation(project(":core:storage"))

    api(libs.androidx.activity.compose)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.lifecycle.viewmodel.compose)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    debugApi(libs.compose.ui.tooling)
    api(libs.compose.ui.tooling.preview)

    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    // PR-30 G2: availability 判定（純粋関数）の JVM 単体テスト。Robolectric は導入しない。
    testImplementation(libs.junit)
}
