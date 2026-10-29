plugins {
    id("clipra.android.library")
    id("clipra.hilt")
}

android {
    namespace = "tech.idct.clipra.core.engine"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:fonts"))
    api(libs.androidx.media3.common)
    api(libs.androidx.media3.effect)
    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.transformer)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
