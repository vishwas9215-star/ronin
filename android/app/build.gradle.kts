import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val homeVersion: String = File(rootDir.parentFile, "VERSION").readText().trim()
val homeCode: Int = homeVersion.split(".").map { it.toInt() }.let { it[0] * 10000 + it[1] * 100 + it[2] }

// Same release key as Ronin Browser (decrypted on the build machine only).
val keyFile = System.getenv("RONIN_KEYSTORE")
val keyPass = System.getenv("RONIN_KEYSTORE_PASS")

android {
    namespace = "com.ronin.home"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ronin.home"
        minSdk = 26
        targetSdk = 34
        versionCode = homeCode
        versionName = homeVersion
    }

    signingConfigs {
        if (keyFile != null && keyPass != null) {
            create("ronin") {
                storeFile = file(keyFile)
                storePassword = keyPass
                keyAlias = "ronin"
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keyFile != null && keyPass != null) signingConfigs.getByName("ronin")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = false }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
