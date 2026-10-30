import java.net.URI
import java.security.MessageDigest

plugins {
    id("clipra.android.library")
}

/**
 * Puts a model from its official source into the APK's assets: downloaded once into a shared
 * cache, checked with SHA-256, then copied into the variant's generated assets.
 */
abstract class DownloadModel : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:Input
    abstract val assetPath: Property<String>

    @get:Internal
    abstract val cache: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val cached = cache.get().asFile
        if (!cached.exists() || sha(cached) != sha256.get()) {
            cached.parentFile.mkdirs()
            URI(url.get()).toURL().openStream().use { input ->
                cached.outputStream().use { input.copyTo(it) }
            }
            check(sha(cached) == sha256.get()) { "${url.get()} does not match its SHA-256" }
        }
        val target = outputDir.get().asFile.resolve(assetPath.get())
        target.parentFile.mkdirs()
        cached.copyTo(target, overwrite = true)
    }

    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}

// The MediaPipe selfie segmenter (Apache-2.0) from Google's model storage, pinned to its version
// and checked with SHA-256, goes into the APK's assets at build time. It isn't kept in the
// repository, and Clipra never hosts it.
val segmenterUrl = "https://storage.googleapis.com/mediapipe-models/image_segmenter/" +
    "selfie_segmenter/float16/1/selfie_segmenter.tflite"
val segmenterSha256 = "191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b"

android {
    namespace = "tech.idct.clipra.ml.vision"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // LiteRT maps the model straight from the APK, so it stays uncompressed.
    androidResources {
        noCompress += "tflite"
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<DownloadModel>("download${name}VisionModels") {
            url.set(segmenterUrl)
            sha256.set(segmenterSha256)
            assetPath.set("models/selfie_segmenter.tflite")
            cache.set(layout.buildDirectory.file("models-cache/selfie_segmenter.tflite"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, DownloadModel::outputDir)
    }
}

dependencies {
    api(libs.litert)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
