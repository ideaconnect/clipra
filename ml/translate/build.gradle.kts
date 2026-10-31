import java.net.URI
import java.security.MessageDigest

plugins {
    id("clipra.android.library")
}

// Marian NMT (browsermt/marian-dev, MIT) and the submodules a CPU build needs, from their GitHub
// archives at the commits Marian pins, checked with SHA-256 (ADR 0007). The sources are fetched
// at build time rather than kept in the repository.
class Source(val repo: String, val commit: String, val sha256: String, val path: String)

val marianSources = listOf(
    Source(
        "browsermt/marian-dev",
        "2781d735d4a10dca876d61be587afdab2726293c",
        "96f3f31a79899f26217b5fa0a78d6e8a6bc5e7a8e4c50de7bde4c3fa2858999d",
        "",
    ),
    Source(
        "browsermt/sentencepiece",
        "ae41b7740d7006596bb9257e83340b2620db9d00",
        "4d2148ae7e240cd113586004d2cf0102f7ab1bae363b91b2934ee83c5ae3dbbe",
        "src/3rd_party/sentencepiece",
    ),
    Source(
        "kpu/intgemm",
        "f7401513da71758dacce52fed1c7855549abee59",
        "8e827c445dbb1b117a9667616bac23b3c945e626da0a3466786cfd058ed2f41d",
        "src/3rd_party/intgemm",
    ),
    Source(
        "google/ruy",
        "2d950b3bfa7ebfbe7a97ecb44b1cc4da5ac1d6f0",
        "6593ec4323ae69e542bde97711d5680069cb802301512063289b1103c5374244",
        "src/3rd_party/ruy",
    ),
    Source(
        "pytorch/cpuinfo",
        "5916273f79a21551890fd3d56fc5375a78d1598d",
        "f3c16d5d393d6d1fa6b6ed8621dd0a535552df9bc88cbba739375dde38a93142",
        "src/3rd_party/ruy/third_party/cpuinfo",
    ),
    Source(
        "browsermt/simd_utils",
        "d0793d86aea9036a5bc77b9ca7791dff024168ca",
        "829325e164ee2be5dffed51ccf8cc4b776edbb4269d174e79f6708fd89fca59f",
        "src/3rd_party/simd_utils",
    ),
)
val marianArchives = layout.buildDirectory.dir("marian-archives")

// A short path: Windows limits paths to 260 characters, and sentencepiece's are long.
val marianRoot = layout.buildDirectory.dir("m")

fun archiveName(source: Source) = source.repo.replace('/', '-') + "-" + source.commit + ".tar.gz"

val downloadMarian = tasks.register("downloadMarian") {
    val folder = marianArchives
    val wanted = marianSources.map { Triple(it.repo, it.commit, it.sha256) }
    outputs.dir(folder)
    doLast {
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
        for ((repo, commit, expected) in wanted) {
            val file = folder.get().file(repo.replace('/', '-') + "-$commit.tar.gz").asFile
            if (file.exists() && sha(file) == expected) continue
            file.parentFile.mkdirs()
            val url = "https://github.com/$repo/archive/$commit.tar.gz"
            URI(url).toURL().openStream().use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            check(sha(file) == expected) { "$url does not match its SHA-256" }
        }
    }
}

val unpackMarian = tasks.register<Sync>("unpackMarian") {
    dependsOn(downloadMarian)
    for (source in marianSources) {
        val depth = if (source.path.isEmpty()) 0 else source.path.split('/').size
        from(tarTree(resources.gzip(marianArchives.map { it.file(archiveName(source)) }))) {
            // Each archive has one top folder, named after the repository and commit, which
            // follows the folder the archive goes into.
            eachFile {
                val segments = relativePath.segments
                val kept = segments.take(depth) + segments.drop(depth + 1)
                relativePath = RelativePath(true, *kept.toTypedArray())
            }
            includeEmptyDirs = false
            if (source.path.isNotEmpty()) into(source.path)
        }
    }
    into(marianRoot)
    val root = marianRoot
    doLast {
        // Marian writes its git revision from .git/logs/HEAD; an archive has neither, so both
        // are made here, the header newer than the log so the build never runs git.
        val head = root.get().file(".git/logs/HEAD").asFile
        head.parentFile.mkdirs()
        head.writeText("2781d735d4a10dca876d61be587afdab2726293c\n")
        head.setLastModified(0)
        root.get().file("src/common/git_revision.h").asFile
            .writeText("#define GIT_REVISION \"2781d735 2024-05-12\"\n")
    }
}

android {
    namespace = "tech.idct.clipra.ml.translate"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // The ABIs the app ships (arm64-v8a) and runs on emulators (x86_64).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                val source = marianRoot.get().asFile
                arguments += "-DMARIAN_SOURCE=${source.invariantSeparatorsPath}"
                // A debug build of Marian is too slow to translate anything.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                targets += "clipra_translate"
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
        dependsOn(unpackMarian)
    }
    // Marian's configure step writes into its source tree, so the ABIs take turns.
    if (name.startsWith("configureCMake") && name.contains("x86_64")) {
        mustRunAfter(name.replace("x86_64", "arm64-v8a"))
    }
}

dependencies {
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
