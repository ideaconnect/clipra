package tech.idct.clipra.core.models

/** What a model is for: the groups of the AI models screen. */
enum class ModelGroup { Captions, VoiceCleanup }

/**
 * A file as its official source publishes it: [url] pinned to a revision, its size in [bytes]
 * and its [sha256]. Clipra downloads it from there and nowhere else.
 */
data class ModelFile(val url: String, val bytes: Long, val sha256: String) {
    val name: String get() = url.substringAfterLast('/')
}

/** A file kept from a downloaded archive: its [path] in the archive and its size. */
data class UnpackedFile(val path: String, val bytes: Long) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * An on-device model. Models with a [download] come from their official source; an archive
 * unpacks into [unpacked]. Models without one are built into the app. [isDefault] marks the
 * one Clipra suggests.
 */
data class AiModel(
    val id: String,
    val name: String,
    val group: ModelGroup,
    val download: ModelFile? = null,
    val unpacked: List<UnpackedFile> = emptyList(),
    val isDefault: Boolean = false,
) {
    val builtIn: Boolean get() = download == null

    /** The bytes the model takes once installed (docs/architecture.md, section 6). */
    val installedBytes: Long
        get() = if (unpacked.isEmpty()) download?.bytes ?: 0 else unpacked.sumOf { it.bytes }
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

    val all = listOf(
        whisperTiny,
        whisperBase,
        whisperSmall,
        whisperLargeTurbo,
        deepFilterNet,
        rnnoise,
    )

    fun byId(id: String): AiModel? = all.firstOrNull { it.id == id }
}
