-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*, JavascriptInterface
-keep class com.dump.app.** { *; }
-keep class * extends android.webkit.WebView { *; }
-dontwarn com.dump.app.**
