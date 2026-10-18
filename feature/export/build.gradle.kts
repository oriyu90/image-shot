// feature:export — 書き出し画面 + ExportJob キュー + ExportWorker（§8.2 2段階コミット、ADR-010）
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // request: ExportJobRequest のファイル永続化に必要（既存バージョン正本から追加なし）
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.imageshot.feature.export"
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    // request: ExportRenderer（core:render 新規ファイル）を使用するため。新規依存追加は既存カタログのみ。
    implementation(project(":core:render"))
    // request: ADR-010 ExportWorker（androidx.worker 2.10.5 は既存カタログの正本バージョン）
    implementation(libs.work.runtime.ktx)
    // request: ExportScreen の ACTION_CREATE_DOCUMENT Launcher（ActivityResultContracts）
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.serialization.json)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    debugApi(libs.compose.ui.tooling)
    api(libs.compose.ui.tooling.preview)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // 純JVM単体テスト（Robolectric未導入のため Android 実装クラスは対象外）
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.android)
}
