# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the default proguard-android-optimize.txt.

# androidx.security-crypto pulls in Google Tink, which references errorprone's
# compile-time-only annotations (SOURCE/CLASS retention; never touched at
# runtime). They aren't on the runtime classpath, so R8 can't resolve them —
# tell it that's expected rather than failing the build.
-dontwarn com.google.errorprone.annotations.**
