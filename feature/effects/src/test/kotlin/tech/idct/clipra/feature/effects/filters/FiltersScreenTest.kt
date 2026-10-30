package tech.idct.clipra.feature.effects.filters

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import java.util.Locale

/** The Filters board with Mosaic picked (design/screens/phone/Filters.png). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class FiltersScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val clip = Clip(
        id = "sunset",
        type = ClipType.Video,
        startUs = 0,
        durationUs = 9_200_000,
        source = MediaRef("sunset", 0, 9_200_000),
        effects = listOf(Effect("mosaic", 0.8f)),
    )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(MediaAsset("sunset", "content://1", MediaKind.Video, "sunset.mp4")),
        layers = listOf(Layer("L1", clips = listOf(clip))),
    )

    // Stand-ins for the live thumbnails, which need GL: a sky gradient, darker per filter.
    private val thumbnails = (listOf("") + Filters.all).withIndex().associate { (i, type) ->
        val bitmap = Bitmap.createBitmap(124, 96, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                96f,
                0xFF23305C.toInt(),
                (0xFFF3B064 - i * 0x050505).toInt(),
                Shader.TileMode.CLAMP,
            )
        }
        Canvas(bitmap).drawRect(0f, 0f, 124f, 96f, paint)
        type to bitmap
    }

    private val picked = mutableListOf<String?>()
    private var category = FilterCategory.All

    private fun show(
        category: FilterCategory = FilterCategory.All,
        locale: Locale = Locale.US,
        layout: WindowLayout = WindowLayout.Phone,
    ) {
        Locale.setDefault(locale)
        compose.setContent {
            ClipraTheme {
                CompositionLocalProvider(LocalWindowLayout provides layout) {
                    FiltersScreen(
                        state = FiltersUiState(
                            project = project,
                            clip = clip,
                            loaded = true,
                            category = category,
                            thumbnails = thumbnails,
                        ),
                        strength = 0.8f,
                        actions = FiltersActions(
                            onBack = {},
                            onCategory = { this.category = it },
                            onPick = { picked += it },
                            onStrength = {},
                            onStrengthDone = {},
                            onCompare = {},
                            onApplyToAll = {},
                        ),
                        preview = {},
                    )
                }
            }
        }
    }

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    @Test
    fun allFilters() {
        show()
        compose.onNodeWithText("sunset.mp4 · 24 filters").assertExists()
        compose.onNodeWithText("80% strength").assertExists()
        compose.onNodeWithTag("filter:mosaic").assertIsSelected()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("filter:noir").performClick()
        compose.onNodeWithTag("filter:none").performClick()
        assertEquals(listOf("noir", null), picked)
        compose.onNodeWithTag("category:Looks").performClick()
        assertEquals(FilterCategory.Looks, category)
    }

    /** The design state in Polish, to see that every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun allFiltersInPolish() {
        show(locale = Locale.forLanguageTag("pl-PL"))
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    /** On a tablet (#70) the preview fills the left and the filters sit beside it. */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun allFiltersOnATablet() {
        show(layout = WindowLayout.Tablet)
        compose.onNodeWithTag("filter:mosaic").assertIsSelected()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun looks() {
        show(FilterCategory.Looks)
        compose.onNodeWithTag("filter:pastel").assertExists()
        compose.onNodeWithTag("filter:mosaic").assertDoesNotExist()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
