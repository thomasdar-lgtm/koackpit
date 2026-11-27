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
        versionCode = 157
        versionName = "0.20.6"
        // Clé HERE (limitations de vitesse) : secret GitHub HERE_API_KEY, ajouté seulement à la fabrication de l'APK
        buildConfigField("String", "HERE_KEY", "\"" + (System.getenv("HERE_API_KEY") ?: "") + "\"")
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

    // Images déjà compressées : pas de recompression (lecture directe plus rapide)
    androidResources { noCompress += listOf("webp", "png") }

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
    // Vue de la Golf : images pré-calculées (assets/golf/views), aucune bibliothèque 3D
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
