package tech.idct.clipra.feature.captions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.model.CaptionPosition
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.CaptionStyle
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.LinkGroup
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transcript
import tech.idct.clipra.core.model.Word
import tech.idct.clipra.core.model.edit.TranslateCaptions
import tech.idct.clipra.core.models.ModelCatalog
import java.util.Locale

/** The CaptionsEdit board (design/screens/phone/CaptionsEdit.png) with the design's captions. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class CaptionsEditScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private fun caption(id: String, from: Double, to: Double, text: String, unsure: Set<String>) =
        Clip(
            id = id,
            type = ClipType.Caption,
            startUs = (from * 1e6).toLong(),
            durationUs = ((to - from) * 1e6).toLong(),
            caption = CaptionSpec(
                words = text.split(" ").mapIndexed { i, word ->
                    Word(
                        word,
                        i * 300_000L,
                        i * 300_000L + 250_000,
                        if (word in unsure) 0.3f else 0.95f,
                    )
                },
            ),
        )

    private val captions = listOf(
        caption("c1", 6.0, 8.8, "We finally made it to Lisbon", emptySet()),
        caption("c2", 9.4, 11.6, "The light here is unreal", emptySet()),
        caption("c3", 12.1, 15.0, "Tomorrow we’re taking the tram up to Alfama", setOf("Alfama")),
        caption("c4", 15.6, 18.2, "and then the train out to Sintra", setOf("Sintra")),
        caption("c5", 18.9, 21.0, "if the weather holds", emptySet()),
    )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        layers = listOf(Layer("L1"), Layer("L2", clips = captions)),
        links = listOf(LinkGroup("g", listOf("src") + captions.map { it.id })),
        transcripts = listOf(
            Transcript("src", "en", model = ModelCatalog.whisperBase.id, detected = true),
        ),
    )

    @Test
    fun captionsAsDesigned() {
        Locale.setDefault(Locale.US)
        val styles = mutableListOf<CaptionStyle>()
        val positions = mutableListOf<CaptionPosition>()
        val seeks = mutableListOf<Long>()
        var autoCaptions = 0
        compose.setContent {
            ClipraTheme {
                CaptionsEditScreen(
                    state = CaptionsEditUiState(project, loaded = true, playheadUs = 8_300_000),
                    scale = null,
                    actions = CaptionsEditActions(
                        onDone = {},
                        onStyle = { styles += it },
                        onPosition = { positions += it },
                        onScale = {},
                        onScaleDone = {},
                        onSeek = { seeks += it },
                        onEditByText = {},
                        onAutoCaptions = { autoCaptions++ },
                    ),
                    preview = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(ClipraTheme.colors.surfaceRaised),
                        )
                    },
                )
            }
        }
        compose.onNodeWithText("Whisper Base · English (detected)").assertExists()
        compose.onNodeWithText("5 captions").assertExists()
        compose.onNodeWithText("2 words to check").assertExists()
        compose.onNodeWithText("22 pt").assertExists()
        compose.onNodeWithText("00:06.0 → 00:08.8").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("style:Karaoke").performClick()
        compose.onNodeWithText("Top").performClick()
        compose.onNodeWithTag("caption:c3").performClick()
        assertEquals(listOf(CaptionStyle.Karaoke), styles)
        assertEquals(listOf(CaptionPosition.Top), positions)
        assertEquals(listOf(12_100_000L), seeks)

        // The model and language lead to Auto captions, to make the captions again (#181).
        compose.onNodeWithTag("header-subtitle").performClick()
        assertEquals(1, autoCaptions)
    }

    /** The design state in Polish, to see that every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun captionsAsDesignedInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        val styles = mutableListOf<CaptionStyle>()
        val positions = mutableListOf<CaptionPosition>()
        val seeks = mutableListOf<Long>()
        compose.setContent {
            ClipraTheme {
                CaptionsEditScreen(
                    state = CaptionsEditUiState(project, loaded = true, playheadUs = 8_300_000),
                    scale = null,
                    actions = CaptionsEditActions(
                        onDone = {},
                        onStyle = { styles += it },
                        onPosition = { positions += it },
                        onScale = {},
                        onScaleDone = {},
                        onSeek = { seeks += it },
                        onEditByText = {},
                    ),
                    preview = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(ClipraTheme.colors.surfaceRaised),
                        )
                    },
                )
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    private class Calls {
        val words = mutableListOf<Triple<String, Int, String>>()
        val splits = mutableListOf<Pair<String, Int>>()
        val merges = mutableListOf<String>()
        val replaced = mutableListOf<Pair<String, String>>()
        var saved = 0
        var imported = 0
        val shown = mutableListOf<String>()
        var translateOpened = 0
        val translatedTo = mutableListOf<String>()
    }

    private fun show(
        calls: Calls = Calls(),
        pick: WordPick? = null,
        find: String? = null,
        message: CaptionsMessage? = null,
        locale: Locale = Locale.US,
        project: Project = this.project,
        translate: TranslateSheet? = null,
    ) {
        Locale.setDefault(locale)
        compose.setContent {
            ClipraTheme {
                CaptionsEditScreen(
                    state = CaptionsEditUiState(
                        project,
                        loaded = true,
                        playheadUs = 8_300_000,
                        message = message,
                        translate = translate,
                    ),
                    scale = null,
                    actions = CaptionsEditActions(
                        onDone = {},
                        onStyle = {},
                        onPosition = {},
                        onScale = {},
                        onScaleDone = {},
                        onSeek = {},
                        onEditByText = {},
                        onEditWord = { id, i, text -> calls.words += Triple(id, i, text) },
                        onSplit = { id, i -> calls.splits += id to i },
                        onMerge = { calls.merges += it },
                        onReplaceAll = { find, with -> calls.replaced += find to with },
                        onSaveSrt = { calls.saved++ },
                        onImportSrt = { calls.imported++ },
                        onShowLanguage = { calls.shown += it },
                        onTranslate = { calls.translateOpened++ },
                        onTranslateTo = { calls.translatedTo += it },
                    ),
                    preview = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(ClipraTheme.colors.surfaceRaised),
                        )
                    },
                    initialPick = pick,
                    initialFind = find,
                )
            }
        }
    }

    /** #46: tap a word to retype it, split the caption there or merge it with the next. */
    @Test
    fun editingAWord() {
        val calls = Calls()
        show(calls, pick = WordPick("c3", 7))
        compose.onNodeWithTag("word-input").assertTextContains("Alfama")
        compose.onRoot().captureRoboImage(roborazziOptions = options)
        compose.onNodeWithTag("word-input").performTextReplacement("Alfama!")
        compose.onNodeWithTag("word-save").performClick()
        assertEquals(listOf(Triple("c3", 7, "Alfama!")), calls.words)

        compose.onNodeWithTag("word:c4:4").performScrollTo().performClick()
        compose.onNodeWithTag("word-split").performClick()
        assertEquals(listOf("c4" to 4), calls.splits)
        compose.onNodeWithTag("word:c4:4").performScrollTo().performClick()
        compose.onNodeWithTag("word-merge").performClick()
        assertEquals(listOf("c4"), calls.merges)

        // The first word of the last caption can neither split nor merge.
        compose.onNodeWithTag("word:c5:0").performScrollTo().performClick()
        compose.onNodeWithTag("word-split").assertDoesNotExist()
        compose.onNodeWithTag("word-merge").assertDoesNotExist()
    }

    @Test
    fun wordsToCheckOpenTheFirst() {
        show()
        compose.onNodeWithTag("captions-check").performClick()
        compose.onNodeWithTag("word-input").assertTextContains("Alfama")
    }

    private fun projectWith(captions: List<Clip>) = project.copy(
        layers = listOf(Layer("L1"), Layer("L2", clips = captions)),
        links = listOf(LinkGroup("g", listOf("src") + captions.map { it.id })),
    )

    /** #235: with Sintra checked, one word is left, and English says so in the singular. */
    @Test
    fun oneWordLeftToCheck() {
        val sintraChecked = captions.map {
            if (it.id == "c4") {
                caption("c4", 15.6, 18.2, "and then the train out to Sintra", emptySet())
            } else {
                it
            }
        }
        show(project = projectWith(sintraChecked))
        compose.onNodeWithText("5 captions").assertExists()
        compose.onNodeWithTag("captions-check").assertTextEquals("1 word to check")
        compose.onNodeWithText("1 words to check").assertDoesNotExist()
    }

    /** #235: a single caption is "1 caption", not "1 captions". */
    @Test
    fun aSingleCaption() {
        show(project = projectWith(listOf(captions[2])))
        compose.onNodeWithText("1 caption").assertExists()
        compose.onNodeWithText("1 captions").assertDoesNotExist()
        compose.onNodeWithTag("captions-check").assertTextEquals("1 word to check")
    }

    /** #235: Polish keeps its label and colon for every number. */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun aSingleCaptionInPolish() {
        show(project = projectWith(listOf(captions[2])), locale = Locale.forLanguageTag("pl-PL"))
        compose.onNodeWithText("Napisy: 1").assertExists()
        compose.onNodeWithTag("captions-check").assertTextEquals("Słowa do sprawdzenia: 1")
    }

    @Test
    fun searchAndReplace() {
        val calls = Calls()
        show(calls, find = "the")
        // "The light", "the tram", "then the train", "the weather" (twice).
        compose.onNodeWithText("6 matches").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
        compose.onNodeWithTag("search-replace").performTextReplacement("a")
        compose.onNodeWithTag("search-replace-all").performClick()
        assertEquals(listOf("the" to "a"), calls.replaced)

        compose.onNodeWithTag("search-find").performTextReplacement("Porto")
        compose.onNodeWithText("0 matches").assertExists()
        compose.onNodeWithTag("search-replace-all").assertIsNotEnabled()
        compose.onNodeWithTag("search-close").performClick()
        compose.onNodeWithTag("captions-search").assertDoesNotExist()
    }

    @Test
    fun savingAndImportingSrt() {
        val calls = Calls()
        show(calls, message = CaptionsMessage.Imported(5))
        compose.onNodeWithText("Imported 5 captions").assertExists()
        compose.onNodeWithTag("captions-save-srt").performClick()
        assertEquals(1, calls.saved)

        // Import asks first, since it replaces the five captions (#233).
        compose.onNodeWithTag("captions-import-srt").performClick()
        compose.onNodeWithText("Replace the captions?").assertExists()
        compose.onNodeWithTag("import-srt-replaces")
            .assertTextEquals("Replaces the 5 captions, edits included.")
        compose.onNodeWithTag("import-srt-cancel").performClick()
        compose.onNodeWithTag("import-srt-replaces").assertDoesNotExist()
        assertEquals(0, calls.imported)

        compose.onNodeWithTag("captions-import-srt").performClick()
        compose.onNodeWithTag("import-srt-confirm").performClick()
        compose.onNodeWithTag("import-srt-replaces").assertDoesNotExist()
        assertEquals(1, calls.imported)
    }

    /** #233: with a translation, the question says it goes too. */
    @Test
    fun importAsksBeforeReplacingCaptionsAndTranslations() {
        show(project = translated())
        compose.onNodeWithTag("captions-import-srt").performClick()
        compose.onNodeWithTag("import-srt-replaces")
            .assertTextEquals("Replaces the 5 captions and their translations, edits included.")
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun importAsksBeforeReplacingCaptionsAndTranslationsInPolish() {
        show(project = translated(), locale = Locale.forLanguageTag("pl-PL"))
        compose.onNodeWithTag("captions-import-srt").performClick()
        compose.onNodeWithTag("import-srt-replaces")
            .assertTextEquals("Zastąpi 5 napisów i ich tłumaczenia, razem z poprawkami.")
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    /** With no captions there is nothing to replace, so the file picker opens at once. */
    @Test
    fun importWithoutCaptionsAsksNothing() {
        val calls = Calls()
        show(calls, project = project.copy(layers = listOf(Layer("L1")), links = emptyList()))
        compose.onNodeWithTag("captions-import-srt").performClick()
        compose.onNodeWithTag("import-srt-replaces").assertDoesNotExist()
        assertEquals(1, calls.imported)
    }

    /**
     * #233: captions from an .srt file came from no transcript, so the header no longer names
     * the Whisper model and language the project's clips were transcribed with.
     */
    @Test
    fun importedCaptionsDontNameWhisper() {
        show(project = project.copy(links = emptyList()))
        compose.onNodeWithText("Whisper Base · English (detected)").assertDoesNotExist()
        compose.onNodeWithTag("header-subtitle").assertTextContains("Auto captions")
    }

    @Test
    fun importReplacesTheCaptionsAsHeard() {
        val translated = translated()
        assertEquals(5, CaptionsEditUiState(project).importReplaces)
        // A translation is a copy of each caption, not more captions.
        assertEquals(5, CaptionsEditUiState(translated).importReplaces)
        // A translation left without its captions as heard still goes with an import.
        val alone = translated.copy(
            layers = translated.layers.map { layer ->
                layer.copy(clips = layer.clips.filter { it.caption?.language == "pl" })
            },
        )
        assertEquals(5, CaptionsEditUiState(alone).importReplaces)
        assertEquals(0, CaptionsEditUiState(Project(id = "empty", name = "Empty")).importReplaces)
    }

    private fun translated(): Project = TranslateCaptions(
        "pl",
        captions.map { it.id }.zip(polish).toMap(),
    ).apply(project)

    private val polish = listOf(
        "W końcu dotarliśmy do Lizbony",
        "Światło tutaj jest nierealne",
        "Jutro jedziemy tramwajem do Alfamy",
        "a potem pociągiem do Sintry",
        "jeśli pogoda się utrzyma",
    )

    /** #47: the captions in Polish, with the chips to switch back. */
    @Test
    fun aTranslationIsShownWithItsLanguages() {
        val calls = Calls()
        show(calls, project = translated())
        compose.onNodeWithText("dotarliśmy").assertExists()
        compose.onNodeWithText("EN · original").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("captions-language:").performClick()
        compose.onNodeWithTag("captions-translate").performClick()
        assertEquals(listOf(""), calls.shown)
        assertEquals(1, calls.translateOpened)
    }

    private val sheet = TranslateSheet(
        from = "en",
        targets = listOf(TranslateTarget("pl", 0), TranslateTarget("de", 25_720_702)),
        sources = listOf("en", "pl", "de"),
    )

    @Test
    fun translateOfflineOffersTheLanguages() {
        val calls = Calls()
        show(calls, translate = sheet)
        compose.onNodeWithText("From English into").assertExists()
        compose.onNodeWithText("On this phone").assertExists()
        compose.onNodeWithText("Download 26 MB").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
        compose.onNodeWithTag("translate-to:pl").performClick()
        assertEquals(listOf("pl"), calls.translatedTo)
    }

    @Test
    fun aTranslationUnderWayShowsItsStep() {
        val calls = Calls()
        show(
            calls,
            translate = sheet.copy(busy = TranslateBusy("de", TranslateBusy.Step.WaitingForWifi)),
        )
        compose.onNodeWithText("Waiting for Wi-Fi").assertExists()
        // Nothing else starts while one runs.
        compose.onNodeWithTag("translate-to:pl").performClick()
        assertEquals(emptyList<String>(), calls.translatedTo)
    }

    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun translateOfflineInPolish() {
        show(translate = sheet, locale = Locale.forLanguageTag("pl-PL"))
        compose.onNodeWithText("Z języka: Angielski na").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun searchAndReplaceInPolish() {
        show(find = "the", locale = Locale.forLanguageTag("pl-PL"))
        compose.onNodeWithText("6 wystąpień").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun editingAWordInPolish() {
        show(pick = WordPick("c3", 7), locale = Locale.forLanguageTag("pl-PL"))
        compose.onNodeWithText("Podziel napis tutaj").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
