plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vwshell.auto"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vwshell.auto"
        minSdk = 29
        targetSdk = 30 // Целевой SDK 30: стабильная работа на Android 10–12 без new-SDK ограничений
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("ru", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
}
