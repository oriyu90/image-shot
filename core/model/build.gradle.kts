// core:model — 不変データクラスのみ。Android依存なし（02_実装定義書.md §2 依存方向）。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    // マスク正規化テスト（MaskSpecsTest）の assertNull/assertNotNull 戻り値利用のため。
    testImplementation(kotlin("test"))
}
