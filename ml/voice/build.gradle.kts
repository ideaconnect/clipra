plugins {
    id("clipra.android.library")
}

android {
    namespace = "tech.idct.clipra.ml.voice"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // The ABIs the app ships (arm64-v8a) and runs on emulators (x86_64).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // What ONNX Runtime's native code reaches by name, kept when the app is shrunk.
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    api(libs.onnxruntime.android)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
