package tech.idct.clipra.core.models

import java.util.Locale

/** What a model is for: the groups of the AI models screen. */
enum class ModelGroup { Captions, VoiceCleanup, Translation }

/**
 * A file as its official source publishes it: [url] pinned to a revision, its size in [bytes]
 * and its [sha256]. Clipra downloads it from there and nowhere else. A gzipped file with
 * [unpackedBytes] is unpacked after its check, to its [name] without ".gz".
 */
data class ModelFile(
    val url: String,
    val bytes: Long,
    val sha256: String,
    val unpackedBytes: Long? = null,
) {
    val name: String get() = url.substringAfterLast('/')

    /** The file's name once installed. */
    val installedName: String get() = if (unpackedBytes != null) name.removeSuffix(".gz") else name
}

/** A file kept from a downloaded archive: its [path] in the archive and its size. */
data class UnpackedFile(val path: String, val bytes: Long) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * An on-device model. Models with a [download] come from their official source; an archive
 * unpacks into [unpacked], and [moreFiles] come with it (a translation direction is three files,
 * #47). Models without one are built into the app. [isDefault] marks the one Clipra suggests.
 */
data class AiModel(
    val id: String,
    val name: String,
    val group: ModelGroup,
    val download: ModelFile? = null,
    val unpacked: List<UnpackedFile> = emptyList(),
    val moreFiles: List<ModelFile> = emptyList(),
    val isDefault: Boolean = false,
) {
    val builtIn: Boolean get() = download == null

    /**
     * The model's name to show: its own name, or for a translation direction (#47) its
     * languages in [locale], such as "English → Polish".
     */
    fun title(locale: Locale = Locale.getDefault()): String {
        if (group != ModelGroup.Translation) return name
        val (from, to) = name.split('-')
        fun language(code: String) = Locale.forLanguageTag(code).getDisplayLanguage(locale)
            .replaceFirstChar { it.titlecase(locale) }
        return "${language(from)} → ${language(to)}"
    }

    /** Every file the model downloads, [download] first. */
    val files: List<ModelFile> get() = listOfNotNull(download) + moreFiles

    /** The bytes a download of the model fetches. */
    val downloadBytes: Long get() = files.sumOf { it.bytes }

    /** The bytes the model takes once installed (docs/architecture.md, section 6). */
    val installedBytes: Long
        get() = if (unpacked.isEmpty()) {
            files.sumOf { it.unpackedBytes ?: it.bytes }
        } else {
            unpacked.sumOf { it.bytes } + moreFiles.sumOf { it.unpackedBytes ?: it.bytes }
        }
}

/**
 * The manifest of every model Clipra uses (docs/architecture.md, section 6): official sources
 * pinned to a commit, with sizes and SHA-256 checked on 2026-10-29. A file that moves or
 * changes fails its download; the fix ships in an app update, never through another host.
 */
object ModelCatalog {
    // The repository whisper.cpp's own download script uses, at its 2024-10-29 commit.
    private const val WHISPER =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/" +
            "5359861c739e955e79d9a303bcbc70fb988958b1/"

    // DeepFilterNet's v0.5.6 release (2023-08-31), by its commit.
    private const val DEEP_FILTER_NET =
        "https://github.com/Rikorose/DeepFilterNet/raw/" +
            "978576aa8400552a4ce9730838c635aa30db5e61/models/"

    val whisperTiny = AiModel(
        id = "whisper-tiny",
        name = "Tiny",
        group = ModelGroup.Captions,
        download = ModelFile(
            WHISPER + "ggml-tiny.bin",
            77_691_713,
            "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
        ),
    )

    val whisperBase = AiModel(
        id = "whisper-base",
        name = "Base",
        group = ModelGroup.Captions,
        download = ModelFile(
            WHISPER + "ggml-base.bin",
            147_951_465,
            "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        ),
        isDefault = true,
    )

    val whisperSmall = AiModel(
        id = "whisper-small",
        name = "Small",
        group = ModelGroup.Captions,
        download = ModelFile(
            WHISPER + "ggml-small.bin",
            487_601_967,
            "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
        ),
    )

    val whisperLargeTurbo = AiModel(
        id = "whisper-large-v3-turbo",
        name = "Large v3 Turbo",
        group = ModelGroup.Captions,
        download = ModelFile(
            WHISPER + "ggml-large-v3-turbo-q5_0.bin",
            574_041_195,
            "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        ),
    )

    val deepFilterNet = AiModel(
        id = "deepfilternet3",
        name = "DeepFilterNet 3",
        group = ModelGroup.VoiceCleanup,
        download = ModelFile(
            DEEP_FILTER_NET + "DeepFilterNet3_onnx.tar.gz",
            7_983_136,
            "c94d91f70911001c946e0fabb4aa9adc37045f45a03b56008cb0c8244cb63616",
        ),
        unpacked = listOf(
            UnpackedFile("tmp/export/enc.onnx", 1_954_042),
            UnpackedFile("tmp/export/erb_dec.onnx", 3_292_397),
            UnpackedFile("tmp/export/df_dec.onnx", 3_340_803),
            UnpackedFile("tmp/export/config.ini", 2_067),
        ),
    )

    /** RNNoise's weights are compiled in from the xiph/rnnoise source. */
    val rnnoise = AiModel(id = "rnnoise", name = "RNNoise", group = ModelGroup.VoiceCleanup)

    // Mozilla's Firefox Translations models (ADR 0007), from its model registry
    // (db/models.json, 2026-10-31): release "base-memory" models, each file at its training
    // run's own path, gzipped. A newer run means a new path and new checksums here.
    private const val MOZILLA = "https://storage.googleapis.com/" +
        "moz-fx-translations-data--303e-prod-translations-data/models/"

    /** A gzipped file of a translation model: its size, SHA-256 and size unpacked. */
    private class Gz(val bytes: Long, val sha256: String, val unpackedBytes: Long)

    private fun translation(
        from: String,
        to: String,
        run: String,
        model: Gz,
        vocab: Gz,
        shortlist: Gz,
    ): AiModel {
        val folder = "$MOZILLA$from-$to/$run/exported/"
        val pair = from + to
        fun file(name: String, gz: Gz) =
            ModelFile(folder + name, gz.bytes, gz.sha256, unpackedBytes = gz.unpackedBytes)
        return AiModel(
            id = "translate-$from-$to",
            name = "$from-$to",
            group = ModelGroup.Translation,
            download = file("model.$pair.intgemm.alphas.bin.gz", model),
            moreFiles = listOf(
                file("vocab.$pair.spm.gz", vocab),
                file("lex.50.50.$pair.s2t.bin.gz", shortlist),
            ),
        )
    }

    val translateEnPl = translation(
        "en",
        "pl",
        "retrain_hr_fix_names_YmTlfuUsSKCbUp75UXaUuw",
        model = Gz(
            22_577_447,
            "80b549e5f4d1d1db3f66f850c71683c9449a259e6eac2abe3b8f04f0781088ed",
            31_561_787,
        ),
        vocab = Gz(
            413_194,
            "13b8f1dc8bf7a29d052008d78aa4429b1311f8fd0fcb63c5490fe1714b00606c",
            821_612,
        ),
        shortlist = Gz(
            1_874_327,
            "6e7cbf38d9298877d9631a33a7bd3608326ba169be9a0fe9c7b2ee0c6c7e6d59",
            3_540_052,
        ),
    )

    val translatePlEn = translation(
        "pl",
        "en",
        "retrain_hr_HLLa6ZwsSomB0kyBr7Iz6w",
        model = Gz(
            22_630_393,
            "7f37aa5e065581c02fbd5fd4f3862a714944e223055edafe23095a68f3a49a76",
            31_561_787,
        ),
        vocab = Gz(
            413_194,
            "90f3d5a707eb8b221ab4139de6146e4741ab3196fbadf4562b03d727a0f8b822",
            821_612,
        ),
        shortlist = Gz(
            2_489_764,
            "7ad8ce0f56b7537a82e7e522a48ea440395a909c547621934cf70daa1c543c36",
            4_964_208,
        ),
    )

    val translateEnDe = translation(
        "en",
        "de",
        "retrain_hr_fix_names_SCgGhxUPQ2WAECHLRtzrMg",
        model = Gz(
            22_992_073,
            "e3a74458faba3ab2752ed8355c5e3979f13395279c8fc1bd3f54a3c2316e4975",
            31_561_787,
        ),
        vocab = Gz(
            413_879,
            "3b2ddbfac73f994f151a8994447d6b3d41ab0d3435da56f79c233f34d03e45c2",
            810_073,
        ),
        shortlist = Gz(
            2_314_750,
            "0370d79cda6fed5dfa8f9b2a0009459bd2766893825bb5b8b5114169d18da6c4",
            4_347_672,
        ),
    )

    val translateDeEn = translation(
        "de",
        "en",
        "retrain_hr2_WZOW24i4QJmIib61LeHfwg",
        model = Gz(
            22_403_755,
            "38b245bcfc23f6d61fd98a187436ea1320a38f41896df411b103df3e83f51681",
            31_561_787,
        ),
        vocab = Gz(
            413_879,
            "88dcd7fdbf20dc60837ce837244d0d55b20f7079f2cbd2ba8e63530632028310",
            810_073,
        ),
        shortlist = Gz(
            2_626_658,
            "231e9caf62e0e867775d5f4c1f4cfae21e37c70d9bf2bd628fa468aeeb2ba869",
            4_945_796,
        ),
    )

    /** The translation directions, named "from-to" by ISO 639-1 codes, to or from English. */
    val translations = listOf(translateEnPl, translatePlEn, translateEnDe, translateDeEn)

    /** The direction from [from] to [to], if Clipra has one. */
    fun translation(from: String, to: String): AiModel? =
        translations.firstOrNull { it.name == "$from-$to" }

    val all = listOf(
        whisperTiny,
        whisperBase,
        whisperSmall,
        whisperLargeTurbo,
        deepFilterNet,
        rnnoise,
    ) + translations

    fun byId(id: String): AiModel? = all.firstOrNull { it.id == id }
}
