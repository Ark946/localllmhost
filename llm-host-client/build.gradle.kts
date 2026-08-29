// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.arkj.llm.client"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Register the "release" software component for Maven publishing.
    publishing {
        singleVariant("release")
    }
}

dependencies {
    // Shared AIDL contract + wire codecs. api() so consumers see
    // ILlmService, ChatResult, ChatMessageCodec, etc. without re-declaring.
    api(project(":llm-contract"))
}

// JitPack builds from a git tag and serves the release AAR. It overrides
// groupId to "com.github.<USERNAME>" and version to the tag; the values below
// are defaults for local `publishToMavenLocal` use. JitPack rewrites the
// project(":llm-contract") dependency above to the matching artifact.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.Ark946"
                artifactId = "llm-host-client"
                version = "0.1.0"
            }
        }
    }
}
