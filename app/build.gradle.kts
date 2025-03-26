// Add these imports right after the plugins block
import java.util.Properties
import java.io.FileInputStream
import java.io.File // Also import File since rootProject.file returns it

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
    kotlin("kapt") // If using libraries requiring annotation processing (like Room, Hilt)
    // id("com.google.protobuf") // If using protobuf
}

// Load secrets from local.properties
val localProperties = Properties() // Use imported Properties
val localPropertiesFile: File = rootProject.file("local.properties") // Explicitly type as File
if (localPropertiesFile.exists()) {
    // Use try-with-resources for safer file handling
    FileInputStream(localPropertiesFile).use { fis -> // Use imported FileInputStream
        localProperties.load(fis)
    }
    // Alternative without try-with-resources (less safe if exceptions occur):
    // val fis = FileInputStream(localPropertiesFile)
    // localProperties.load(fis)
    // fis.close() // Important to close the stream
}

android {
    // Ensure this namespace is unique to your app
    namespace = "com.example.myapplication12" // <<< MAKE SURE THIS IS YOUR UNIQUE PACKAGE NAME
    compileSdk = 34 // Target Android 14

    defaultConfig {
        // Ensure this applicationId is unique
        applicationId = "com.example.myapplication12" // <<< MAKE SURE THIS IS YOUR UNIQUE ID
        minSdk = 26 // Minimum SDK for ARCore and modern features
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true // Enable code shrinking
            isShrinkResources = true // Shrink resources
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // You might not need proguard in debug, but keep if intended
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        // Make sure this version is compatible with your Kotlin and Compose versions
        kotlinCompilerExtensionVersion = "1.5.3"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Ktor/Netty exclusion if needed
            excludes += "META-INF/native-image/**"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    // Core Android & Kotlin
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service-ktx:2.7.0") // For Service lifecycle
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.10.01")) // Use latest stable BOM
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core") // For Icons
    implementation("androidx.compose.material:material-icons-extended") // For more Icons

    // CameraX
    val cameraxVersion = "1.3.1" // Use latest stable CameraX version
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-video:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")
    implementation("androidx.camera:camera-extensions:${cameraxVersion}") // Optional for effects

    // ARCore
    implementation("com.google.ar:core:1.48.0") // Use latest stable ARCore version

    // Ktor (Server & WebSockets)
    val ktorVersion = "3.1.1" // Use latest stable Ktor version
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion") // Or CIO: ktor-server-cio-jvm
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-gson-jvm:$ktorVersion") // For JSON serialization
    implementation("io.ktor:ktor-server-html-builder-jvm:$ktorVersion") // For serving HTML
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.9.1") // Or newer
    implementation("com.google.code.gson:gson:2.10.1") // Or newer
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2") // For serviceScope


    // WebRTC (Official Google library)
    implementation("org.webrtc:google-webrtc:1.0.32006")// Check for latest stable version

    // Gson (For JSON serialization if not using Ktor's plugin or for DataSerializer)
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.9.1") // Use appropriate version

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Optional: Protobuf for more efficient serialization
    // implementation("com.google.protobuf:protobuf-kotlin-lite:3.25.1")
    // implementation("com.google.protobuf:protobuf-javalite:3.25.1")
    // // Add protobuf plugin configuration if used
}

// Protobuf configuration (if used)
// protobuf {
//    protoc {
//        artifact = "com.google.protobuf:protoc:3.25.1"
//    }
//    generateProtoTasks {
//        all().forEach { task ->
//            task.builtins {
//                // Ensure you generate Kotlin code
//                 id("kotlin") { option("lite") }
//            }
//        }
//    }
// }