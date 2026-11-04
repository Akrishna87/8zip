# Archive libraries use reflection / JNI / optional dependencies.
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn org.slf4j.**
-dontwarn net.lingala.zip4j.**
-dontwarn com.github.junrar.**
-keep class org.apache.commons.compress.** { *; }
-keep class com.github.junrar.** { *; }
-keep class net.lingala.zip4j.** { *; }
# libarchive's native code looks these classes/methods up by name.
-keep class me.zhanghai.android.libarchive.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
