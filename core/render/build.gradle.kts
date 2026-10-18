// core:render — Kotlin/Bitmap 基盤の暫定レンダーパイプライン。
// NDK版（ADR-002/005 の本命）は別モジュール native/* で行う。ここは骨格と同値契約の土台。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.imageshot.core.render"
    compileSdk = 36

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
