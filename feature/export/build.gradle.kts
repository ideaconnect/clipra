plugins {
    id("clipra.android.feature")
    id("clipra.hilt")
    id("clipra.android.screenshots")
}

android {
    namespace = "tech.idct.clipra.feature.export"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:engine"))
    implementation(project(":monetization"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // The missing media test builds the Export view model from the real classes (#212).
    testImplementation(libs.androidx.datastore.preferences)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Compose UI test brings Espresso 3.5, which fails on Android 16 and later.
    androidTestImplementation(libs.androidx.test.espresso.core)
}
