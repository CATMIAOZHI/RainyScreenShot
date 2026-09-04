# RainyScreenShot ProGuard 规则
# 当前 minify 关闭，规则为未来开启做准备

# Shizuku API
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.rainy.screenshot.**$$serializer { *; }
-keepclassmembers class com.rainy.screenshot.** {
    *** Companion;
}
-keepclasseswithmembers class com.rainy.screenshot.** {
    kotlinx.serialization.KSerializer serializer(...);
}