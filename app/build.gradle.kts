plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.flipcover"
    compileSdk = 35

    defaultConfig {
        // アプリの ID。見本用の「com.example」は怪しいアプリに使われやすく、Play プロテクトに止められるので、自分の名前にする
        // （コードの置き場所の namespace は com.example.flipcover のまま）
        applicationId = "io.github.yukichi006.flipcover"
        minSdk = 31
        targetSdk = 35
        // CI の実行番号を使い、更新のたびに大きくなるようにする
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
        // 表示用のバージョンは VERSION ファイル（メジャー.マイナー.パッチ）。Releases のタグ（v<バージョン>）と揃え、
        // Obtainium が新旧を比べられるようにする
        versionName = rootProject.file("VERSION").readText().trim()
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
        // 配るのは正式版（デバッグできない）。署名は上の固定鍵
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// ライブラリは使わない（Android の標準機能と Kotlin だけで作る。FlipHome と同じ）
