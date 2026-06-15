plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.the412banner.batterylog"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.the412banner.batterylog"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.1"
    }

    signingConfigs {
        // Stable debug key (committed) so updates install over the top and
        // PRESERVE collected logs — a fresh CI keystore each build would force
        // an uninstall and wipe the app's external log dir.
        create("debugStable") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debugStable")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
