# XRay99 R8/ProGuard rules — "99% Fast & Light"
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep kotlinx.serialization generated serializers
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.xray99.client.model.**$$serializer { *; }
-keepclassmembers class com.xray99.client.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.xray99.client.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Remove verbose debug logs in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
