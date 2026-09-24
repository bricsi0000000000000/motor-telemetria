import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

/**
 * Gépfüggő alapértelmezések (szervercím, token) a repóból kihagyott
 * `secrets.properties` fájlból. Lásd: `secrets.properties.example`.
 * Ha a fájl hiányzik, a build nem áll meg: az app üres beállításokkal indul,
 * és a Beállítások képernyőn adható meg minden.
 */
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(key: String, fallback: String = "") =
    (secrets.getProperty(key) ?: fallback).trim()

android {
    namespace = "hu.motor.telemetria"
    compileSdk = 35

    defaultConfig {
        applicationId = "hu.motor.telemetria"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"

        buildConfigField("String", "DEFAULT_BASE_URL", "\"${secret("motor.defaultBaseUrl")}\"")
        buildConfigField("String", "DEFAULT_TOKEN", "\"${secret("motor.defaultToken")}\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Ingyenes, kulcs nélküli OpenStreetMap térkép
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
