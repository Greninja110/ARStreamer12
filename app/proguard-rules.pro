# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in Android Studio configuration files. You must run the build release build variant to support these.

# Keep generic classes required by Kotlin reflection
-keepnames class kotlin.Metadata

# Keep specific classes related to Coroutines
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }

# Keep Ktor classes
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-dontwarn io.ktor.**

# Keep OkHttp/Okio (often used by Ktor engines or WebRTC internally)
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep WebRTC classes (adjust based on the specific library used)
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-keepattributes *Annotation* # Important for WebRTC internals

# Keep ARCore classes
-keep class com.google.ar.** { *; }
-keepclassmembers class com.google.ar.** { *; }

# Keep Gson (used for Ktor content negotiation and potentially data serialization)
-keep class com.google.gson.** { *; }
-keep class com.example.myapplication12.model.** { *; } # Keep your data models

# Keep annotation classes during obfuscation
-keepattributes *Annotation*

# Preserve stack traces
-keepattributes SourceFile,LineNumberTable