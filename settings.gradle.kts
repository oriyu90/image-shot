// Image Shot — Gradle settings
// モジュール境界は 02_実装定義書.md §2 に準拠（M1として最小構成: app / core/* / feature/*）
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ImageShotPro"

include(":app")
include(":core:model")
include(":core:database")
include(":core:storage")
include(":core:render")
include(":feature:library")
include(":feature:editor")
include(":feature:export")
include(":benchmark")
