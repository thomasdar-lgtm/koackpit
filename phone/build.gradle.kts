plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Appli compagnon pour le téléphone (S25) : envoie à Koackpit la pochette et les infos du morceau en cours.
android {
    namespace = "com.koala.koackpit.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.koala.koackpit.phone"
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
