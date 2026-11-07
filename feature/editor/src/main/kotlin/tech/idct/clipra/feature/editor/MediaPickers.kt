package tech.idct.clipra.feature.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia

/**
 * Which picker gives Clipra photos and videos (#226). A project keeps links to its media, so the
 * links have to last after the app closes. The system photo picker's do. Where there is none, as
 * on Android 10, the photo picker contract opens the Google Play services picker instead, and
 * its links last only until the activity finishes (Back, Recents, a reboot): the project then
 * loses all its media. There Clipra picks with the system file picker, as it does sounds, since
 * its links can be kept.
 */
internal object MediaPickers {
    /** Whether this phone has the system photo picker, whose links Clipra can keep. */
    fun hasSystemPhotoPicker(): Boolean {
        val extension = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R)
        } else {
            0
        }
        return hasSystemPhotoPicker(Build.VERSION.SDK_INT, extension)
    }

    /**
     * Whether Android [sdk] has the system photo picker: Android 13 and later, and Android 11
     * and 12 with version 2 of the R SDK extension ([rExtension]), which brought it there. The
     * same test the photo picker contract makes before it opens the system picker.
     */
    fun hasSystemPhotoPicker(sdk: Int, rExtension: Int): Boolean =
        sdk >= Build.VERSION_CODES.TIRAMISU ||
            (sdk >= Build.VERSION_CODES.R && rExtension >= SYSTEM_PICKER_R_EXTENSION)

    /** The file types the system file picker offers for [type]. */
    fun mimeTypes(type: PickVisualMedia.VisualMediaType): Array<String> = when (type) {
        PickVisualMedia.ImageOnly -> arrayOf(IMAGES)
        PickVisualMedia.VideoOnly -> arrayOf(VIDEOS)
        PickVisualMedia.ImageAndVideo -> arrayOf(IMAGES, VIDEOS)
        is PickVisualMedia.SingleMimeType -> arrayOf(type.mimeType)
    }

    private const val SYSTEM_PICKER_R_EXTENSION = 2
    private const val IMAGES = "image/*"
    private const val VIDEOS = "video/*"
}

/**
 * Picks photos and videos to add: with the system photo picker, or with the system file picker
 * where there is none ([MediaPickers]). [photoPicker] says which, each time it opens.
 */
internal class PickMultipleMedia(
    private val photoPicker: () -> Boolean = MediaPickers::hasSystemPhotoPicker,
) : ActivityResultContract<PickVisualMediaRequest, List<Uri>>() {
    private val photos = ActivityResultContracts.PickMultipleVisualMedia()
    private val files = ActivityResultContracts.OpenMultipleDocuments()

    override fun createIntent(context: Context, input: PickVisualMediaRequest): Intent =
        if (photoPicker()) {
            photos.createIntent(context, input)
        } else {
            files.createIntent(context, MediaPickers.mimeTypes(input.mediaType))
        }

    // Both pickers hand over what was picked the same way.
    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
        photos.parseResult(resultCode, intent)
}

/**
 * Picks one photo or video, to replace a clip's media or to find a missing file: with the system
 * photo picker, or with the system file picker where there is none ([MediaPickers]).
 */
internal class PickMedia(
    private val photoPicker: () -> Boolean = MediaPickers::hasSystemPhotoPicker,
) : ActivityResultContract<PickVisualMediaRequest, Uri?>() {
    private val photos = ActivityResultContracts.PickVisualMedia()
    private val files = ActivityResultContracts.OpenDocument()

    override fun createIntent(context: Context, input: PickVisualMediaRequest): Intent =
        if (photoPicker()) {
            photos.createIntent(context, input)
        } else {
            files.createIntent(context, MediaPickers.mimeTypes(input.mediaType))
        }

    // The photo picker's parsing also reads the file picker's answer (its data).
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        photos.parseResult(resultCode, intent)
}
