import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// kodif.integrationId / kodif.environment (production, staging, development) in local.properties.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "ai.kodif.chat.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.kodif.chat.example"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField(
            "String",
            "KODIF_INTEGRATION_ID",
            "\"${localProperties.getProperty("kodif.integrationId", "YOUR_INTEGRATION_ID")}\"",
        )
        buildConfigField(
            "String",
            "KODIF_ENVIRONMENT",
            "\"${localProperties.getProperty("kodif.environment", "development")}\"",
        )
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":kodifchat"))
    implementation(libs.androidx.appcompat)
}
