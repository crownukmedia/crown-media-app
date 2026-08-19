# The first cut keeps the app buildable while the networking and player layers settle.
-keep class androidx.media3.** { *; }
-dontwarn org.conscrypt.**
