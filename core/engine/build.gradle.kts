plugins {
    id("clipra.android.library")
}

android {
    namespace = "tech.idct.clipra.core.engine"
}

dependencies {
    api(libs.androidx.media3.common)
    api(libs.androidx.media3.effect)
    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.transformer)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
