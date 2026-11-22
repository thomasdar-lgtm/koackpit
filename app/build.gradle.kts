plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.koala.koackpit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.koala.koackpit"
        minSdk = 26
        targetSdk = 35
        versionCode = 142
        versionName = "0.17.3"
    }

    signingConfigs {
        create("release") {
            storeFile = file("koackpit.jks")
            storePassword = "koackpit"
            keyAlias = "koackpit"
            keyPassword = "koackpit"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            // Autoradio PX5 = arm64 ; armeabi-v7a gardé par sécurité. Pas d'x86 (APK plus léger) — la version
            // de test (debug) les garde pour l'émulateur.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("roborazzi.test.record", "true") }
        }
    }

    // Le modèle et la lumière sont déjà compressés : pas de recompression (lecture directe plus rapide)
    androidResources { noCompress += listOf("glb", "ktx") }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    // Vue 3D de la Golf : Filament (moteur 3D libre de Google, Apache 2.0, hors ligne, aucune connexion)
    val filament = "1.56.0"
    implementation("com.google.android.filament:filament-android:$filament")
    implementation("com.google.android.filament:gltfio-android:$filament")
    implementation("com.google.android.filament:filament-utils-android:$filament")
    // QR code du fichier de diagnostic (génération seule, sans appareil photo)
    implementation("com.google.zxing:core:3.5.3")

    // Captures d'écran sans la voiture (workflow screenshots.yml uniquement)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.29.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.29.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
