plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kinora.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kinora.tv"
        minSdk = 21
        targetSdk = 35
        versionCode = 5
        versionName = "1.5.3"
    }

    // Chave fixa do projeto (so para instalar na TV por fora da loja): assim cada APK novo
    // instala por cima do anterior sem precisar desinstalar.
    signingConfigs {
        create("kinora") {
            storeFile = file("kinora.jks")
            storePassword = "kinora123"
            keyAlias = "kinora"
            keyPassword = "kinora123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("kinora")
        }
        debug {
            signingConfig = signingConfigs.getByName("kinora")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("io.coil-kt:coil-compose:2.7.0")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // Motor P2P (fontes com infoHash): libtorrent4j + bibliotecas nativas das TVs (ARM) e emuladores (x86_64)
    val lt4j = "2.1.0-39"
    implementation("org.libtorrent4j:libtorrent4j:$lt4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$lt4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$lt4j")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$lt4j")
}
