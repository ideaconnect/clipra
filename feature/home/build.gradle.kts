plugins {
    id("clipra.android.feature")
    id("clipra.hilt")
    id("clipra.android.screenshots")
}

android {
    namespace = "tech.idct.clipra.feature.home"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:engine"))
    implementation(project(":core:models"))
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
