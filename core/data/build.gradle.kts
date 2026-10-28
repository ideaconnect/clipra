plugins {
    id("clipra.android.library")
    id("clipra.hilt")
    id("clipra.android.room")
}

android {
    namespace = "tech.idct.clipra.core.data"
}

dependencies {
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.kotlinx.coroutines.test)
}
