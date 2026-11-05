package tech.idct.clipra.ml.translate

/**
 * chrF (Popović, 2015) as sacrebleu computes it by default, chrF2: character n-grams of 1 to 6
 * with whitespace left out, precision and recall averaged over the orders, then their F-score
 * with recall weighted twice as much (beta 2). The score runs from 0 to 100.
 *
 * A word that differs in a few letters costs a few points; a translation of another sentence,
 * an untranslated line or an empty one keeps only the letters it shares by chance. That makes it
 * a test of "the same translation, give or take a word", which is what the int8 kernels allow:
 * ruy on arm64 and intgemm on x86_64 compute the products differently, so the scores drift a
 * little and a close choice between two words can go either way on the same model (#202).
 */
internal object Chrf {
    /**
     * The lowest score that still counts as the same translation. Of 32 lines translated with
     * the same model on a Galaxy S9 (arm64) and the Pixel_3_API_29 emulator (x86_64), the 12 that
     * came out differently scored 64.6 to 99.4 against each other. Against the right line,
     * another line's translation scored at most 26.6, the untranslated English line at most 23.6
     * and an empty line 0.
     */
    const val SAME_TRANSLATION = 50.0

    private const val ORDER = 6
    private const val BETA = 2.0

    /** The score of [hypothesis] against [reference]. */
    fun score(hypothesis: String, reference: String): Double {
        val hypothesisChars = codePoints(hypothesis)
        val referenceChars = codePoints(reference)
        var precision = 0.0
        var recall = 0.0
        var orders = 0
        for (n in 1..ORDER) {
            val found = ngrams(hypothesisChars, n)
            val wanted = ngrams(referenceChars, n)
            if (found.isEmpty() || wanted.isEmpty()) continue
            val matches = found.entries.sumOf { (gram, count) -> minOf(count, wanted[gram] ?: 0) }
            precision += matches.toDouble() / found.values.sum()
            recall += matches.toDouble() / wanted.values.sum()
            orders++
        }
        if (orders == 0) return 0.0
        precision /= orders
        recall /= orders
        if (precision + recall == 0.0) return 0.0
        val factor = BETA * BETA
        return 100 * (1 + factor) * precision * recall / (factor * precision + recall)
    }

    private fun codePoints(text: String): IntArray =
        text.filterNot { it.isWhitespace() }.codePoints().toArray()

    private fun ngrams(chars: IntArray, n: Int): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (start in 0..chars.size - n) {
            val gram = String(chars, start, n)
            counts[gram] = (counts[gram] ?: 0) + 1
        }
        return counts
    }
}
