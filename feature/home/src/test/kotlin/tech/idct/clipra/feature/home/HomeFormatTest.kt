package tech.idct.clipra.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.idct.clipra.core.models.ModelCatalog
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class HomeFormatTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 10, 28, 18, 30)

    private fun ago(then: LocalDateTime) = EditedAgo.of(now.toMillis(), then.toMillis(), zone)

    private fun LocalDateTime.toMillis() = atZone(zone).toInstant().toEpochMilli()

    @Test
    fun editedAgoBuckets() {
        assertEquals(EditedAgo.JustNow, ago(now.minusSeconds(30)))
        assertEquals(EditedAgo.Minutes(5), ago(now.minusMinutes(5)))
        assertEquals(EditedAgo.Hours(2), ago(now.minusHours(2)))
        // Still today: hours, even if many.
        assertEquals(EditedAgo.Hours(18), ago(now.minusHours(18).minusMinutes(20)))
        // The calendar day before, even if under 24 hours ago.
        assertEquals(EditedAgo.Yesterday, ago(LocalDateTime.of(2026, 10, 27, 23, 0)))
        assertEquals(EditedAgo.Days(3), ago(now.minusDays(3)))
        assertEquals(EditedAgo.LastWeek, ago(now.minusDays(8)))
        assertEquals(EditedAgo.On(LocalDate.of(2026, 10, 1)), ago(now.minusDays(27)))
    }

    @Test
    fun aClockSetBackwardsCountsAsJustNow() {
        assertEquals(EditedAgo.JustNow, ago(now.plusMinutes(3)))
    }

    @Test
    fun durationsAsTheDesignShowsThem() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:42", formatDuration(42_000_000))
        assertEquals("1:18", formatDuration(78_400_000))
        assertEquals("2:05", formatDuration(125_000_000))
        assertEquals("1:02:03", formatDuration(3_723_000_000))
    }

    /** The AI card as on the Main board: "On-device AI ready", Whisper Base and DeepFilterNet 3. */
    @Test
    fun theAiCardNamesTheDownloadedModels() {
        val installed = listOf(
            ModelCatalog.translateEnPl,
            ModelCatalog.deepFilterNet,
            ModelCatalog.whisperBase,
        )

        val status = aiStatus(installed, "Whisper %1${'$'}s", Locale.US)

        assertEquals(
            AiStatus(ready = true, summary = "Whisper Base · DeepFilterNet 3 · English → Polish"),
            status,
        )
    }

    /** Without a captions model the card says what the models are for. */
    @Test
    fun theAiCardIsNotReadyWithoutCaptions() {
        assertNull(aiStatus(emptyList(), "Whisper %1${'$'}s", Locale.US))
        assertNull(aiStatus(listOf(ModelCatalog.deepFilterNet), "Whisper %1${'$'}s", Locale.US))
    }
}
