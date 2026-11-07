package tech.idct.clipra.feature.editor

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Photos and videos come from the system photo picker where the phone has one, and from the
 * system file picker where it doesn't (#226): on Android 10 the photo picker contract opens
 * Google Play services' picker, whose links are gone once Clipra closes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MediaPickersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val photosAndVideos = PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)
    private val videos = PickVisualMediaRequest(PickVisualMedia.VideoOnly)
    private val photos = PickVisualMediaRequest(PickVisualMedia.ImageOnly)

    private fun Intent.mimeTypes(): List<String>? =
        getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList()

    @Test
    fun theSystemPhotoPickerIsThereFromAndroid13AndOnUpdatedAndroid11And12() {
        assertFalse(MediaPickers.hasSystemPhotoPicker(Build.VERSION_CODES.Q, rExtension = 0))
        // Android 11 and 12 before and after the update that brought the picker.
        assertFalse(MediaPickers.hasSystemPhotoPicker(Build.VERSION_CODES.R, rExtension = 1))
        assertTrue(MediaPickers.hasSystemPhotoPicker(Build.VERSION_CODES.R, rExtension = 2))
        assertFalse(MediaPickers.hasSystemPhotoPicker(Build.VERSION_CODES.S_V2, rExtension = 1))
        assertTrue(MediaPickers.hasSystemPhotoPicker(Build.VERSION_CODES.S_V2, rExtension = 7))
        assertTrue(MediaPickers.hasSystemPhotoPicker(Build.VERSION_CODES.TIRAMISU, rExtension = 0))
        assertTrue(MediaPickers.hasSystemPhotoPicker(37, rExtension = 0))
    }

    @Test
    fun withTheSystemPhotoPickerMediaIsAddedWithIt() {
        val intent = PickMultipleMedia { true }.createIntent(context, photosAndVideos)
        assertEquals(MediaStore.ACTION_PICK_IMAGES, intent.action)
    }

    @Test
    fun withoutItMediaIsAddedWithTheFilePicker() {
        val intent = PickMultipleMedia { false }.createIntent(context, photosAndVideos)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals(listOf("image/*", "video/*"), intent.mimeTypes())
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun replacingAndFindingMediaPickOneFileOfItsKind() {
        val video = PickMedia { false }.createIntent(context, videos)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, video.action)
        assertEquals(listOf("video/*"), video.mimeTypes())
        assertFalse(video.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        val photo = PickMedia { false }.createIntent(context, photos)
        assertEquals(listOf("image/*"), photo.mimeTypes())
        val any = PickMedia { false }.createIntent(context, photosAndVideos)
        assertEquals(listOf("image/*", "video/*"), any.mimeTypes())

        val picker = PickMedia { true }.createIntent(context, videos)
        assertEquals(MediaStore.ACTION_PICK_IMAGES, picker.action)
        assertEquals("video/*", picker.type)
    }

    @Test
    fun aSingleTypeIsOfferedAsItIs() {
        assertArrayEquals(
            arrayOf("video/mp4"),
            MediaPickers.mimeTypes(PickVisualMedia.SingleMimeType("video/mp4")),
        )
    }

    @Test
    fun thePickedFilesAreHandedOver() {
        val documents = "content://com.android.providers.media.documents/document"
        val first = Uri.parse("$documents/video%3A57")
        val second = Uri.parse("$documents/image%3A58")
        val several = Intent().apply {
            clipData = ClipData.newRawUri(null, first).apply { addItem(ClipData.Item(second)) }
        }
        val files = PickMultipleMedia { false }
        assertEquals(listOf(first, second), files.parseResult(Activity.RESULT_OK, several))
        val one = Intent().setData(first)
        assertEquals(listOf(first), files.parseResult(Activity.RESULT_OK, one))
        assertEquals(first, PickMedia { false }.parseResult(Activity.RESULT_OK, one))
        // Closed without picking.
        assertEquals(emptyList<Uri>(), files.parseResult(Activity.RESULT_CANCELED, one))
        assertNull(PickMedia { false }.parseResult(Activity.RESULT_CANCELED, one))
    }

    @Test
    fun onAnAndroid13PhoneThePhotoPickerIsUsed() {
        assertTrue(MediaPickers.hasSystemPhotoPicker())
        val intent = PickMultipleMedia().createIntent(context, photosAndVideos)
        assertEquals(MediaStore.ACTION_PICK_IMAGES, intent.action)
    }

    @Test
    @Config(sdk = [29])
    fun onAnAndroid10PhoneWithGooglePlayServicesThePickerIsTheFilePicker() {
        // Google Play services offers its picker as the system's fallback, as on the issue's
        // Galaxy S9: the photo picker contract would open it, and its links don't last.
        val playServices = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.google.android.gms"
                name = "com.google.android.gms.photopicker.ui.PhotoPickerActivity"
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.google.android.gms"
                    flags = ApplicationInfo.FLAG_SYSTEM
                }
            }
        }
        // Robolectric's simplest way to answer one intent, as the phone's package manager does.
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(PickVisualMedia.ACTION_SYSTEM_FALLBACK_PICK_IMAGES),
            playServices,
        )
        assertTrue(PickVisualMedia.isPhotoPickerAvailable(context))
        assertEquals(
            PickVisualMedia.ACTION_SYSTEM_FALLBACK_PICK_IMAGES,
            ActivityResultContracts.PickMultipleVisualMedia()
                .createIntent(context, photosAndVideos).action,
        )

        assertFalse(MediaPickers.hasSystemPhotoPicker())
        val added = PickMultipleMedia().createIntent(context, photosAndVideos)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, added.action)
        assertEquals(listOf("image/*", "video/*"), added.mimeTypes())
        val found = PickMedia().createIntent(context, videos)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, found.action)
        assertEquals(listOf("video/*"), found.mimeTypes())
    }
}
