package tech.idct.clipra.feature.editor

import android.content.Context
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Names for media that the photo picker hands over under a number (#215). The picker gives apps
 * `<id>.<extension>` as the display name ("30.mp4", "74.jpg"), not the file's name, and that
 * number would show wherever a clip is named: the timeline, Layers, the tools' headers and
 * TalkBack. Such media is named by its kind and when it was taken instead
 * ("Video · 5 Nov 2026, 15:30"), in the app's language. The name is kept in the project, as a
 * file name is, so it still says which file it was once the file is gone.
 */
internal object PickerNames {
    /**
     * [asset] as it goes into the project: named by its kind and date taken when its name is
     * only the picker's number, or by its kind alone when it has no date. [known] is the same
     * content when the project has it already: a name of its own (a file picked in the Files
     * app) stays. Media with a real name is returned as it is.
     */
    fun named(
        context: Context,
        asset: MediaAsset,
        known: MediaAsset? = null,
        zone: TimeZone = TimeZone.getDefault(),
    ): MediaAsset {
        if (!hasNumberName(asset)) return asset
        if (known != null && known.displayName.isNotEmpty() && !hasNumberName(known)) {
            return asset.copy(displayName = known.displayName)
        }
        val kind = context.getString(
            when (asset.kind) {
                MediaKind.Video -> R.string.editor_clip_video
                MediaKind.Photo -> R.string.editor_clip_photo
                MediaKind.Audio -> R.string.editor_clip_audio
            },
        )
        val name = if (asset.takenAtMillis > 0) {
            val locale = context.resources.configuration.locales[0]
            val date = taken(asset.takenAtMillis, locale, zone)
            context.getString(R.string.editor_media_name, kind, date)
        } else {
            kind
        }
        return asset.copy(displayName = name)
    }

    /**
     * Whether [asset]'s name is only the picker's number for it: digits, with or without an
     * extension, from a photo picker link (`content://media/picker/...`) or from any link that
     * ends in that number. A file picked in the Files app keeps its name even when it is
     * "30.mp4": its link ends in a document id ("video%3A30", "primary%3ADCIM%2F30.mp4").
     */
    fun hasNumberName(asset: MediaAsset): Boolean {
        val number = asset.displayName.substringBeforeLast('.')
        if (number.isEmpty() || number.any { it !in '0'..'9' }) return false
        return asset.uri.startsWith(PICKER) || asset.uri.substringAfterLast('/') == number
    }

    /** When the media was taken, as [locale] writes a date and time ("5 Nov 2026, 15:30"). */
    fun taken(millis: Long, locale: Locale, zone: TimeZone): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
            .apply { timeZone = zone }
            .format(Date(millis))

    /** The photo picker's links, also `content://media/picker_get_content/...` (Android 14). */
    private const val PICKER = "content://media/picker"
}
