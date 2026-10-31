package tech.idct.clipra.core.engine.media

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import java.io.File

/**
 * A photo deleted from the gallery after it was imported: the screens that start from its
 * picture (Filters, Color's Auto, Transitions, Cutout) get none instead of an exception that
 * would end the app.
 */
@RunWith(AndroidJUnit4::class)
class ThumbnailsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aMissingPhotoHasNoPicture() {
        val root = File(context.cacheDir, "thumbnails-test").apply { mkdirs() }
        val gone = Uri.fromFile(File(root, "gone.jpg").apply { delete() })
        val asset = MediaAsset("gone", gone.toString(), MediaKind.Photo, "gone.jpg")

        assertNull(Thumbnails.decodePhoto(context, gone, 320))
        assertNull(Thumbnails.still(context, asset, MediaCache(File(root, "cache")), 0, 320))
        assertNull(Thumbnails.thumbnail(context, asset, MediaCache(File(root, "cache"))))
    }
}
