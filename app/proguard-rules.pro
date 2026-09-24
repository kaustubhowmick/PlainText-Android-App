# The app uses no reflection, serialization, or JNI; AAPT generates keep rules
# for the manifest's activity and for views referenced from layouts.

# Strip debug/verbose/info logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Allow aggressive repackaging into a single package for shorter names.
-repackageclasses ''
-allowaccessmodification
