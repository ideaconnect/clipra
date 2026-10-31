package tech.idct.clipra.core.models

import java.io.File

/**
 * Downloaded models in app-specific storage: one folder per model, which only exists once its
 * download was verified and installed. A download in progress is a `.part` file beside it.
 */
class ModelStore(val root: File) {
    /** Whether [model] can be used: built in, or downloaded, verified and installed. */
    fun isInstalled(model: AiModel): Boolean =
        model.builtIn || File(folder(model), INSTALLED).exists()

    /** The bytes [model]'s files take on disk; 0 when it is not installed or built in. */
    fun bytesOnDisk(model: AiModel): Long {
        if (model.builtIn || !isInstalled(model)) return 0
        return folder(model).listFiles().orEmpty()
            .filter { it.isFile && it.name != INSTALLED }
            .sumOf { it.length() }
    }

    /** One of an installed model's files, by [name] ("ggml-base.bin", "enc.onnx"). */
    fun file(model: AiModel, name: String): File? =
        File(folder(model), name).takeIf { isInstalled(model) && it.isFile }

    /** Removes [model]'s files and any unfinished download of it. */
    fun remove(model: AiModel) {
        folder(model).deleteRecursively()
        staging(model).deleteRecursively()
        model.files.indices.forEach { partial(model, it).delete() }
    }

    internal fun folder(model: AiModel) = File(root, model.id)

    /** The download in progress of [model]'s file number [index] (0 is its main file). */
    internal fun partial(model: AiModel, index: Int = 0) =
        File(root, if (index == 0) "${model.id}.part" else "${model.id}.$index.part")

    internal fun staging(model: AiModel) = File(root, "${model.id}.new")

    /** Moves a verified [staged] folder into place and marks it installed. */
    internal fun install(model: AiModel, staged: File) {
        File(staged, INSTALLED).writeText(model.download?.sha256.orEmpty())
        val folder = folder(model)
        folder.deleteRecursively()
        check(staged.renameTo(folder)) { "Cannot install ${model.id}" }
    }

    private companion object {
        const val INSTALLED = ".installed"
    }
}
