plugins {
    alias(libs.plugins.android.application)
}

import java.io.FileInputStream
import java.util.Properties

// Release signing credentials live in keystore.properties (git-ignored).
// The keystore file itself must be backed up: every future release has to be
// signed with this same key to allow in-place upgrades.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.arkj.llmserver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arkj.llmserver"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                // storeFile is project-root relative (e.g. keystore/release.jks).
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        // Local unit tests exercise code paths that log via android.util.Log (XLog).
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":llm-contract"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // LiteRT-LM on-device LLM inference (Google AI Edge)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.0")

    testImplementation(libs.junit)
}
