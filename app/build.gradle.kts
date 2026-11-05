plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.eightzip"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.eightzip"
        minSdk = 24
        targetSdk = 35
        // CI passes the run number so every release has a higher versionCode.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "0.1.${System.getenv("VERSION_CODE") ?: "0"}"
    }
    signingConfigs {
        // The key is committed encrypted (PKCS12, AES-256); its password lives in the
        // ANDROID_KEYSTORE_PASSWORD secret. Without it the release build is simply unsigned.
        System.getenv("ANDROID_KEYSTORE_PASSWORD")?.filterNot { it.isWhitespace() }?.let { pw ->
            create("release") {
                storeFile = file("release.p12")
                storeType = "pkcs12"
                storePassword = pw
                keyAlias = "eightzip"
                keyPassword = pw
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES") }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Archive formats: zip, 7z (LZMA2), tar, tar.gz
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    // AES-256 encrypted zip read/write
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    // RAR extraction (RAR 1-4; RAR5 is not supported by this library)
    implementation("com.github.junrar:junrar:7.5.5")
    // RAR5 reading via libarchive (native, Android only)
    implementation("me.zhanghai.android.libarchive:library:1.1.4")

    testImplementation("junit:junit:4.13.2")
}
