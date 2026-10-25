// core:storage — SAF/PhotoPicker URI の取り込み契約（02_実装定義書.md §5, §8.1, ADR-009）
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.imageshot.core.storage"
    compileSdk = 36

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    // PR-8: サムネイル生成が共有デコードパス（SourceDecode, core:render）を使う。
    // core:render は core:model のみに依存し逆循環は無い。
    implementation(project(":core:render"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // runBlocking 等（Android 依存の無い suspend 契約のJVM検証）。
    testImplementation(libs.kotlinx.coroutines.android)
}
