# Keep WebView JS bridge members if minification is ever enabled.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
