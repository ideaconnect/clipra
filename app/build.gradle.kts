plugins {
    id("clipra.android.application")
    id("clipra.android.compose")
    id("clipra.hilt")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "tech.idct.clipra"

    defaultConfig {
        applicationId = "tech.idct.clipra"
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // Release builds ship arm64-v8a only (docs/architecture.md, section 1).
            abiFilters += "arm64-v8a"
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
