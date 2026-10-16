# R8 is on for release builds. Everything below exists because something in the app
# reaches for a class or member by name at runtime, where the shrinker cannot see it.

# Keep line numbers so a Play Console crash is readable, but hide the source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Gson ---------------------------------------------------------------------
# Every API model is populated by reflection from field names / @SerializedName, and
# TypeToken needs generic signatures to survive.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-keep class com.anubhav.app.data.model.** { *; }
-keep class com.anubhav.app.data.remote.CatalogResponse { *; }
-keep class com.anubhav.app.data.remote.CatalogTestDto { *; }
# The bundled catalog seed is parsed into private nested classes of CatalogRepository.
-keep class com.anubhav.app.data.repository.CatalogRepository$* { *; }
-keep class com.anubhav.app.data.repository.TestInfoRepository$* { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# R8 full mode (the AGP 8 default) drops generic signatures even with -keepattributes
# Signature unless the class itself is kept. ReportCache and the booking screen read
# List<T> through anonymous TypeToken subclasses. These are the rules Gson 2.11 ships.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# --- Retrofit -----------------------------------------------------------------
# Service interfaces are implemented by a runtime proxy driven off their annotations.
-keep,allowobfuscation interface com.anubhav.app.data.remote.AktivApi
-keep,allowobfuscation interface com.anubhav.app.data.remote.CustomerApi
-keep,allowobfuscation interface com.anubhav.app.data.remote.CatalogApi
-keep,allowobfuscation interface com.anubhav.app.data.remote.AdminApi
-keepclassmembers,allowshrinking,allowobfuscation interface com.anubhav.app.data.remote.** {
    @retrofit2.http.* <methods>;
}
# Retrofit 2.9 reads each suspend method's Continuation<Response<T>> signature at
# runtime. Without these (shipped by Retrofit 2.10+) R8 full mode strips it and EVERY
# API call fails with "Class cannot be cast to ParameterizedType" - which broke clinic
# login, report lookup, bookings and payments in release builds.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- WebView bridge -----------------------------------------------------------
# picker.html calls window.AndroidMap.*; those methods are only reachable from JS, so
# without this the offline map picker silently stops reporting the dropped pin.
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.anubhav.app.ui.location.MapPickerFragment$MapBridge { *; }

# --- Razorpay -----------------------------------------------------------------
# Checkout drives its flow through reflection and its own WebView.
-keep class com.razorpay.** { *; }
-dontwarn com.razorpay.**
-optimizations !method/inlining/*
-keepclasseswithmembers class * {
    public void onPayment*(...);
}
