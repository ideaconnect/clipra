plugins {
    id("clipra.jvm.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    // Projects are versioned JSON documents (docs/architecture.md, section 3).
    implementation(libs.kotlinx.serialization.json)
}
