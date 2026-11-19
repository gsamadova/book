plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.guny90.reader"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.guny90.reader"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 strips the unused parts of PdfBox: a much smaller APK.
            isMinifyEnabled = true
            isShrinkResources = true
            // Same key as debug builds, so a release APK installs over a debug one, keeping
            // books, positions and settings. Fine for a personal app that isn't published.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // Reads the words on PDF pages, for selecting them. BouncyCastle is only needed for PDFs
    // encrypted with certificates (rare); password and "no copying" encryption works without it.
    implementation(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303") // real org.json for JVM unit tests (Android's is a stub)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}