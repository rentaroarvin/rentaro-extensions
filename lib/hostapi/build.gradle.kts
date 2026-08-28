plugins {
    alias(libs.plugins.android.library)

    alias(kei.plugins.android.base)
    alias(kei.plugins.spotless)
}

android {
    namespace = "aniyomi.lib.hostapi"

    sourceSets {
        named("main") {
            java.directories.clear()
            java.directories.add("src")
            kotlin.directories.clear()
            kotlin.directories.add("src")
        }
    }

    androidResources.enable = false
}

dependencies {
    // The declarations here mirror an ABI the host owns, so they are compiled
    // against and never packaged. `compileOnly` on both sides - here and in the
    // extension that consumes this module - is what keeps them out of the APK.
    compileOnly(libs.bundles.common)
}
