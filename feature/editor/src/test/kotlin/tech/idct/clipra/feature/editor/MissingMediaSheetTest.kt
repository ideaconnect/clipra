package tech.idct.clipra.feature.editor

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import java.util.Locale
import java.util.TimeZone

/** The Missing media sheet (#216) and the question before another file is used. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class MissingMediaSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val sec = 1_000_000L

    /** Noon on 5 November 2026, UTC: the same day in every time zone tests run in. */
    private val taken = 1_793_880_000_000L

    // The issue's project: a picker video split in three, a picker photo and a sound from the
    // Files app, all gone; one more photo is gone that no clip uses any more.
    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(
            MediaAsset(
                "tram",
                "content://media/picker/0/com.android.providers.media.photopicker/media/30",
                MediaKind.Video,
                "30.mp4",
                durationUs = 12_400_000,
                sizeBytes = 48_300_000,
                takenAtMillis = taken,
            ),
            MediaAsset(
                "stamp",
                "content://media/picker/0/com.android.providers.media.photopicker/media/74",
                MediaKind.Photo,
                "74.jpg",
                sizeBytes = 3_100_000,
                takenAtMillis = taken,
            ),
            MediaAsset(
                "habitat",
                "content://com.android.externalstorage.documents/document/primary%3AMusic",
                MediaKind.Audio,
                "habitat.mp3",
                durationUs = 187_000_000,
                sizeBytes = 7_400_000,
            ),
            MediaAsset("old", "content://media/picker/0/media/12", MediaKind.Photo, "12.jpg"),
        ),
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    Clip("t1", ClipType.Video, 0, 4 * sec, MediaRef("tram", 0, 4 * sec)),
                    Clip(
                        "t2",
                        ClipType.Video,
                        4 * sec,
                        4 * sec,
                        MediaRef("tram", 4 * sec, 8 * sec),
                    ),
                    Clip(
                        "t3",
                        ClipType.Video,
                        8 * sec,
                        4 * sec,
                        MediaRef("tram", 8 * sec, 12 * sec),
                    ),
                ),
            ),
            Layer(
                "L2",
                clips = listOf(Clip("s", ClipType.Photo, 0, 3 * sec, MediaRef("stamp", 0, 0))),
            ),
            Layer(
                "L3",
                clips = listOf(
                    Clip(
                        "m",
                        ClipType.Audio,
                        0,
                        12 * sec,
                        MediaRef(
                            "habitat",
                            0,
                            12 * sec,
                        ),
                    ),
                ),
            ),
        ),
    )

    private val missing = setOf("tram", "stamp", "habitat", "old")

    /** A size as the phone writes it, in its locale (decimal units). */
    private fun size(bytes: Long) =
        Formatter.formatShortFileSize(ApplicationProvider.getApplicationContext(), bytes)

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private fun showSheet(onFind: (MediaAsset) -> Unit = {}) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        compose.setContent {
            ClipraTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(ClipraTheme.colors.surfaceSunken)
                        .padding(16.dp),
                ) {
                    MissingMediaList(missingFiles(project, missing), onFind)
                }
            }
        }
    }

    @Test
    fun missingFilesCountTheirClips() {
        val files = missingFiles(project, missing)
        assertEquals(listOf("tram", "stamp", "habitat", "old"), files.map { it.asset.id })
        assertEquals(listOf(3, 1, 1, 0), files.map { it.clips })
        assertEquals(emptyList<MissingFile>(), missingFiles(project, emptySet()))
    }

    @Test
    fun eachMissingFileSaysWhatItIs() {
        Locale.setDefault(Locale.US)
        var found: MediaAsset? = null
        showSheet { found = it }

        compose.onNodeWithText("Missing media").assertIsDisplayed()
        compose.onNodeWithText("Video · 0:12 · ${size(48_300_000)}").assertIsDisplayed()
        compose.onNodeWithText("Taken Nov 5, 2026 · 3 clips").assertIsDisplayed()
        // A photo has no length; a sound from the Files app no date taken.
        compose.onNodeWithText("Photo · ${size(3_100_000)}").assertIsDisplayed()
        compose.onNodeWithText("Sound · 3:07 · ${size(7_400_000)}").assertIsDisplayed()
        compose.onNodeWithText("Not on the timeline", substring = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("find:habitat").performClick()
        assertEquals("habitat", found?.id)
    }

    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun eachMissingFileSaysWhatItIsInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        showSheet()
        compose.onNodeWithText("Brakujące pliki").assertIsDisplayed()
        compose.onNodeWithText("3 klipy", substring = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    private fun showQuestion(candidate: RelinkCandidate, onUse: () -> Unit = {}) {
        compose.setContent {
            ClipraTheme {
                RelinkDialog(candidate, onUse = onUse, onCancel = {})
            }
        }
    }

    private val shorter = project.media.first().copy(
        id = "tram-short",
        uri = "content://media/picker/0/media/83",
        displayName = "83.mp4",
        durationUs = 9 * sec,
    )

    @Test
    fun anotherFileIsUsedOnlyWhenTheUserSaysSo() {
        Locale.setDefault(Locale.US)
        var used = false
        showQuestion(RelinkCandidate("tram", shorter, refitted = 1, clips = 3)) { used = true }

        compose.onNodeWithText("Not the same file").assertIsDisplayed()
        compose.onNodeWithTag("relink-body").assertIsDisplayed()
        compose.onNodeWithText(
            "83.mp4 isn't the file the project used. Use it anyway? Clips that don't fit in it " +
                "will play another part of it: 1 of 3.",
        ).assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("relink-use").performClick()
        assertEquals(true, used)
    }

    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun anotherFileIsUsedOnlyWhenTheUserSaysSoInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        showQuestion(RelinkCandidate("tram", shorter, refitted = 0, clips = 3))
        compose.onNodeWithText("To nie ten sam plik").assertIsDisplayed()
        // Everything fits in it: nothing more to say.
        compose.onNodeWithText("83.mp4 to nie plik, którego używał projekt. Użyć go mimo to?")
            .assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage(roborazziOptions = options)
    }
}
