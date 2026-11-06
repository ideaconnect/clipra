package tech.idct.clipra.feature.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.engine.export.MissingOriginals
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import java.io.File
import java.util.Locale

/**
 * The issue's steps on a phone (#212): a photo in the gallery is deleted through MediaStore, as a
 * gallery app does, and Export warns that its clip would be left out before anything starts.
 */
@RunWith(AndroidJUnit4::class)
class MissingMediaExportTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val inserted = mutableListOf<Uri>()

    @After
    fun tearDown() {
        // The deleted one is gone already; MediaProvider refuses a second delete.
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    /** A small PNG in Pictures/ClipraTest, through MediaStore, as the camera or a download. */
    private fun galleryPhoto(name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/ClipraTest",
            )
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values)!!
        inserted += uri
        context.contentResolver.openOutputStream(uri)!!.use { out ->
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return uri
    }

    @Test
    fun aPhotoDeletedFromTheGalleryIsWarnedAboutBeforeTheExport() {
        Locale.setDefault(Locale.US)
        val sec = 1_000_000L
        val kept = galleryPhoto("clipra-kept.png")
        val deleted = galleryPhoto("clipra-stamp.png")
        val video = File(context.cacheDir, "missing-media-test/tram.mp4").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(64))
        }
        val project = Project(
            id = "missing-media-test",
            name = "Missing media test",
            media = listOf(
                MediaAsset("tram", Uri.fromFile(video).toString(), MediaKind.Video, "tram.mp4"),
                MediaAsset("kept", kept.toString(), MediaKind.Photo, "clipra-kept.png"),
                MediaAsset("stamp", deleted.toString(), MediaKind.Photo, "clipra-stamp.png"),
            ),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip("a", ClipType.Video, 0, 8 * sec, MediaRef("tram", 0, 8 * sec)),
                    ),
                ),
                Layer(
                    "L2",
                    clips = listOf(
                        Clip("b", ClipType.Photo, 0, 2 * sec, MediaRef("kept", 0, 0)),
                        Clip("c", ClipType.Photo, 2 * sec, 4 * sec, MediaRef("stamp", 0, 0)),
                    ),
                ),
            ),
        )
        val cache = MediaCache(File(context.cacheDir, "missing-media-test/cache"))
        assertEquals(emptyList<Any>(), MissingOriginals.leftOut(context, cache, project))

        assertEquals(1, context.contentResolver.delete(deleted, null, null))
        val leftOut = MissingOriginals.leftOut(context, cache, project)

        assertEquals(listOf("c"), leftOut.map { it.clipId })
        var exported = false
        compose.setContent {
            ClipraTheme {
                LeftOutDialog(leftOut, onExportAnyway = { exported = true }, onCancel = {})
            }
        }
        compose.onNodeWithText("Some media is missing").assertIsDisplayed()
        compose.onNodeWithText("Photo · L2 · 0:02-0:06").assertIsDisplayed()
        compose.onNodeWithText("clipra-stamp.png").assertIsDisplayed()
        compose.onNodeWithTag("left-out-export").performClick()
        assertEquals(true, exported)
    }
}
