package tech.idct.clipra.core.model.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Size
import kotlin.math.abs

/** The export's time left follows the work, and a slow stretch shows within seconds (#183). */
class ExportWorkTest {
    private val sec = 1_000_000L
    private val hd = Size(1920, 1080)

    private fun video(id: String, start: Long, media: String) =
        Clip(id, ClipType.Video, start, 10 * sec, MediaRef(media, 0, 10 * sec))

    /** Photos for 10 s, then 4K video for 10 s, exported in 1080p. */
    private val project = Project(
        id = "p",
        name = "p",
        media = listOf(
            MediaAsset("uhd", "content://1", MediaKind.Video, width = 3840, height = 2160),
            MediaAsset("fhd", "content://2", MediaKind.Video, width = 1920, height = 1080),
            MediaAsset("photo", "content://3", MediaKind.Photo, width = 4000, height = 3000),
        ),
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    Clip("still", ClipType.Photo, 0, 10 * sec, MediaRef("photo", 0, 0)),
                    video("talk", 10 * sec, "uhd"),
                ),
            ),
        ),
    )

    /**
     * Seconds left at each poll (every 250 ms) of an export whose real pace at each progress is
     * [msPerProgress], with the true seconds left alongside.
     */
    private fun export(
        timeLeft: TimeLeft,
        msPerProgress: (Float) -> Double,
    ): List<Triple<Float, Int?, Double>> {
        val step = 0.001f
        val ms = generateSequence(0f) { it + step }.takeWhile { it <= 1f }
            .map { msPerProgress(it) * step }.toList()
        val total = ms.sum()
        var elapsed = 0.0
        var nextPoll = 0.0
        val seen = mutableListOf<Triple<Float, Int?, Double>>()
        ms.forEachIndexed { i, cost ->
            elapsed += cost
            if (elapsed >= nextPoll) {
                val progress = (i + 1) * step
                val left = timeLeft.update(elapsed.toLong(), progress)
                seen += Triple(progress, left, (total - elapsed) / 1000)
                nextPoll += 250
            }
        }
        return seen
    }

    /** Within a quarter of the true seconds left, give or take the rounding. */
    private fun close(left: Int, truth: Double) = abs(left - truth) <= truth * 0.25 + 1

    @Test
    fun theWorkIsInTheVideoNotThePhotos() {
        val work = ExportWork(project, hd)
        // A frame is 1, a photo on it 0.1, 4K video 4 more: the photos are 1.1 / 6.1 of it.
        assertEquals(1.1f / 6.1f, work.share(0.5f), 0.01f)
        assertEquals(0f, work.share(0f), 0f)
        assertEquals(1f, work.share(1f), 1e-6f)

        // Rendered at the pace of its work, 6 s per unit of it: right almost from the start.
        val seen = export(TimeLeft(work::share)) { if (it < 0.5f) 1.1 * 6_000 else 5.0 * 6_000 }
        for ((progress, left, truth) in seen.filter { it.first >= 0.1f }) {
            assertTrue("at $progress: $left s, truly $truth s", close(left!!, truth))
        }
    }

    @Test
    fun aStretchSlowerThanItsWorkShowsWithinSeconds() {
        // Two 1080p videos, the second slower to decode than its size says: three times.
        val clips = listOf(video("a", 0, "fhd"), video("b", 10 * sec, "fhd"))
        val even = project.copy(layers = listOf(Layer("L1", clips = clips)))
        val seen = export(TimeLeft(ExportWork(even, hd)::share)) {
            if (it < 0.5f) 20_000.0 else 60_000.0
        }
        // The slow half starts 10 s in; 15 s into it the pace is known.
        val slowFrom = seen.first { it.first >= 0.5f }
        val settled = seen.filter { it.first > 0.5f && it.third <= slowFrom.third - 15 }
        assertTrue(settled.isNotEmpty())
        for ((progress, left, truth) in settled) {
            assertTrue("at $progress: $left s, truly $truth s", close(left!!, truth))
        }
    }

    @Test
    fun nothingIsShownUntilTheExportIsUnderWay() {
        val seen = export(TimeLeft(ExportWork(project, hd)::share)) { p ->
            if (p < 0.5f) 1.1 * 6_000 else 5.0 * 6_000
        }
        assertTrue(seen.first { it.first >= 0.02f }.second != null)
        assertEquals(null, seen.first().second)
    }
}
