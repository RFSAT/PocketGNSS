plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The one place to change the app version.
val appVersion = "1.3.0"
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }

android {
    namespace = "com.rfsat.pocketgnss"
    compileSdk = 36

    defaultConfig {
        // Permanent once published on Google Play.
        applicationId = "com.PocketGNSS"
        minSdk = 26
        targetSdk = 36
        // Edit only appVersion (top of this file). The Play version code follows from it:
        // 1.MINOR.PATCH -> 1*10000 + MINOR*100 + PATCH (1.1.2 -> 10102).
        versionCode = appVersionCode
        versionName = appVersion
        // Launcher label only; the screens always show app_name ("PocketGNSS"), also in screenshots.
        manifestPlaceholders["appLabel"] = "PocketGNSS"
    }

    signingConfigs {
        // Fixed debug key kept in the repository, so every CI debug APK installs over the previous one
        // (the runner would otherwise sign each build with a new random debug key). Not used for Play.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            // Same secret names as the other RFSAT apps (DBM, BAS, TinyRAD, PocketSpectro); absent locally,
            // in which case the release build stays unsigned.
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Side-by-side with the Play version: com.PocketGNSS.debug, labelled "PocketGNSS debug" in the launcher.
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "PocketGNSS debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Screenshots (Robolectric + Roborazzi) are only rendered when asked for:
            //   ./gradlew :app:testDebugUnitTest -Pscreenshots
            it.systemProperty("roborazzi.test.record", "true")
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            if (!project.hasProperty("screenshots")) it.exclude("**/ScreenshotTest*")
            project.findProperty("robolectricRepo")?.let { url -> it.systemProperty("robolectric.dependency.repo.url", url.toString()) }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Same map engine and raster layers as DBM and PocketSpectro.
    implementation("org.maplibre.gl:android-sdk:13.0.0")
    // USB serial drivers: CDC-ACM (u-blox ZED-F9x native USB), CP210x (ESP32-DevKitC), CH34x, FTDI.
    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
