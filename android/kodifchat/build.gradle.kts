plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

// JitPack builds tags and sets GROUP / VERSION; everywhere else the coordinates are ai.kodif:chat.
val isJitPack = System.getenv("JITPACK") == "true"
val publishGroup = if (isJitPack) System.getenv("GROUP") ?: "ai.kodif" else "ai.kodif"
val publishArtifact = if (isJitPack) "kodif-chat-mobile-sdk" else "chat"
val publishVersion = System.getenv("VERSION").takeIf { isJitPack && !it.isNullOrBlank() }
    ?: property("VERSION_NAME").toString()

android {
    namespace = "ai.kodif.chat"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$publishVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        // New releases of dependencies are not a build failure.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    testOptions {
        // android.util.Log in unit tests.
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi()
    compilerOptions {
        // Java implementations of KodifChatListener get its default methods.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    api(libs.androidx.fragment)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.junit)
    // The real org.json for JVM tests (android.jar only has stubs).
    testImplementation(libs.org.json)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = publishGroup
            artifactId = publishArtifact
            version = publishVersion
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Kodif Chat")
                description.set("The Kodif chat widget for Android apps.")
                url.set("https://github.com/InvisibleCommerce/kodif-chat-mobile-sdk")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
