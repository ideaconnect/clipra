package tech.idct.clipra.feature.captions

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Srt
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reading and writing .srt files (#46) the user picks with the system file picker; nothing
 * else is read or written, and nothing leaves the device.
 */
internal object SrtFiles {
    /** The MIME types an .srt file comes with; pickers differ. */
    val types = arrayOf("application/x-subrip", "text/*", "application/octet-stream")

    const val MIME = "application/x-subrip"

    /** No caption file is this big; anything larger isn't one. */
    private const val MAX_BYTES = 4 shl 20

    /** When the file is saved, in its name; no colons, which file systems refuse. */
    private val SAVED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss", Locale.ROOT)

    /**
     * "Lisbon weekend 2026-11-07 142005.pl.srt" (#234): the project's name, when the file is
     * saved and the captions' language, without characters file systems refuse. The language
     * tells the original and each translation apart and is what players look for next to a
     * video ("video.pl.srt"). The time keeps another save, or another project of the same name,
     * from taking the name: Android 10's picker numbers a taken name after the extension
     * ("name.srt (1)"), and players and MediaStore no longer see an .srt file then.
     */
    fun fileName(project: Project, savedAt: LocalDateTime = LocalDateTime.now()): String {
        val name = project.name.replace(Regex("""[\\/:*?"<>|]"""), " ").trim()
            .ifEmpty { "captions" }
        val language = Srt.language(project)?.filter { it.isLetterOrDigit() || it == '-' }
        val suffix = if (language.isNullOrEmpty()) "" else ".$language"
        return "$name ${SAVED_AT.format(savedAt)}$suffix.srt"
    }

    suspend fun write(context: Context, uri: Uri, project: Project) = withContext(Dispatchers.IO) {
        val text = Srt.write(Srt.cues(project))
        context.contentResolver.openOutputStream(uri, "wt")!!.use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    /** The cues in the file, or empty when it isn't captions. */
    suspend fun read(context: Context, uri: Uri): List<Srt.Cue> = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (out.size() <= MAX_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            if (bytes.size > MAX_BYTES) emptyList() else Srt.read(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            emptyList()
        }
    }
}
