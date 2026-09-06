# Falcor — keep Retrofit/Serialization/LibVLC
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class kotlinx.serialization.json.** { *; }
-dontwarn org.videolan.libvlc.**
