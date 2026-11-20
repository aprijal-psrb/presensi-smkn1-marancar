plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Kunci tanda tangan: file .p12 hasil decode dari keystore/presensi.p12.b64
// (dibuat otomatis oleh workflow CI, lihat .github/workflows/build.yml)
val kunciRilis = file("keystore/presensi.p12")

android {
    namespace = "id.sch.smkn1marancar.presensi"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.sch.smkn1marancar.presensi"
        minSdk = 29          // Android 10 (Q) — sesuai target perangkat sekolah
        targetSdk = 34
        versionCode = 2      // v1.1: perbaikan P0 (outbox, roster, alamat QR)
        versionName = "1.1"
    }

    signingConfigs {
        create("rilis") {
            if (kunciRilis.exists()) {
                storeFile = kunciRilis
                storeType = "PKCS12"
                storePassword = "presensi2026"
                keyAlias = "presensi"
                keyPassword = "presensi2026"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false      // tanpa obfuscation agar mudah diaudit sekolah
            if (kunciRilis.exists()) signingConfig = signingConfigs.getByName("rilis")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Server absensi ringan yang berjalan DI HP GURU (tanpa VPS/cloud terpisah)
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // QR: pembuat gambar (core) + pemindai kamera (embedded)
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
