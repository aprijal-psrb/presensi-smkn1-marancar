plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── Kunci tanda tangan ───────────────────────────────────────────────────────
// File kunci DAN password TIDAK disimpan di repo ini.
// CI men-decode app/keystore/presensi.p12 dari GitHub Secrets (KEYSTORE_B64),
// lalu mengirim password lewat variabel lingkungan (KEYSTORE_PASSWORD, KEY_PASSWORD).
// Lihat .github/workflows/build.yml dan docs/KUNCI-TANDA-TANGAN.md
val kunciRilis = file("keystore/presensi.p12")
val passKeystore = System.getenv("KEYSTORE_PASSWORD").orEmpty()
val aliasKunci = System.getenv("KEY_ALIAS") ?: "presensi"
val passKunci = System.getenv("KEY_PASSWORD") ?: passKeystore
val kunciSiap = kunciRilis.exists() && passKeystore.isNotEmpty()

android {
    namespace = "id.sch.smkn1marancar.presensi"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.sch.smkn1marancar.presensi"
        minSdk = 29          // Android 10 (Q) — sesuai target perangkat sekolah
        targetSdk = 34
        versionCode = 3      // v1.2: kunci tanda tangan baru (lihat docs/KUNCI-TANDA-TANGAN.md)
        versionName = "1.2"
    }

    signingConfigs {
        create("rilis") {
            if (kunciSiap) {
                storeFile = kunciRilis
                storeType = "PKCS12"
                storePassword = passKeystore
                keyAlias = aliasKunci
                keyPassword = passKunci
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false      // tanpa obfuscation agar mudah diaudit sekolah
            if (kunciSiap) signingConfig = signingConfigs.getByName("rilis")
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
