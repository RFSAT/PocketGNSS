plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The one place to change the app version.
val appVersion = "1.12.19"
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }

android {
    namespace = "com.rfsat.pocketgnss"
    compileSdk = 36
    // Phone RTK engine (RTKLIB demo5, C): pinned NDK and CMake so local and CI builds match.
    ndkVersion = "29.0.14206865"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

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
            // Only compiled for the unit tests and screenshots (CI builds no debug APK).
            isPseudoLocalesEnabled = true
        }
        release {
            // Symbol tables of our native code (RTKLIB engine) go inside the AAB, where Play reads them for
            // crash reports: nothing to upload by hand.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
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

// Host (Linux) build of the phone RTK engine, so the JVM unit tests run the same C code through the same JNI
// bridge (RtkEngineTest, a real two-station RTK session). Skipped on other systems or without a C compiler;
// the test then skips itself.
val hostRtkDir = layout.buildDirectory.dir("hostlib")
val buildHostRtk by tasks.registering(Exec::class) {
    val cpp = file("src/main/cpp")
    val outDir = hostRtkDir.get().asFile
    val javaHome = File(System.getProperty("java.home"))
    val sources = listOf("prtk.c", "prtk_jni.c").map { File(cpp, it).path } +
        listOf("rtkcmn", "rtkpos", "lambda", "ephemeris", "preceph", "sbas", "ionex", "pntpos", "ppp", "ppp_ar", "rtcm", "rtcm2",
            "rtcm3", "rtcm3e", "tides", "geoid", "trace").map { File(cpp, "rtklib/$it.c").path }
    inputs.dir(cpp)
    outputs.dir(outDir)
    onlyIf { System.getProperty("os.name").startsWith("Linux") && File(javaHome, "include/jni.h").exists() }
    doFirst { outDir.mkdirs() }
    isIgnoreExitValue = true
    commandLine(listOf("cc", "-shared", "-fPIC", "-O2", "-std=gnu99", "-w", "-DENAGLO", "-DENAGAL", "-DENAQZS", "-DENACMP",
        "-DENAIRN", "-DNFREQ=3", "-DNEXOBS=0", "-I${javaHome}/include", "-I${javaHome}/include/linux", "-I${cpp}/rtklib", "-I$cpp",
        "-o", File(outDir, "libprtk.so").path) + sources + listOf("-lm"))
}
tasks.withType<Test>().configureEach {
    dependsOn(buildHostRtk)
    // Full stack traces in the console, so a CI failure can be diagnosed from the log alone.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    systemProperty("java.library.path", hostRtkDir.get().asFile.path)
    // Optional: a folder with all PROJ-data geoid grids of HeightModels (-PgeoidDir=...), to check every model against PROJ.
    (project.findProperty("geoidDir") as String?)?.let { systemProperty("geoid.dir", it) }
    // Optional: a phone raw session (gnss_log.txt + base.rtcm3) to replay through the engine (SessionAnalysis).
    (project.findProperty("sessionDir") as String?)?.let { systemProperty("session.dir", it) }
    (project.findProperty("sessionVariants") as String?)?.let { systemProperty("session.variants", it) }
    (project.findProperty("sessionDump") as String?)?.let { systemProperty("session.dump", it) }
    (project.findProperty("sessionTrials") as String?)?.let { systemProperty("session.trials", it) }
    (project.findProperty("sessionSlots") as String?)?.let { systemProperty("session.slots", it) }
    (project.findProperty("sessionRinex") as String?)?.let { systemProperty("session.rinex", it) }
    (project.findProperty("sessionReplay") as String?)?.let { systemProperty("session.replay", it) }
}
