plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Petite appli compagnon : affiche l'écran de mise à jour pendant que Koackpit est remplacé.
android {
    namespace = "com.koala.koackpit.updater"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.koala.koackpit.updater"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../app/koackpit.jks")
            storePassword = "koackpit"
            keyAlias = "koackpit"
            keyPassword = "koackpit"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
