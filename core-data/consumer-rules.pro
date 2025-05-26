# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/tonimelisma/Development/Mail/core-data/proguard-defaults.txt
# You can edit '/Users/tonimelisma/Development/Mail/core-data/proguard-defaults.txt' to apply suggestions to all targetRules
# types.

# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses Application classes that are dynamically created by IDE,
# consider adding the following rules.
#-keep public class * extends android.app.Application
#-keep public class * extends android.app.Service
#-keep public class * extends android.content.ContentProvider
#-keep public class * extends android.content.BroadcastReceiver
#-keep public class * extends android.preference.Preference

# Add any project specific keep rules here:

# If you use reflection, you might want to keep the attributes.
#-keepattributes Signature

# For Enumerationsshrinker support, see https://guardsquare.com/manual/configuration/examples
#-keepclassmembers enum * {
#    public static **[] values();
#    public static ** valueOf(java.lang.String);
#}

#-keep @interface kotlin.Metadata

#-dontwarn org.jetbrains.kotlin.**
#-dontwarn kotlinx.serialization.**
#-dontwarn kotlinx.coroutines.debug.** 