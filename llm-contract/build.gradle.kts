plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.arkj.llm.contract"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
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

// JitPack builds from a git tag and serves the release AAR. It overrides
// groupId to "com.github.<USERNAME>" and version to the tag; the values below
// are defaults for local `publishToMavenLocal` use.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.Ark946"
                artifactId = "llm-contract"
                version = "0.1.0"
            }
        }
    }
}

dependencies {
    // gson is part of the public API surface: ToolSpecCodec.ToolDecl.parameters
    // exposes JsonObject, so it must be api() (compile-transitive), not implementation().
    api(libs.gson)
    // Shared message/tool types (ChatMessage, ToolSpecification, ToolExecutionRequest)
    api(libs.langchain4j.core)

    testImplementation(libs.junit)
}
