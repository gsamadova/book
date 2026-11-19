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

    // The release key lives outside the repo. Set BOOK_STORE_FILE, BOOK_STORE_PASSWORD and
    // BOOK_KEY_ALIAS in ~/.gradle/gradle.properties; without them, release builds are unsigned.
    // It's the same key that signed 1.0 and 1.1, so updates install over those versions.
    val storeFile = providers.gradleProperty("BOOK_STORE_FILE").orNull
    signingConfigs {
        if (storeFile != null) create("release") {
            this.storeFile = file(storeFile)
            storePassword = providers.gradleProperty("BOOK_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("BOOK_KEY_ALIAS").get()
            keyPassword = storePassword
        }
    }

    buildTypes {
        release {
            // R8 strips the unused parts of PdfBox: a much smaller APK.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
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