# Meteor release rules. Keep in sync with the frozen interfaces.
-keep class com.meteor.memory.core.crypto.** { *; }
-keep class com.meteor.memory.core.storage.** { *; }

# Tink (pulled in by androidx.security-crypto) references Error Prone's
# compile-time annotations, which are not packaged into the APK. They are only
# annotations read by static analysis, so R8 can safely ignore them.
-dontwarn com.google.errorprone.annotations.**

# R8 renames classes, but AppLog writes crash reports to disk; keep the line
# numbers so a stack trace from a release build still says where it happened.
-keepattributes SourceFile,LineNumberTable

# Enum *names* are a persisted data format here, not an implementation detail:
# MediaKind, SearchBackend, DarkMode, FontScale and MediaPickerMode are all
# written to disk as `valueOf(name)` strings and read back the same way. If R8
# renamed the constants, every existing archive and every stored preference
# would fail to parse after an upgrade — a silent data loss that only shows up
# on a release build, which is exactly what CI does not exercise today.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# media3 is annotated @UnstableApi in several places (MediaStreamDataSource,
# VideoPlayerCard); the AAR ships consumer rules, but keep the entry points
# explicit so a future media3 upgrade cannot silently strip them.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
