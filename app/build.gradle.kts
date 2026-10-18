// app — applicationId com.imageshot.app。手動DI（AppContainer）+ Compose ナビゲーション。
// 依存注入は Hilt を使わない（ADR: ビルドリスク低減のため AppContainer + ViewModel initializer）。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.imageshot.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.imageshot.app"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // 署名設定はこのPRでは意図的に組み込まない（保留）。
    // local.properties の RELEASE_STORE_* は用意されているが、リリース署名は別PRで実施する。
    // TODO(release): signingConfigs/release ビルドタイプの組み込みは、キーストア保護方針の
    //  確認後に追加すること（.jks はコミット対象外）。

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false // TODO(release): R8 は署名PRで有効化する
        }
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
    implementation(project(":core:storage"))
    implementation(project(":core:render"))
    implementation(project(":feature:library"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:export"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // ADR-010: 書き出しは WorkManager 適合ジョブ。実装は Sprint C だが依存は先に確定。
    implementation(libs.work.runtime.ktx)

    implementation(libs.kotlinx.coroutines.android)
}
