package tech.idct.clipra.core.engine.media

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.render.ProjectRender
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.models.ModelStore
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Speech and beats (#40) on the device, the way clips get them: a recording is decoded, analysed
 * and kept in the media cache, where the resolver and the timeline read them.
 */
@RunWith(AndroidJUnit4::class)
class SoundAnalyzerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rate = 48_000

    @Test
    fun speechIsFoundAndKept() {
        val random = Random(5)
        val samples = FloatArray(rate * 6) { i ->
            val t = i.toDouble() / rate
            val speaking = t in 1.0..3.0
            val voice = if (speaking) {
                0.2 * (0.5 + 0.5 * sin(2 * PI * 4 * t)) *
                    random.nextGaussian()
            } else {
                0.0
            }
            (0.04 * sin(2 * PI * 110 * t) + 0.002 * random.nextGaussian() + voice).toFloat()
        }
        val (analyzer, cache, asset) = setUp("voice", samples)
        val speech = analyzer.prepareSpeech(asset)
        assertNotNull(speech)
        assertEquals(1, speech!!.size)
        assertEquals(1_000_000.0, speech[0].first.toDouble(), 100_000.0)
        assertEquals(3_000_000.0, speech[0].last.toDouble(), 150_000.0)
        assertEquals(speech, SoundAnalyzer.readSpeech(cache, asset.id))
        val media = ProjectMediaResolver(context, cache, forExport = true, maxImageSide = 0)
        assertEquals(speech, media.speech(asset))
    }

    @Test
    fun beatsLineUpWithTheKicks() {
        val random = Random(9)
        val samples = FloatArray(rate * 16) { i ->
            (0.04 * sin(2 * PI * 220 * i / rate) + 0.003 * random.nextGaussian()).toFloat()
        }
        // 100 BPM from 0.4 s.
        val kicks = (0 until 26).map { 0.4 + it * 0.6 }
        for (kick in kicks) {
            val start = (kick * rate).toInt()
            for (i in 0 until rate / 10) {
                if (start + i >= samples.size) break
                val t = i.toDouble() / rate
                samples[start + i] +=
                    (0.8 * sin(2 * PI * (60 - 200 * t) * t) * exp(-t * 30)).toFloat()
            }
        }
        val (analyzer, cache, asset) = setUp("music", samples)
        val beats = analyzer.prepareBeats(asset)
        assertNotNull(beats)
        assertEquals(100f, beats!!.bpm, 2f)
        kicks.drop(1).dropLast(1).forEach { kick ->
            val us = (kick * 1e6).toLong()
            assertTrue("a beat near $kick s", beats.timesUs.any { abs(it - us) <= 30_000 })
        }
        assertEquals(beats, SoundAnalyzer.readBeats(cache, asset.id))
    }

    /**
     * Auto-duck under speech (TabletAudio board): once music asks for it, the other clips'
     * speech is found, and the music ducks under it in the timeline.
     */
    @Test
    fun musicDucksUnderTheSpeechItAsksFor() {
        val random = Random(5)
        val spoken = FloatArray(rate * 6) { i ->
            val t = i.toDouble() / rate
            val voice = if (t in 1.0..3.0) {
                0.2 * (0.5 + 0.5 * sin(2 * PI * 4 * t)) * random.nextGaussian()
            } else {
                0.0
            }
            (0.04 * sin(2 * PI * 110 * t) + 0.002 * random.nextGaussian() + voice).toFloat()
        }
        val played = FloatArray(rate * 6) { i -> (0.1 * sin(2 * PI * 220 * i / rate)).toFloat() }
        val (analyzer, cache, recorded) = setUp("autoduck-vo", spoken)
        // The timeline reads media by URI, as imports keep them.
        val vo = recorded.copy(uri = Uri.fromFile(File(recorded.uri)).toString())
        val songFile = File(context.cacheDir, "autoduck-song.wav")
        WavWriter(songFile, rate).use { it.write(played, played.size) }
        val song = MediaAsset(
            "autoduck-song",
            Uri.fromFile(songFile).toString(),
            MediaKind.Audio,
            "song.wav",
            6_000_000,
            hasAudio = true,
        )
        val sixSeconds = 6_000_000L
        val project = Project(
            id = "autoduck",
            name = "Auto-duck",
            media = listOf(vo, song),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip(
                            "music",
                            ClipType.Audio,
                            0,
                            sixSeconds,
                            MediaRef(song.id, 0, sixSeconds),
                            audio = AudioSettings(duckUnderSpeech = 12f),
                        ),
                    ),
                ),
                Layer(
                    "L2",
                    clips = listOf(
                        Clip("voice", ClipType.Audio, 0, sixSeconds, MediaRef(vo.id, 0, sixSeconds)),
                    ),
                ),
            ),
        )

        analyzer.requestSpeech(project)

        assertNotNull(analyzer.speech(vo))
        // The music's own speech isn't needed.
        assertNull(analyzer.speech(song))
        val media = ProjectMediaResolver(context, cache, forExport = true, maxImageSide = 0)
        val music = ProjectRender.timeline(project, media, 360, 640).layers[0].clips.single()
        val duck = music.ducks.single()
        assertEquals(12f, duck.depthDb)
        assertEquals(1_000_000.0, duck.speech.first().first.toDouble(), 100_000.0)
    }

    private fun setUp(
        name: String,
        samples: FloatArray,
    ): Triple<SoundAnalyzer, MediaCache, MediaAsset> {
        val file = File(context.cacheDir, "$name.wav")
        WavWriter(file, rate).use { it.write(samples, samples.size) }
        val cache = MediaCache(File(context.cacheDir, "analysis-$name")).also {
            it.speech(name).parentFile?.deleteRecursively()
            it.voiceRecorded(name).parentFile?.deleteRecursively()
        }
        val voices = VoiceCleaner(context, cache, ModelStore(File(context.filesDir, "models")))
        val asset = MediaAsset(
            name,
            file.path,
            MediaKind.Audio,
            "$name.wav",
            samples.size * 1_000_000L / rate,
            hasAudio = true,
        )
        return Triple(SoundAnalyzer(cache, voices), cache, asset)
    }
}
