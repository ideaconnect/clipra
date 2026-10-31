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

// The AdMob app id and the export interstitial unit. Debug builds use Google's sample app and
// test unit, which only serve test ads; release builds take Clipra's from clipraAdMobAppId and
// clipraAdUnitInterstitial, like the Play key.
val adMobAppId = providers.gradleProperty("clipraAdMobAppId").orNull.orEmpty().trim()
val adUnitInterstitial =
    providers.gradleProperty("clipraAdUnitInterstitial").orNull.orEmpty().trim()
val sampleAdMobAppId = "ca-app-pub-3940256099942544~3347511713"
val testAdUnitInterstitial = "ca-app-pub-3940256099942544/1033173712"

android {
    namespace = "tech.idct.clipra.monetization"

    defaultConfig {
        buildConfigField("String", "PLAY_LICENSE_KEY", "\"$obfuscatedPlayKey\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "ADMOB_APP_ID", "\"$sampleAdMobAppId\"")
            manifestPlaceholders["adMobAppId"] = sampleAdMobAppId
            buildConfigField("String", "AD_UNIT_INTERSTITIAL", "\"$testAdUnitInterstitial\"")
        }
        release {
            buildConfigField("String", "ADMOB_APP_ID", "\"$adMobAppId\"")
            manifestPlaceholders["adMobAppId"] = adMobAppId
            buildConfigField("String", "AD_UNIT_INTERSTITIAL", "\"$adUnitInterstitial\"")
        }
        // The app's benchmark builds (#77): release code with Google's test ads, so they need
        // none of Clipra's keys.
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            buildConfigField("String", "ADMOB_APP_ID", "\"$sampleAdMobAppId\"")
            manifestPlaceholders["adMobAppId"] = sampleAdMobAppId
            buildConfigField("String", "AD_UNIT_INTERSTITIAL", "\"$testAdUnitInterstitial\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

// Without the Play key a release build could never grant Pro, and without the AdMob ids it could
// show no ads, so it is not built at all: the app's release build runs this check
// (app/build.gradle.kts). Debug builds trust purchases when the key is missing; benchmark builds
// grant nothing and show Google's test ads.
tasks.register("checkReleaseKeys") {
    val playKey = playLicenseKey.isNotEmpty()
    val appId = adMobAppId.isNotEmpty()
    val unit = adUnitInterstitial.isNotEmpty()
    doLast {
        check(playKey) { "Set clipraPlayLicenseKey (the Play public key) to build a release" }
        check(appId) { "Set clipraAdMobAppId (the AdMob app id) to build a release" }
        check(unit) { "Set clipraAdUnitInterstitial (the export ad unit) to build a release" }
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.play.billing)
    implementation(libs.ump)
    implementation(libs.ads.mobile.sdk)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.kotlinx.coroutines.test)
}
