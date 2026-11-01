package tech.idct.clipra.ml.whisper

/**
 * The language each clip of one Auto captions run is heard in, when it is detected (#188).
 * Whisper detects a clip's language from its first 30 s, and on faint voices in the background
 * that is close to a guess: a street clip in a Polish project came out as "¡Vamos a ver,
 * vamos!". So a clip Whisper is sure of keeps its language, and the others take the run's: the
 * language Whisper is surest of, added up over the clips it is sure of.
 */
object LanguageChoice {
    /** Below this, a detection is a guess. */
    const val SURE = 0.5f

    /** The language for each clip of [detections] (by clip id). */
    fun <K> pick(detections: Map<K, Whisper.Detection>): Map<K, String> {
        val sure = detections.values.filter { it.probability >= SURE }
        val run = sure.ifEmpty { detections.values }
            .groupBy { it.language }
            .maxByOrNull { (_, found) -> found.sumOf { it.probability.toDouble() } }
            ?.key ?: return emptyMap()
        return detections.mapValues { (_, heard) ->
            if (heard.probability >= SURE) heard.language else run
        }
    }
}
