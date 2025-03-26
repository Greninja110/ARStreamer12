// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.2.0" apply false // Use your Android Gradle plugin version
    id("org.jetbrains.kotlin.android") version "1.9.10" apply false // Use your Kotlin version
    id("com.google.devtools.ksp") version "1.9.10-1.0.13" apply false // If needed for libraries like Room/Hilt
    // id("com.google.protobuf") version "0.9.4" apply false // If using Protobuf for serialization
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}