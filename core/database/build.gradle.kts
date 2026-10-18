// core:database — Room (SQLite WAL)。DBは描画APIを知らない（02_実装定義書.md §2 依存方向）。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.imageshot.core.database"
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

// Room のスキーマをschemas/へ書き出し（移行テストの正本）。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))

    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
