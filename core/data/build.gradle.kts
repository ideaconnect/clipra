plugins {
    id("clipra.android.library")
    id("clipra.hilt")
    id("clipra.android.room")
}

android {
    namespace = "tech.idct.clipra.core.data"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}

android {
    testOptions.unitTests.isIncludeAndroidResources = true
}
