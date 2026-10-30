package tech.idct.clipra.core.model

import java.util.Locale

/** SubRip (.srt) captions (#46): what Save .srt writes and Import .srt reads. */
object Srt {
    /** One caption: when it shows on the timeline, and its text. */
    data class Cue(val startUs: Long, val endUs: Long, val text: String)

    /** The project's caption clips as cues, in time order, with the words as typed. */
    fun cues(project: Project): List<Cue> = project.layers
        .flatMap { it.clips }
        .filter { it.type == ClipType.Caption && it.caption != null }
        .sortedBy { it.startUs }
        .map { Cue(it.startUs, it.endUs, it.caption!!.text) }

    fun write(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, cue ->
            append(i + 1).append('\n')
            append(time(cue.startUs)).append(" --> ").append(time(cue.endUs)).append('\n')
            append(cue.text.trim()).append("\n\n")
        }
    }

    /**
     * The cues of an .srt file, in time order. It reads what players accept too: a byte order
     * mark, Windows line ends, a full stop before the milliseconds, missing numbers, and tags
     * such as `<i>` or `{\an8}`, which are dropped. Blocks it can't read are skipped.
     */
    fun read(text: String): List<Cue> {
        val lines = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
        val cues = mutableListOf<Cue>()
        var i = 0
        while (i < lines.size) {
            val match = TIMING.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val (from, to) = match.destructured
            val body = mutableListOf<String>()
            i++
            while (i < lines.size && lines[i].isNotBlank()) {
                // A missing blank line before the next cue's number and timing.
                if (TIMING.containsMatchIn(lines[i])) break
                if (i + 1 < lines.size && lines[i].trim().all { it.isDigit() } &&
                    TIMING.containsMatchIn(lines[i + 1])
                ) {
                    break
                }
                body += lines[i]
                i++
            }
            val start = parse(from)
            val end = parse(to)
            val words = body.joinToString(" ").replace(TAGS, "").trim()
            if (start != null && end != null && end > start && words.isNotEmpty()) {
                cues += Cue(start, end, words.replace(Regex("\\s+"), " "))
            }
        }
        return cues.sortedBy { it.startUs }
    }

    /** "00:00:06,000". */
    fun time(us: Long): String {
        val ms = us / 1_000
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d,%03d",
            ms / 3_600_000,
            ms / 60_000 % 60,
            ms / 1_000 % 60,
            ms % 1_000,
        )
    }

    private fun parse(value: String): Long? {
        val parts = TIME.matchEntire(value.trim())?.destructured ?: return null
        val (h, m, s, frac) = parts
        val ms = frac.padEnd(3, '0').take(3).toLong()
        return ((h.toLong() * 3_600 + m.toLong() * 60 + s.toLong()) * 1_000 + ms) * 1_000
    }

    private const val CLOCK = """\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}"""
    private val TIMING = Regex("""($CLOCK)\s*-->\s*($CLOCK)""")
    private val TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val TAGS = Regex("""<[^>]*>|\{\\[^}]*\}""")
}
