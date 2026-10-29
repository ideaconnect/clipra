plugins {
    id("clipra.android.library")
    id("clipra.hilt")
}

android {
    namespace = "tech.idct.clipra.core.models"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}

android {
    testOptions.unitTests.isIncludeAndroidResources = true
}
