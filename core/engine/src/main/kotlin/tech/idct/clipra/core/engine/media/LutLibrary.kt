package tech.idct.clipra.core.engine.media

import android.content.Context
import tech.idct.clipra.core.model.CubeLut
import tech.idct.clipra.core.model.Luts
import java.io.File
import java.security.MessageDigest

/**
 * The `.cube` files the user imported (#28), copied into the app's own storage so they work
 * for every project and offline. Each is checked when it comes in and named by its content,
 * so importing the same file twice keeps one copy.
 */
class LutLibrary(private val directory: File) {
    /** An imported LUT: its id in a grade (`imported:<hash>`) and the file's name. */
    data class Entry(val id: String, val name: String)

    /** Every imported LUT, by name. */
    fun list(): List<Entry> = directory.listFiles { file -> file.extension == "cube" }
        .orEmpty()
        .map { Entry(Luts.IMPORTED + it.nameWithoutExtension, nameOf(it)) }
        .sortedBy { it.name.lowercase() }

    /**
     * Keeps [text], a `.cube` file called [name]. Throws `CubeError` when it isn't a 3D LUT
     * Clipra can use.
     */
    fun import(name: String, text: String): Entry {
        CubeLut.parse(text)
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .take(HASH_BYTES).joinToString("") { "%02x".format(it) }
        directory.mkdirs()
        File(directory, "$hash.cube").writeText(text)
        File(directory, "$hash.name").writeText(name)
        return Entry(Luts.IMPORTED + hash, name)
    }

    /** The imported LUT with [id], or null when it isn't one or its file is gone. */
    fun lut(id: String): CubeLut? {
        if (!id.startsWith(Luts.IMPORTED)) return null
        val hash = id.removePrefix(Luts.IMPORTED)
        if (!hash.all { it.isLetterOrDigit() }) return null
        val file = File(directory, "$hash.cube").takeIf { it.exists() } ?: return null
        return runCatching { CubeLut.parse(file.readText()) }.getOrNull()
    }

    /** The name of the imported LUT with [id], if there is one. */
    fun name(id: String): String? = list().firstOrNull { it.id == id }?.name

    private fun nameOf(cube: File): String =
        File(cube.parentFile, cube.nameWithoutExtension + ".name").takeIf { it.exists() }
            ?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: cube.name

    companion object {
        private const val HASH_BYTES = 8

        /** Files over this size aren't LUTs Clipra reads (a 129-point table is about 60 MB). */
        const val MAX_BYTES = 64L * 1024 * 1024

        fun of(context: Context): LutLibrary = LutLibrary(File(context.filesDir, "luts"))
    }
}
