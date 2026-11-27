import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// One version for Windows and Android: the VERSION file at the top of the repo.
val roninVersion: String = File(rootDir.parentFile, "VERSION").readText().trim()
val roninCode: Int = roninVersion.split(".").map { it.toInt() }.let { it[0] * 10000 + it[1] * 100 + it[2] }

// Release signing: the build machine decrypts the key and sets these.
val keyFile = System.getenv("RONIN_KEYSTORE")
val keyPass = System.getenv("RONIN_KEYSTORE_PASS")

android {
    namespace = "com.ronin.browser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ronin.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = roninCode
        versionName = roninVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Two apps from one code (21 Nov 2026, Play Protect): Ronin Browser (com.ronin.browser) has no SMS / call log /
    // accessibility; Ronin Phone (com.ronin.phone) is only Messages + Calls. Their manifests: src/browser, src/phone.
    flavorDimensions += "app"
    productFlavors {
        create("browser") { dimension = "app"; applicationId = "com.ronin.browser" }
        create("phone") { dimension = "app"; applicationId = "com.ronin.phone" }
        // pilot of the Ronin Agent (Claude works in the owner's own browser): a separate app, so tests on his real
        // phone / tablet never touch his Ronin or its data
        create("lab") { dimension = "app"; applicationId = "com.ronin.agentlab" }
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
            if (keyFile != null && keyPass != null) signingConfig = signingConfigs.getByName("ronin")
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

// Ronin's shared rules (bank / trading / call sites, ad rules, page scripts) come
// from the Go code, so Windows and Android always follow the same rules.
val exportRules by tasks.registering(Exec::class) {
    workingDir = rootDir.parentFile
    commandLine("go", "run", "./tools/exportlists", "-assets", "android/app/src/main/assets/ronin")
}
tasks.named("preBuild") { dependsOn(exportRules) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media:media:1.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
