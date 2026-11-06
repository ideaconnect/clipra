package tech.idct.clipra.feature.editor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import java.util.Locale
import java.util.TimeZone

/** Media the photo picker hands over under a number is named by kind and date taken (#215). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-rGB")
class PickerNamesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val warsaw = TimeZone.getTimeZone("Europe/Warsaw")

    /** 14:30 UTC on 5 November 2026: 15:30 in Warsaw. */
    private val taken = 1_793_889_000_000L

    private fun picked(
        number: String,
        name: String,
        kind: MediaKind = MediaKind.Video,
        takenAtMillis: Long = taken,
    ) = MediaAsset(
        id = "media-$number",
        uri = "content://media/picker/0/com.android.providers.media.photopicker/media/$number",
        kind = kind,
        displayName = name,
        takenAtMillis = takenAtMillis,
    )

    /** A file picked in the Files app, under its own name. */
    private fun document(uri: String, name: String) = MediaAsset(
        id = "document",
        uri = uri,
        kind = MediaKind.Video,
        displayName = name,
        takenAtMillis = taken,
    )

    private val dcim = "content://com.android.externalstorage.documents/document/primary%3ADCIM"

    @Test
    fun thePickersNumberIsNoName() {
        // The issue's names, and a picker link without a file name at all.
        assertTrue(PickerNames.hasNumberName(picked("30", "30.mp4")))
        assertTrue(PickerNames.hasNumberName(picked("74", "74.jpg", MediaKind.Photo)))
        assertTrue(PickerNames.hasNumberName(picked("1000000018", "1000000018")))
        // Android 14's picker behind Get content, and another picker that names by its link.
        assertTrue(
            PickerNames.hasNumberName(
                picked("30", "30.mp4").copy(uri = "content://media/picker_get_content/0/media/30"),
            ),
        )
        assertTrue(
            PickerNames.hasNumberName(
                picked("30", "30.mp4").copy(uri = "content://com.example.gallery/item/30"),
            ),
        )
    }

    @Test
    fun aRealNameIsAName() {
        // A file called "30.mp4" in the Files app: its link ends in the document id.
        assertFalse(PickerNames.hasNumberName(document("$dcim%2F30.mp4", "30.mp4")))
        assertFalse(
            PickerNames.hasNumberName(
                document(
                    "content://com.android.providers.media.documents/document/video%3A30",
                    "30.mp4",
                ),
            ),
        )
        // A picker that gives the file's name, as the Google Play picker on Android 10 does.
        assertFalse(PickerNames.hasNumberName(picked("46", "PXL_20261105_143000.mp4")))
        assertFalse(PickerNames.hasNumberName(picked("30", "30 (1).mp4")))
        assertFalse(PickerNames.hasNumberName(picked("30", "")))
        assertFalse(PickerNames.hasNumberName(picked("30", ".mp4")))
    }

    @Test
    fun pickerMediaIsNamedByKindAndDateTaken() {
        val video = PickerNames.named(context, picked("30", "30.mp4"), zone = warsaw)
        assertEquals("Video · 5 Nov 2026, 15:30", video.displayName)
        // Only the name changes.
        assertEquals(picked("30", "30.mp4").copy(displayName = video.displayName), video)

        val photo = PickerNames.named(
            context,
            picked("74", "74.jpg", MediaKind.Photo),
            zone = warsaw,
        )
        assertEquals("Photo · 5 Nov 2026, 15:30", photo.displayName)
    }

    @Test
    @Config(qualifiers = "pl")
    fun pickerMediaIsNamedInTheAppsLanguage() {
        val video = PickerNames.named(context, picked("30", "30.mp4"), zone = warsaw)
        assertEquals("Film · 5 lis 2026, 15:30", video.displayName)
        val photo = PickerNames.named(
            context,
            picked("74", "74.jpg", MediaKind.Photo),
            zone = warsaw,
        )
        assertEquals("Zdjęcie · 5 lis 2026, 15:30", photo.displayName)
    }

    @Test
    fun pickerMediaWithoutADateIsNamedByItsKind() {
        val undated = picked("30", "30.mp4", takenAtMillis = 0)
        assertEquals("Video", PickerNames.named(context, undated, zone = warsaw).displayName)
    }

    @Test
    fun aRealNameStays() {
        val files = document("$dcim%2F30.mp4", "30.mp4")
        assertSame(files, PickerNames.named(context, files, zone = warsaw))
        val named = picked("46", "PXL_20261105_143000.mp4")
        assertSame(named, PickerNames.named(context, named, zone = warsaw))
    }

    @Test
    fun mediaTheProjectHasUnderItsOwnNameKeepsIt() {
        // The same video, picked in the Files app first and then with the photo picker.
        val known = document("$dcim%2Ftram.mp4", "tram.mp4")
        val again = PickerNames.named(context, picked("30", "30.mp4"), known, warsaw)
        assertEquals("tram.mp4", again.displayName)
        assertEquals(picked("30", "30.mp4").uri, again.uri)
        // Known under the picker's number, as in a project from before: it gets the new name.
        val old = picked("30", "30.mp4")
        assertEquals(
            "Video · 5 Nov 2026, 15:30",
            PickerNames.named(context, picked("30", "30.mp4"), old, warsaw).displayName,
        )
    }

    @Test
    fun theDateIsWrittenAsTheLocaleWritesIt() {
        assertEquals("5 Nov 2026, 15:30", PickerNames.taken(taken, Locale.UK, warsaw))
        assertEquals(
            "5 lis 2026, 15:30",
            PickerNames.taken(taken, Locale.forLanguageTag("pl-PL"), warsaw),
        )
        // In the time zone the phone is in.
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        assertEquals("5 Nov 2026, 23:30", PickerNames.taken(taken, Locale.UK, tokyo))
    }
}
