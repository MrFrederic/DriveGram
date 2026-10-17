import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// api_id / api_hash live in local.properties (never committed), from my.telegram.org.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.drivegram"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "app.drivegram"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"
        buildConfigField("int", "TG_API_ID", local.getProperty("telegram.apiId", "0").ifBlank { "0" })
        buildConfigField("String", "TG_API_HASH", "\"${local.getProperty("telegram.apiHash", "")}\"")
    }
    // Release signing comes from GitHub Actions secrets (see .github/workflows/release.yml). Without them the release APK is unsigned.
    val keystore = System.getenv("DRIVEGRAM_KEYSTORE")
    if (keystore != null) {
        signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("DRIVEGRAM_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("DRIVEGRAM_KEY_ALIAS")
            keyPassword = System.getenv("DRIVEGRAM_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions.unitTests {
        isIncludeAndroidResources = true // Robolectric needs the merged manifest and resources
        all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") } // Robolectric on JDK 21+
    }
}

dependencies {
    implementation(project(":tdlib"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    // Material 3 Expressive (MaterialExpressiveTheme, segmented lists, shape morphing) is only in 1.5; the BOM still pins 1.4.
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.car.app:app-testing:1.7.0")
}
