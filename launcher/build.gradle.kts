// Galaxy Home / Ben Tech Home: ti app launcher (ekran akèy) pou box Android TV. Li separe nèt ak app Galaxy / Ben Tech yo.
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

    // Chak mak gen pwòp launcher pa l (non, ikòn, app TV li sèlman)
    flavorDimensions += "brand"
    productFlavors {
        create("galaxy") {
            dimension = "brand"
            applicationId = "com.galaxytvstick.home"
            buildConfigField("String", "BRAND_PKG", "\"com.galaxytvstick.tv\"")
            buildConfigField("String", "HOME_TITLE", "\"Galaxy TV Stick\"")
        }
        create("bentech") {
            dimension = "brand"
            applicationId = "net.bentechtvstick.home"
            buildConfigField("String", "BRAND_PKG", "\"net.bentechtvstick.tv\"")
            buildConfigField("String", "HOME_TITLE", "\"Ben Tech TV Stick\"")
        }
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
