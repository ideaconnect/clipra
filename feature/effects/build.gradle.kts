plugins {
    id("clipra.android.feature")
    id("clipra.hilt")
    id("clipra.android.screenshots")
}

android {
    namespace = "tech.idct.clipra.feature.effects"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:engine"))
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // The Cutout board's view model test runs on WorkManager's test setup for the person
    // search it queues and cancels (#240).
    testImplementation(libs.androidx.work.testing)
}
