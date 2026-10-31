package tech.idct.clipra.core.engine.media

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.models.ModelCatalog
import tech.idct.clipra.core.models.ModelDownloader
import tech.idct.clipra.core.models.ModelStore
import java.io.File

/**
 * Caption translation (#47): Mozilla's English to Polish model, downloaded as the app does
 * (three gzipped files, each checked), then captions translated on the phone.
 */
@RunWith(AndroidJUnit4::class)
class CaptionTranslatorTest {
    private val translator = CaptionTranslator(store)

    @Test
    fun routesGoThroughEnglish() {
        assertEquals(listOf("en", "pl", "de"), translator.languages)
        assertEquals(listOf("pl", "de"), translator.targets("en"))
        assertEquals(
            listOf(ModelCatalog.translatePlEn, ModelCatalog.translateEnDe),
            translator.route("pl", "de"),
        )
        assertEquals(null, translator.route("en", "en"))
        assertTrue(translator.isReady("en", "pl"))
        // Polish to German needs two models, neither of them downloaded here.
        assertFalse(translator.isReady("pl", "de"))
    }

    @Test
    fun englishCaptionsBecomePolish() = runBlocking {
        val started = System.nanoTime()
        val polish = translator.translate(
            listOf(
                "We finally made it to Lisbon",
                "The light here is unreal",
                "if the weather holds",
            ),
            "en",
            "pl",
        )
        Log.i(TAG, "3 captions in ${(System.nanoTime() - started) / 1_000_000} ms: $polish")
        assertEquals(3, polish.size)
        assertTrue(polish[0], "Lizbon" in polish[0])
        assertTrue(polish[1], "świat" in polish[1].lowercase())
        assertTrue(polish[2], polish[2].isNotBlank())
    }

    companion object {
        private const val TAG = "CaptionTranslator"
        private lateinit var store: ModelStore

        @BeforeClass
        @JvmStatic
        fun downloadEnglishToPolish() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            store = ModelStore(File(context.filesDir, "models"))
            ModelDownloader(store).download(ModelCatalog.translateEnPl)
        }
    }
}
