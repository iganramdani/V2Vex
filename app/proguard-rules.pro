# V2Vex R8/ProGuard rules — "99% Fast & Light"
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep native JNI bridge for hev-socks5-tunnel
-keep class hev.htproxy.TProxyService { *; }

# Keep kotlinx.serialization generated serializers
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.v2vex.client.model.**$$serializer { *; }
-keepclassmembers class com.v2vex.client.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.v2vex.client.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Remove verbose debug logs in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
