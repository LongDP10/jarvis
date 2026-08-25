# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.jarvis.assistant.**$$serializer { *; }
-keepclassmembers class com.jarvis.assistant.** {
    *** Companion;
}
-keepclasseswithmembers class com.jarvis.assistant.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The accessibility and listener services are referenced only from the manifest.
-keep class com.jarvis.assistant.accessibility.JarvisAccessibilityService { *; }
-keep class com.jarvis.assistant.notifications.JarvisNotificationListener { *; }
-keep class com.jarvis.assistant.overlay.OverlayService { *; }

# OkHttp platform warnings.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
