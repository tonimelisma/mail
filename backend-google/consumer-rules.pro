# Consumer ProGuard rules for backend-google module

# These rules will be applied to any consumer of this library

# Keep Google Sign-In related classes
-keep class com.google.android.gms.auth.** { *; }

# Keep kotlinx.serialization annotated classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt