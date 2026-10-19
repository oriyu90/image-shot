# Image Shot — R8 設定（リリース最適化・ADR-025）
#
# 基本方針（AGENTS.md/02_実装定義書.md）:
# - アプリは手動 DI（AppContainer・リフレクション不使用）。Room 2.8.5 / WorkManager /
#   Compose / Coroutines は AAR consumer rules に従う（追加 keep は最小限）。
# - kotlinx.serialization: EditRecipe 等の payload JSON 化に使用。ライブラリ標準の
#   consumer rules に加え、canonical keep（$$serializer / Companion / serializer()）を
#   com.imageshot.core.model に限定して付与（過大保存を避ける）。

# ---- kotlinx.serialization（com.imageshot.core.model のみに限定）----
-keep,includedescriptorclasses class com.imageshot.core.model.**$$serializer { *; }
-keepclassmembers class com.imageshot.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.imageshot.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- 実機検証で必要になった際のみ追加（追加は ADR-025 に記録する）----
