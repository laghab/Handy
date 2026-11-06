# sherpa-onnx's JNI layer reads Kotlin config objects by class and field name
# and constructs result objects reflectively. Nothing in it may be renamed.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

# commons-compress has optional codecs we don't ship.
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.apache.commons.codec.**
-dontwarn org.apache.commons.lang3.**
-dontwarn org.apache.commons.io.**
-dontwarn org.osgi.**
-dontwarn javax.annotation.**

# OkHttp platform shims.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
