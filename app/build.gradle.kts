plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.flipcover"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.flipcover"
        minSdk = 31
        targetSdk = 35
        // CI の実行番号を使い、更新のたびに大きくなるようにする
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
        versionName = "1.0"
    }

    signingConfigs {
        // CI では Secrets の固定鍵で署名し、上書き更新できるようにする
        getByName("debug") {
            System.getenv("FLIPCOVER_KEYSTORE")?.let {
                storeFile = file(it)
                storePassword = System.getenv("FLIPCOVER_KEYSTORE_PASSWORD")
                keyAlias = "flipcover"
                keyPassword = System.getenv("FLIPCOVER_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
