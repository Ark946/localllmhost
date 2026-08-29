plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.arkj.llmserver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arkj.llmserver"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
