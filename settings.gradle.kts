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
        // Xposed 传统 API 的官方仓库，只提供 de.robv.android.xposed 一个坐标
        maven {
            url = uri("https://api.xposed.info/")
            content { includeGroup("de.robv.android.xposed") }
        }
    }
}

rootProject.name = "MicControl"
include(":app")
include(":module")
