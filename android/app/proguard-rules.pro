# Mantiene i metodi esposti al JavaScript del player
-keepattributes JavascriptInterface
-keepclassmembers class com.kairos.player.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# VLC usa codice nativo (JNI): le sue classi non vanno rinominate
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.**
