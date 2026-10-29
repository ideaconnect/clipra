import java.net.URI
import java.security.MessageDigest

plugins {
    id("clipra.android.library")
}

// whisper.cpp (MIT) from its official release, checked with SHA-256 and built with CMake. The
// source is fetched at build time rather than kept in the repository.
val whisperVersion = "1.9.5"
val whisperSha256 = "ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720"
val whisperArchive = layout.buildDirectory.file("whisper/whisper.cpp-$whisperVersion.tar.gz")
val whisperRoot = layout.buildDirectory.dir("whisper/src")

val downloadWhisper = tasks.register("downloadWhisperCpp") {
    val archive = whisperArchive
    val url = "https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v$whisperVersion.tar.gz"
    val expected = whisperSha256
    outputs.file(archive)
    doLast {
        val file = archive.get().asFile
        val sha = { f: File ->
            val digest = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (file.exists() && sha(file) == expected) return@doLast
        file.parentFile.mkdirs()
        URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        check(sha(file) == expected) { "whisper.cpp $url does not match its SHA-256" }
    }
}

val unpackWhisper = tasks.register<Sync>("unpackWhisperCpp") {
    dependsOn(downloadWhisper)
    from(tarTree(resources.gzip(whisperArchive)))
    into(whisperRoot)
}

android {
    namespace = "tech.idct.clipra.ml.whisper"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // The ABIs the app ships (arm64-v8a) and runs on emulators (x86_64).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                val source = whisperRoot.get().asFile.resolve("whisper.cpp-$whisperVersion")
                arguments += "-DWHISPER_SOURCE=${source.invariantSeparatorsPath}"
                // Debug builds of ggml are too slow to measure anything.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(unpackWhisper)
    }
}

dependencies {
    // The spike decodes its test clips with the engine's decoder.
    androidTestImplementation(project(":core:engine"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
