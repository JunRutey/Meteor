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
