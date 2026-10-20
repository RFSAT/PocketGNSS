pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android (CDC-ACM / CP210x / CH34x / FTDI drivers) is published on JitPack only.
        maven("https://jitpack.io") { content { includeGroup("com.github.mik3y") } }
    }
}
rootProject.name = "PocketGNSS"
include(":app")
