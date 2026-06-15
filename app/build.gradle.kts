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
        versionCode = 7
        versionName = "1.5.0"
    }

    signingConfigs {
        // AOSP "testkey" (the well-known public android testkey, CN=Android),
        // converted to a PKCS12 keystore and committed. Stable identity so updates
        // install over the top, signed with the full v1 + v2 + v3 scheme set.
        create("testkey") {
            storeFile = file("testkey.p12")
            storeType = "PKCS12"
            storePassword = "android"
            keyAlias = "testkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("testkey")
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
