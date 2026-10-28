plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vwshell.auto"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vwshell.auto"
        // HU F9210A: китайская прошивка заявляет Android 13, фактически API 29-30 и 32-битная
        // система (armeabi-v7a). minSdk 26 покрывает реальный API этой магнитолы.
        minSdk = 26
        // Целевой SDK 33 — обязательная адаптация под Android 13 (API 33).
        // BLUETOOTH_CONNECT/READ_CONTACTS запрашиваются через Activity Result API по месту
        // использования; Notification Listener Access — специальный доступ системы.
        targetSdk = 33
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("ru", "en")
        // Универсальный APK: 32-битная HU F9210A требует armeabi-v7a наряду с arm64-v8a.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        // Векторные drawable через AppCompat — надёжно на урезанных стеках HU (API 26-30).
        vectorDrawables {
            useSupportLibrary = true
        }
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
    testImplementation("junit:junit:4.13.2")
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
