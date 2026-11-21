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

# Enum *names* are a persisted data format here, not an implementation detail.
# These are written to disk as `valueOf(name)` strings and read back the same
# way:
#   - core-storage:  MediaKind            (media sidecar metadata)
#   - core-index:    SearchBackend        (index meta table)
#   - feature-settings: DarkMode, FontScale, MediaPickerMode   (DataStore prefs)
#   - app:           any enum persisted through SettingsSnapshotCodec's
#                    `enumValueOf<T>` (settings backup snapshot)
# If R8 renames the constants, stored preferences and the index silently reset
# on the next upgrade — data loss that only shows up in a release build.
#
# The `<fields>` line is what actually protects the constant *names*; keeping
# values()/valueOf() alone is not enough.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# Belt and braces: even with the rule above, a whole enum class could still be
# renamed in a way that breaks reflection over its constants. Name the enums
# that actually own a persisted format so an accidental rule edit fails loudly
# here instead of silently corrupting user data.
-keepnames class com.meteor.memory.core.storage.MediaKind
-keepnames class com.meteor.memory.core.index.SearchBackend
-keepnames class com.meteor.memory.feature.settings.DarkMode
-keepnames class com.meteor.memory.feature.settings.FontScale
-keepnames class com.meteor.memory.feature.settings.MediaPickerMode
-keepnames class com.meteor.memory.core.storage.AlbumSortMode
-keepnames class com.meteor.memory.core.storage.Mood
-keepnames class com.meteor.memory.core.storage.Relation
-keepnames class com.meteor.memory.core.storage.RecordType
-keepnames class com.meteor.memory.core.storage.RecordSource
-keepnames class com.meteor.memory.core.storage.AlbumKind
-keepnames class com.meteor.memory.feature.gallery.GalleryKind

# media3 is annotated @UnstableApi in several places (MediaStreamDataSource,
# VideoPlayerCard); the AAR ships consumer rules, but keep the entry points
# explicit so a future media3 upgrade cannot silently strip them.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
