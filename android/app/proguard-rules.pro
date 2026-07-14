# Don't obfuscate code
-dontobfuscate

# Our code
-keep class com.limelight.binding.input.evdev.* {*;}

# Moonlight common
-keep class com.limelight.nvstream.jni.* {*;}

# Okio
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

# BouncyCastle
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
-dontwarn javax.naming.**

# jMDNS
-dontwarn javax.jmdns.impl.DNSCache
-dontwarn org.slf4j.**
# Space Connect account/launcher API models (populated by Gson via reflection).
# Without these keeps, R8 strips their fields/constructors — marking the model
# classes abstract or removing them entirely — so gson.fromJson() throws
# "Unable to instantiate class com.limelight.account.SpaceConnectApiClient$..."
# on every login (success, invalid credentials, the 2FA challenge and refresh).
-keep class com.limelight.account.SpaceConnectApiClient$* { *; }
-keepclassmembers class com.limelight.account.SpaceConnectApiClient$* { *; }
-keep class com.limelight.account.SecureSessionStore$* { *; }
-keepclassmembers class com.limelight.account.SecureSessionStore$* { *; }

# Gson relies on generic signatures and annotations
-keepattributes Signature
-keepattributes *Annotation*

# reCAPTCHA token bridge: these methods are only ever called from JavaScript in
# the off-screen WebView, so R8 would otherwise shrink them away.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
