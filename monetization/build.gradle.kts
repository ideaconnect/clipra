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

// The AdMob app id. Debug builds use Google's sample app, which only serves test ads; release
// builds take Clipra's from clipraAdMobAppId, like the Play key.
val adMobAppId = providers.gradleProperty("clipraAdMobAppId").orNull.orEmpty().trim()
val sampleAdMobAppId = "ca-app-pub-3940256099942544~3347511713"

android {
    namespace = "tech.idct.clipra.monetization"

    defaultConfig {
        buildConfigField("String", "PLAY_LICENSE_KEY", "\"$obfuscatedPlayKey\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "ADMOB_APP_ID", "\"$sampleAdMobAppId\"")
            manifestPlaceholders["adMobAppId"] = sampleAdMobAppId
        }
        release {
            buildConfigField("String", "ADMOB_APP_ID", "\"$adMobAppId\"")
            manifestPlaceholders["adMobAppId"] = adMobAppId
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

// Without the Play key a release build could never grant Pro, and without the AdMob app id it
// could show no ads, so it is not built at all. Debug builds trust purchases when the key is
// missing.
val checkReleaseKeys = tasks.register("checkReleaseKeys") {
    val playKey = playLicenseKey.isNotEmpty()
    val appId = adMobAppId.isNotEmpty()
    doLast {
        check(playKey) { "Set clipraPlayLicenseKey (the Play public key) to build a release" }
        check(appId) { "Set clipraAdMobAppId (the AdMob app id) to build a release" }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(checkReleaseKeys)
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.play.billing)
    implementation(libs.ump)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.kotlinx.coroutines.test)
}
