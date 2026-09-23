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
