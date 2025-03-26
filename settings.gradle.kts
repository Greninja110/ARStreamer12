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
        google()
        mavenCentral()
        // Add jitpack if any library requires it (WebRTC sometimes did)
        // maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ARStreamer"
include(":app")