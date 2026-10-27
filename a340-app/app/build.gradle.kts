plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.a340.technicalassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.a340.technicalassistant"
        minSdk = 24
        targetSdk = 35
        versionCode = 15
        versionName = "0.7.7"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures { viewBinding = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("androidx.sqlite:sqlite-bundled:2.7.1")
}
