package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.TextColors
import tech.idct.clipra.core.model.TextEffects
import tech.idct.clipra.core.model.TextSpec
import java.io.File
import java.io.FileNotFoundException

/**
 * The 12 text effects (#32), "DAY TWO" in heavy Bricolage as on the Text board: each draws
 * what its design tile shows (checked by its colours: the orange outline, the cyan and red
 * glitch, the yellow box and so on), and matches its golden image. Record with
 * `recordGoldens true`, as for the filters.
 */
@RunWith(AndroidJUnit4::class)
class TextEffectGoldenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val record = InstrumentationRegistry.getArguments().getString("recordGoldens") == "true"

    private val effects = listOf<String?>(null) + TextEffects.all

    @Test
    fun eachEffectDrawsWhatItsTileShows() {
        val drawn = effects.associateWith { render(it) }
        val none = drawn.getValue(null)
        val failures = mutableListOf<String>()
        fun check(effect: String?, what: String, ok: Boolean) {
            if (!ok) failures += "${effect ?: "none"}: $what"
        }
        val white = count(none) { r, g, b -> r > 230 && g > 230 && b > 230 }
        check(null, "white letters", white > 500)
        check(null, "nothing orange", count(none, match = ::orange) == 0)

        check(
            "outline",
            "an orange outline",
            count(drawn.getValue("outline"), match = ::orange) > 300,
        )
        check("shadow", "a dark shadow", count(drawn.getValue("shadow"), match = ::dark) > 500)
        check("glow", "a yellow glow", count(drawn.getValue("glow"), match = ::warmGlow) > 500)
        check("neon", "a magenta glow", count(drawn.getValue("neon"), match = ::magenta) > 500)
        val box = drawn.getValue("box")
        check("box", "a yellow box", count(box, match = ::yellow) > 3000)
        check("box", "dark letters", count(box) { r, g, b -> r < 60 && g < 60 && b < 60 } > 500)
        val gradient = drawn.getValue("gradient")
        check("gradient", "yellow on the left", count(gradient, 0f, 0.4f, ::yellow) > 100)
        check("gradient", "orange on the right", count(gradient, 0.6f, 1f, ::orange) > 100)
        check("gradient", "no yellow on the right", count(gradient, 0.75f, 1f, ::yellow) == 0)
        val extrude = drawn.getValue("extrude")
        check("extrude", "yellow letters", count(extrude, match = ::yellow) > 500)
        check("extrude", "darker gold sides", count(extrude, match = ::gold) > 200)
        val glitch = drawn.getValue("glitch")
        check("glitch", "a cyan edge", count(glitch, match = ::cyan) > 100)
        check("glitch", "a red edge", count(glitch, match = ::red) > 100)
        // Where the plain letters are white, the hollow ones show the card.
        val hollow = drawn.getValue("hollow")
        var inside = 0
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val p = hollow.getPixel(x, y)
                if (Color.red(none.getPixel(x, y)) > 230 && Color.red(p) in 100..120) inside++
            }
        }
        check("hollow", "an empty inside", inside > white / 3)
        check(
            "hollow",
            "an outline",
            count(hollow) { r, g, b -> r > 230 && g > 230 && b > 230 } > 300,
        )
        val retro = drawn.getValue("retro")
        check("retro", "yellow letters", count(retro, match = ::yellow) > 500)
        check("retro", "an orange copy", count(retro, match = ::orange) > 200)
        check("retro", "a blue copy", count(retro, match = ::blue) > 200)
        check("marker", "an orange band", count(drawn.getValue("marker"), match = ::orange) > 2000)
        assertTrue("effects not drawn as designed: $failures", failures.isEmpty())
    }

    @Test
    fun effectsMatchTheirGoldens() {
        val failures = effects.mapNotNull { effect ->
            val name = effect ?: "none"
            val rendered = render(effect)
            if (record) {
                save(File(context.filesDir, "golden-text"), name, rendered)
                return@mapNotNull null
            }
            val golden = golden(name) ?: return@mapNotNull "$name (no golden)"
            val matched = Offscreen.match(golden, rendered, 8)
            Log.i(TAG, "$name: ${"%.4f".format(matched)} of pixels match")
            if (matched >= 0.99) null else "$name (${"%.4f".format(matched)})"
        }
        assertTrue("text effects off their goldens: $failures", failures.isEmpty())
    }

    @Test
    fun aBoxOnAYellowCardIsWhite() {
        val boxed = TextRenderer.render(
            context,
            spec("box").copy(background = TextColors.YELLOW, color = TextColors.WHITE),
            card = true,
            WIDTH,
            HEIGHT,
        )
        assertTrue(count(boxed) { r, g, b -> r > 240 && g > 240 && b > 240 } > 3000)
    }

    private fun spec(effect: String?) = TextSpec(
        "DAY TWO",
        font = TextRenderer.BRICOLAGE,
        weight = 800,
        size = 0.2f,
        color = TextColors.WHITE,
        background = CARD,
        effect = effect,
    )

    private fun render(effect: String?): Bitmap =
        TextRenderer.render(context, spec(effect), card = true, WIDTH, HEIGHT)

    /** Pixels of [bitmap] between [from] and [to] of its width that [match]. */
    private fun count(
        bitmap: Bitmap,
        from: Float = 0f,
        to: Float = 1f,
        match: (Int, Int, Int) -> Boolean,
    ): Int {
        var n = 0
        for (y in 0 until bitmap.height) {
            for (x in (bitmap.width * from).toInt() until (bitmap.width * to).toInt()) {
                val p = bitmap.getPixel(x, y)
                if (match(Color.red(p), Color.green(p), Color.blue(p))) n++
            }
        }
        return n
    }

    private fun orange(r: Int, g: Int, b: Int) = r > 200 && g in 60..140 && b < 110

    private fun yellow(r: Int, g: Int, b: Int) = r > 230 && g > 180 && b < 120

    private fun gold(r: Int, g: Int, b: Int) = r in 110..215 && g in 80..170 && b < 40

    private fun cyan(r: Int, g: Int, b: Int) = r < 90 && g > 180 && b > 200

    private fun red(r: Int, g: Int, b: Int) = r > 200 && g < 100 && b in 40..140

    private fun blue(r: Int, g: Int, b: Int) = r < 110 && g in 60..140 && b > 200

    private fun magenta(r: Int, g: Int, b: Int) = r > 140 && b > 140 && g < 110

    private fun warmGlow(r: Int, g: Int, b: Int) = r > 150 && g > 120 && b < r - 40

    /** Darker than the card (a shadow). */
    private fun dark(r: Int, g: Int, b: Int) = r + g + b < 3 * 0x6E - 90

    private fun golden(name: String): Bitmap? = try {
        instrumentation.context.assets.open("golden/text/$name.png").use {
            BitmapFactory.decodeStream(it)
        }
    } catch (_: FileNotFoundException) {
        null
    }

    private fun save(directory: File, name: String, bitmap: Bitmap) {
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private companion object {
        const val TAG = "TextEffectGolden"
        const val WIDTH = 640
        const val HEIGHT = 360

        /** A mid-grey card, so shadows and light both show. */
        const val CARD = 0xFF6E6E6E
    }
}
