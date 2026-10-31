plugins {
    id("clipra.android.application")
    id("clipra.android.compose")
    id("clipra.hilt")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The version (#78) comes from the release tag: the release workflow passes v1.2.3 as
// clipraVersion=1.2.3, which gives versionName 1.2.3 and versionCode 10203, so every release goes
// up. Other builds are 0.1.0.
val clipraVersion = providers.gradleProperty("clipraVersion").orNull?.trim() ?: "0.1.0"
val (versionMajor, versionMinor, versionPatch) =
    Regex("""(\d{1,3})\.(\d{1,2})\.(\d{1,2})""").matchEntire(clipraVersion)
        ?.destructured?.toList()?.map(String::toInt)
        ?: error("clipraVersion is major.minor.patch, minor and patch below 100: $clipraVersion")

// The upload key (#78): Play checks it on every upload and signs the app with the app signing
// key it keeps. Set clipraUploadKeystore (the keystore file), clipraUploadKeystorePassword,
// clipraUploadKeyAlias and clipraUploadKeyPassword like the other keys
// (docs/release/publishing.md).
fun secret(name: String) = providers.gradleProperty(name).orNull.orEmpty()
val uploadKeystore = secret("clipraUploadKeystore").trim()

android {
    namespace = "tech.idct.clipra"

    defaultConfig {
        applicationId = "tech.idct.clipra"
        versionCode = versionMajor * 10_000 + versionMinor * 100 + versionPatch
        versionName = clipraVersion
        ndk {
            // Release builds ship arm64-v8a only (docs/architecture.md, section 1).
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (uploadKeystore.isNotEmpty()) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = secret("clipraUploadKeystorePassword")
                keyAlias = secret("clipraUploadKeyAlias")
                keyPassword = secret("clipraUploadKeyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // The emulators we develop on are x86_64.
            ndk {
                abiFilters += "x86_64"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("upload")
            // The native libraries' symbols go into the bundle, so Play shows readable native
            // crashes (whisper.cpp, RNNoise).
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
        // Release as it ships, but signed with the debug key and with Google's test ads, so
        // the benchmarks (#77) measure what users run, on any phone, without Clipra's keys.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            // Readable names, so the baseline profile recorded on it applies to the release build;
            // R8 renames the profile's entries when it obfuscates the release.
            proguardFile("benchmark-rules.pro")
            // And the emulators, for trying the benchmarks out.
            ndk {
                abiFilters += "x86_64"
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":feature:home"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:effects"))
    implementation(project(":feature:text"))
    implementation(project(":feature:captions"))
    implementation(project(":feature:audio"))
    implementation(project(":feature:export"))
    implementation(project(":feature:models"))
    implementation(project(":feature:settings"))
    implementation(project(":monetization"))

    implementation(libs.androidx.activity.compose)
    // Installs the baseline profile (#77) so startup and the editor are compiled ahead of time.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    // Compose UI test brings Espresso 3.5, which fails on Android 16 and later.
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// A release ships only signed with the upload key and with Clipra's Play key and AdMob ids. The
// checks are here, not in :monetization, since the benchmark build (#77) compiles the other
// modules' release variants.
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    val keystore = uploadKeystore.takeIf { it.isNotEmpty() }?.let { file(it) }
    val passwords = listOf(
        "clipraUploadKeystorePassword",
        "clipraUploadKeyAlias",
        "clipraUploadKeyPassword",
    ).filter { secret(it).isEmpty() }
    doLast {
        check(keystore != null) { "Set clipraUploadKeystore (the upload key) to build a release" }
        check(keystore.isFile) { "The upload keystore $keystore is not there" }
        check(passwords.isEmpty()) { "Set ${passwords.joinToString()} to build a release" }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") {
        dependsOn(":monetization:checkReleaseKeys")
        dependsOn(checkReleaseSigning)
    }
}
