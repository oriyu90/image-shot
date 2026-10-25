// core:database — Room (SQLite)。DBは描画APIを知らない（02_実装定義書.md §2 依存方向）。
// PR-21: kotlin-android → AGP 9 built-in Kotlin（jvmTarget は compileOptions 17 を継承）。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.imageshot.core.database"
    compileSdk = 36

    defaultConfig {
        minSdk = 36
        // ライブラリ androidTest を単体実行する標準 runner（EditStore 実 Room 統合テスト用）。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Room のスキーマをschemas/へ書き出し（移行テストの正本）。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))

    api(libs.room.runtime)
    implementation(libs.room.ktx)
    // ADR-021 未採用のため既定 framework factory を使用
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // EditStoreInstrumentedTest の runBlocking（suspend 契約）用。
    androidTestImplementation(libs.kotlinx.coroutines.android)
}
