import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 百度智能云密钥统一从 local.properties 读取，避免把密钥写进源码。
// local.properties 已被 .gitignore 忽略，可参考 local.properties.example 配置。
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun localProperty(key: String): String = localProperties.getProperty(key).orEmpty()

android {
    namespace = "com.rongyou.xinling"

    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.rongyou.xinling"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BAIDU_SPEECH_API_KEY", "\"${localProperty("BAIDU_SPEECH_API_KEY")}\"")
        buildConfigField("String", "BAIDU_SPEECH_SECRET_KEY", "\"${localProperty("BAIDU_SPEECH_SECRET_KEY")}\"")
        buildConfigField("String", "BAIDU_NLP_API_KEY", "\"${localProperty("BAIDU_NLP_API_KEY")}\"")
        buildConfigField("String", "BAIDU_NLP_SECRET_KEY", "\"${localProperty("BAIDU_NLP_SECRET_KEY")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.cardview)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
