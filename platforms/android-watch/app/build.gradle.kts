plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.motogps.watch"
    // 注：android-35 的公开 stub jar 缺失 BluetoothAdapter.openGattServer，故编译目标用 34
    compileSdk = 34

    defaultConfig {
        applicationId = "com.motogps.watch"
        minSdk = 28
        targetSdk = 34
        versionCode = 17
        versionName = "0.17.0"
    }

    buildTypes {
        release {
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
