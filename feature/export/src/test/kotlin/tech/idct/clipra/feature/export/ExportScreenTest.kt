package tech.idct.clipra.feature.export

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.adaptive.LocalWindowLayout
import tech.idct.clipra.core.designsystem.adaptive.WindowLayout
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Word
import tech.idct.clipra.core.model.export.CaptionMode
import tech.idct.clipra.core.model.export.ExportExtra
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.model.export.VideoCodec
import java.util.Locale

/** Export (design/screens/phone/Export.png): 42 s, 9:16, six layers, AV1 not on this phone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class ExportScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var pro = 0

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        layers = (1..6).map { n ->
            Layer(
                "L$n",
                clips = if (n ==
                    1
                ) {
                    listOf(Clip("c", ClipType.Photo, 0, 42_000_000))
                } else {
                    emptyList()
                },
            )
        },
    )

    /** The board's Also save: Cover .jpg and Project .otio. */
    private val designSettings =
        ExportSettings(extras = setOf(ExportExtra.Cover, ExportExtra.Project))

    @Test
    fun exportWithoutAv1() {
        Locale.setDefault(Locale.US)
        compose.setContent {
            ClipraTheme {
                ExportScreen(
                    project = project,
                    settings = designSettings,
                    support = EncoderSupport(codecs = setOf(VideoCodec.H264, VideoCodec.HEVC)),
                    onChange = {},
                    onExport = {},
                    onBack = {},
                    ads = ExportAdsLine(proPrice = "10,00 zł", onPro = { pro++ }),
                )
            }
        }
        compose.onNodeWithTag("estimate").assertTextEquals("~64.3 MB")
        compose.onNodeWithTag("codec:AV1").assertIsNotEnabled()
        compose.onNodeWithText("Remove for 10,00 zł", substring = true).assertExists()
        compose.onRoot().captureRoboImage(
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
            ),
        )
        compose.onNodeWithTag("export-ads-line").performClick()
        assertEquals(1, pro)
    }

    /** With the system font at 200% (#75), Export to Gallery stays on screen. */
    @Test
    fun exportWithLargeFonts() {
        Locale.setDefault(Locale.US)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ClipraTheme {
                    ExportScreen(
                        project = project,
                        settings = ExportSettings(),
                        support = EncoderSupport(),
                        onChange = {},
                        onExport = {},
                        onBack = {},
                        ads = ExportAdsLine(proPrice = "10,00 zł", onPro = {}),
                    )
                }
            }
        }
        compose.onNodeWithTag("export-to-gallery").assertIsDisplayed()
        compose.onRoot().captureRoboImage(
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
            ),
        )
    }

    /** The design state in Polish, to see that every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun exportWithoutAv1InPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        compose.setContent {
            ClipraTheme {
                ExportScreen(
                    project = project,
                    settings = designSettings,
                    support = EncoderSupport(codecs = setOf(VideoCodec.H264, VideoCodec.HEVC)),
                    onChange = {},
                    onExport = {},
                    onBack = {},
                    ads = ExportAdsLine(proPrice = "10,00 zł", onPro = { pro++ }),
                )
            }
        }
        compose.onRoot().captureRoboImage(
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
            ),
        )
    }

    @Test
    fun proUsersReadNothingAboutAds() {
        compose.setContent {
            ClipraTheme {
                ExportScreen(
                    project = project,
                    settings = ExportSettings(),
                    support = EncoderSupport(codecs = setOf(VideoCodec.H264)),
                    onChange = {},
                    onExport = {},
                    onBack = {},
                    ads = null,
                )
            }
        }
        compose.onNodeWithTag("export-ads-line").assertDoesNotExist()
    }

    @Test
    fun withoutAPriceNoneIsMadeUp() {
        compose.setContent {
            ClipraTheme {
                ExportScreen(
                    project = project,
                    settings = ExportSettings(),
                    support = EncoderSupport(codecs = setOf(VideoCodec.H264)),
                    onChange = {},
                    onExport = {},
                    onBack = {},
                    ads = ExportAdsLine(proPrice = null, onPro = {}),
                )
            }
        }
        compose.onNodeWithText("Remove them with Clipra Pro", substring = true).assertExists()
    }

    /** Also save (#59): chips add and remove extras; captions and sound only when there are any. */
    @Test
    fun alsoSaveTogglesTheExtras() {
        val changes = mutableListOf<ExportSettings>()
        var settings by mutableStateOf(ExportSettings())
        var withCaptions by mutableStateOf(project)
        compose.setContent {
            ClipraTheme {
                ExportScreen(
                    project = withCaptions,
                    settings = settings,
                    support = EncoderSupport(),
                    onChange = {
                        changes += it
                        settings = it
                    },
                    onExport = {},
                    onBack = {},
                )
            }
        }
        // A photo plays no sound and has no captions.
        compose.onNodeWithTag("extra:Captions").assertDoesNotExist()
        compose.onNodeWithTag("extra:Audio").assertDoesNotExist()
        compose.onNodeWithTag("extra:Cover").performScrollTo().performClick()
        assertEquals(setOf(ExportExtra.Cover), changes.last().extras)
        compose.onNodeWithTag("extra:Cover").assertIsSelected()
        compose.onNodeWithTag("extra:Project").performClick()
        compose.onNodeWithTag("extra:Cover").performClick()
        assertEquals(setOf(ExportExtra.Project), changes.last().extras)

        // With captions, the .srt is offered; as a separate file it is on and fixed.
        withCaptions = project.copy(
            layers = project.layers.take(1) + Layer(
                "L2",
                clips = listOf(
                    Clip(
                        "cap",
                        ClipType.Caption,
                        0,
                        1_000_000,
                        caption = CaptionSpec(listOf(Word("Lisbon", 0, 1_000_000))),
                    ),
                ),
            ),
        )
        compose.onNodeWithTag("extra:Captions").performScrollTo().assertIsNotSelected()
        settings = settings.copy(captions = CaptionMode.Srt)
        compose.onNodeWithTag("extra:Captions").assertIsSelected().assertIsNotEnabled()
    }

    /**
     * Export on a tablet (#70, design/screens/tablet/TabletExport.png): the cover large over the
     * filmstrip and Also save, the options, estimate and Export to Gallery beside.
     */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tabletExport() {
        Locale.setDefault(Locale.US)
        val covers = mutableListOf<Long>()
        val frame = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.rgb(107, 77, 122)) }
        val wide = project.copy(format = Format(ratio = AspectRatio.Landscape16x9))
        compose.setContent {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.Tablet) {
                ClipraTheme {
                    ExportScreen(
                        project = wide,
                        settings = ExportSettings(
                            extras = setOf(ExportExtra.Captions, ExportExtra.Cover),
                            coverUs = 7_120_000,
                        ),
                        support = EncoderSupport(),
                        onChange = {},
                        onExport = {},
                        onBack = {},
                        ads = ExportAdsLine(proPrice = "10,00 zł", onPro = {}),
                        preview = ExportPreview(
                            cover = frame,
                            strip = List(12) { frame },
                            coverUs = 7_120_000,
                            onCover = { covers += it },
                        ),
                    )
                }
            }
        }
        compose.onNodeWithText("1920 × 1080 · 30 fps").assertExists()
        compose.onNodeWithText("00:07.12").assertExists()
        compose.onRoot().captureRoboImage(
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
            ),
        )
        compose.onNodeWithTag("export-strip").performClick()
        assertEquals(1, covers.size)
    }
}
