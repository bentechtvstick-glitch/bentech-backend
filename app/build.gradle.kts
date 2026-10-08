plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.galaxytvstick.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.galaxytvstick.app"
        minSdk = 22
        targetSdk = 34
        // Chak bati sou GitHub gen yon nimewo pi wo, pou nouvo APK a ka enstale sou ansyen an
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.0.$build"

        // Adrès backend panel la (bentech-backend)
        buildConfigField("String", "PANEL_URL", "\"https://bentech-backend.onrender.com/api\"")

        // Sèvè Xtream pa defo si panel la pa bay youn nan playlist la (egz: "http://dns.example.com:8080")
        buildConfigField("String", "DEFAULT_SERVER", "\"\"")

        // false = kliyan an pa ka antre playlist li menm; se panel la sèl ki ajoute l
        buildConfigField("boolean", "ALLOW_MANUAL_LOGIN", "false")
    }

    // Menm kle siyati pou tout bati yo (san sa, Fire Stick la refize mete yon nouvo vèsyon sou ansyen an)
    signingConfigs {
        create("galaxy") {
            storeFile = rootProject.file("signing/galaxy.keystore")
            storePassword = "galaxytv"
            keyAlias = "galaxy"
            keyPassword = "galaxytv"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("galaxy")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    val media3 = "1.4.1"

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")

    // Tout imoji yo (menm sou ansyen Fire TV ki pa gen nouvo imoji yo): font la anndan app la
    implementation("androidx.emoji2:emoji2:1.4.0")
    implementation("androidx.emoji2:emoji2-views-helper:1.4.0")
    implementation("androidx.emoji2:emoji2-bundled:1.4.0")

    // Player (sipòte HLS, TS, HEVC/AV1 jiska 8K si aparèy la kapab)
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
}
