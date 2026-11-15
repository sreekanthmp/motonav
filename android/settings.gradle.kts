pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()        // includes the Google Navigation SDK
        mavenCentral()
    }
}

rootProject.name = "MotoNav"
include(":app")
