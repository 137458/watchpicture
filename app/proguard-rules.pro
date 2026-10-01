# Project-level ProGuard and R8 rules

# Keep JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Native7z JNI bindings and data models
-keep class com.watchpicture.app.archive.Native7z { *; }
-keep class com.watchpicture.app.archive.Native7zEntry { *; }
-keepclassmembers class com.watchpicture.app.archive.Native7zEntry {
    <init>(...);
    <fields>;
}

# Keep models annotated with @Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# KotlinX Serialization
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,allowobfuscation,allowshrinking class * {
    @kotlinx.serialization.Serializable class *;
}

# Vendored 7z 缓存解码器经反射注入 Commons Coders.CODER_MAP(字段名硬编码反射),
# R8 重命名会使 install() 静默失败,release 退回每次全量 PBKDF2(0.5-1.5s)
-keepclassmembers class org.apache.commons.compress.archivers.sevenz.Coders {
    static *** CODER_MAP;
}
-keep class org.apache.commons.compress.archivers.sevenz.CachedAES256SHA256Decoder { *; }
