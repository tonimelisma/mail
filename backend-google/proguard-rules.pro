# ProGuard rules for backend-google module

# Keep any serializable classes used for Ktor/kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class net.melisma.backend_google.** {
    *** Companion;
}
-keepclasseswithmembers class net.melisma.backend_google.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Google Sign-In related classes
-keep class com.google.android.gms.auth.** { *; }