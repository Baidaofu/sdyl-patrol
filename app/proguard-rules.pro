# Retrofit / Gson DTOs are reflected over; keep generic signatures.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn javax.annotation.**
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# Keep our network DTO package (Gson deserialization by field name).
-keep class com.sdyl.patrol.core.network.dto.** { *; }
-keep class com.sdyl.patrol.feature.**.model.** { *; }
