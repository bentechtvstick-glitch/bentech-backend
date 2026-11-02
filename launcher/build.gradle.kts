// GoSmart Home: ti app launcher (ekran akèy) pou box Android TV. Li separe nèt ak app Galaxy / Ben Tech yo.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.gosmartservices.home"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.gosmartservices.home"
        minSdk = 22
        targetSdk = 34
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.0.$build"
        buildConfigField("String", "PANEL_URL", "\"https://panel.galaxytvstick.net/api\"")
    }

    signingConfigs {
        create("gosmart") {
            storeFile = rootProject.file("signing/galaxy.keystore")
            storePassword = "galaxytv"
            keyAlias = "galaxy"
            keyPassword = "galaxytv"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("gosmart") }
        release {
            signingConfig = signingConfigs.getByName("gosmart")
            isMinifyEnabled = false
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
