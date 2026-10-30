import java.util.Base64

plugins {
    id("clipra.android.feature")
    id("clipra.hilt")
    id("clipra.android.screenshots")
}

// The app's Play public key (Play Console, Monetization setup, Licensing), for checking purchase
// signatures on the device. Set clipraPlayLicenseKey in ~/.gradle/gradle.properties or as the
// ORG_GRADLE_PROJECT_clipraPlayLicenseKey environment variable. It is stored XOR-ed with the mask
// of PurchaseVerifier.KEY_MASK, so the plain key is not one string search away in the APK.
val playLicenseKey = providers.gradleProperty("clipraPlayLicenseKey").orNull.orEmpty().trim()
val obfuscatedPlayKey: String = run {
    val mask = "tech.idct.clipra.pro".toByteArray()
    val bytes = playLicenseKey.toByteArray()
    val masked =
        ByteArray(bytes.size) { i -> (bytes[i].toInt() xor mask[i % mask.size].toInt()).toByte() }
    Base64.getEncoder().encodeToString(masked)
}

android {
    namespace = "tech.idct.clipra.monetization"

    defaultConfig {
        buildConfigField("String", "PLAY_LICENSE_KEY", "\"$obfuscatedPlayKey\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

// Without the key a release build could never grant Pro, so it is not built at all. Debug builds
// trust purchases when the key is missing.
val checkPlayLicenseKey = tasks.register("checkPlayLicenseKey") {
    val present = playLicenseKey.isNotEmpty()
    doLast {
        check(present) { "Set clipraPlayLicenseKey (the Play public key) to build a release" }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(checkPlayLicenseKey)
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.play.billing)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.kotlinx.coroutines.test)
}
