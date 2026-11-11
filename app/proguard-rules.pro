# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# OkHttp 自带 consumer rules，一般无需额外配置。
# 下面两条用于消除可选依赖（Conscrypt / BouncyCastle）的缺失告警。
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**

# 保留行号信息，便于线上崩溃定位。
-keepattributes SourceFile,LineNumberTable
