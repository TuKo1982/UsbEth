import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key, kept outside the repository. Path overridable with USBETH_SIGNING;
// without it, release builds fall back to the debug key so that anyone can build the sources.
val signingFile = file(System.getenv("USBETH_SIGNING") ?: "${System.getProperty("user.home")}/.android-keys/usbeth.properties")
val signingProps = Properties().apply { if (signingFile.isFile) signingFile.inputStream().use { load(it) } }

android {
    namespace = "ch.tuko.usbeth"
    compileSdk = 34

    defaultConfig {
        applicationId = "ch.tuko.usbeth"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.0"
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
