package tech.idct.clipra.core.model

/**
 * Editing a caption's words (#46) with their timing kept: retyping a word, and finding and
 * replacing text across words. A change spans whole words; its new words share their time by
 * length, and count as checked (confidence 1).
 */
object CaptionText {
    /**
     * [words] with [text] in place of the word at [index]: spaces make more words, and nothing
     * takes the word out.
     */
    fun retype(words: List<Word>, index: Int, text: String): List<Word> {
        require(index in words.indices)
        return words.take(index) + respread(words[index], words[index], text) +
            words.drop(index + 1)
    }

    /** The words that [query] touches, ignoring case, as ranges of word indices. */
    fun matches(words: List<Word>, query: String): List<IntRange> =
        groups(words, query).map { it.words }

    /**
     * [words] with every [find] (ignoring case) replaced by [replacement], or null when it isn't
     * there. A match may be part of a word or run over several.
     */
    fun replace(words: List<Word>, find: String, replacement: String): List<Word>? {
        val groups = groups(words, find)
        if (groups.isEmpty()) return null
        val joined = joined(words)
        val starts = starts(words)
        val result = mutableListOf<Word>()
        var next = 0
        for (group in groups) {
            result += words.subList(next, group.words.first)
            val from = starts[group.words.first]
            val to = starts[group.words.last] + words[group.words.last].text.length
            val text = buildString {
                var at = from
                for (hit in group.hits) {
                    append(joined, at, hit.first)
                    append(replacement)
                    at = hit.last + 1
                }
                append(joined, at, to)
            }
            result += respread(words[group.words.first], words[group.words.last], text)
            next = group.words.last + 1
        }
        result += words.subList(next, words.size)
        return result
    }

    /** Matches of [query] in the words joined by spaces, grouped by the words they touch. */
    private class Group(val words: IntRange, val hits: List<IntRange>)

    private fun groups(words: List<Word>, query: String): List<Group> {
        val needle = query.trim()
        if (needle.isEmpty() || words.isEmpty()) return emptyList()
        val joined = joined(words)
        val starts = starts(words)
        val wordAt = { char: Int -> starts.indexOfLast { it <= char } }
        val groups = mutableListOf<Group>()
        var from = 0
        while (true) {
            val at = joined.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            val hit = at until at + needle.length
            val span = wordAt(hit.first)..wordAt(hit.last)
            val last = groups.lastOrNull()
            if (last != null && span.first <= last.words.last) {
                val words = last.words.first..maxOf(last.words.last, span.last)
                groups[groups.lastIndex] = Group(words, last.hits + listOf(hit))
            } else {
                groups += Group(span, listOf(hit))
            }
            from = hit.last + 1
        }
        return groups
    }

    private fun joined(words: List<Word>) = words.joinToString(" ") { it.text }

    /** Where each word starts in [joined]. */
    private fun starts(words: List<Word>): List<Int> {
        var at = 0
        return words.map { word -> at.also { at += word.text.length + 1 } }
    }

    /** [text]'s words over the time from [first]'s start to [last]'s end, by length. */
    private fun respread(first: Word, last: Word, text: String): List<Word> =
        words(text, first.startUs, last.endUs)

    /** [text]'s words, sharing the time from [startUs] to [endUs] by their length. */
    fun words(text: String, startUs: Long, endUs: Long): List<Word> {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val start = startUs
        val span = endUs - start
        val total = tokens.sumOf { it.length }.toLong()
        var chars = 0L
        return tokens.map { token ->
            val from = start + span * chars / total
            chars += token.length
            Word(token, from, start + span * chars / total)
        }
    }
}
