plugins {
    id("clipra.android.feature")
    id("clipra.hilt")
    id("clipra.android.screenshots")
}

android {
    namespace = "tech.idct.clipra.feature.editor"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:engine"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // The editor's tests build its view model from the real engine classes (#213), on
    // WorkManager's test setup for the proxies it queues (#217).
    testImplementation(project(":core:models"))
    testImplementation(libs.androidx.work.testing)
}
