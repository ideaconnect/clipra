package tech.idct.clipra.feature.export

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.model.export.VideoCodec
import java.util.Locale

/** Export (design/screens/phone/Export.png): 42 s, 9:16, six layers, AV1 not on this phone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class ExportScreenTest {
    @get:Rule
    val compose = createComposeRule()

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

    @Test
    fun exportWithoutAv1() {
        Locale.setDefault(Locale.US)
        compose.setContent {
            ClipraTheme {
                ExportScreen(
                    project = project,
                    settings = ExportSettings(),
                    support = EncoderSupport(codecs = setOf(VideoCodec.H264, VideoCodec.HEVC)),
                    onChange = {},
                    onExport = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("estimate").assertTextEquals("~64.3 MB")
        compose.onNodeWithTag("codec:AV1").assertIsNotEnabled()
        compose.onRoot().captureRoboImage(
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
            ),
        )
    }
}
