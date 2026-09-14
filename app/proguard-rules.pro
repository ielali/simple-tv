# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.ielali.simpletv.** { kotlinx.serialization.KSerializer serializer(...); }
# Ktor / coroutines
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
