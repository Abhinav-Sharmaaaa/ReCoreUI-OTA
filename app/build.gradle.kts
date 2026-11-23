plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.recore.ota"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.recore.ota"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        val repo = providers.gradleProperty("otaRepo").orElse("owner/repo").get()
        buildConfigField("String", "OTA_REPO", "\"$repo\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
