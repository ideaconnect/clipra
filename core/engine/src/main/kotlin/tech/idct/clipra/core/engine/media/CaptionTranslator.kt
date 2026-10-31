package tech.idct.clipra.core.engine.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.models.AiModel
import tech.idct.clipra.core.models.ModelCatalog
import tech.idct.clipra.core.models.ModelStore
import tech.idct.clipra.ml.translate.Marian
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translates captions on the phone (#47, ADR 0007) with Marian and Mozilla's Firefox
 * Translations models. Mozilla trains to and from English, so a pair without English goes
 * through it, with two models. Nothing leaves the phone.
 */
@Singleton
class CaptionTranslator @Inject constructor(private val models: ModelStore) {
    /** Every language some model translates from or into, by ISO 639-1 code. */
    val languages: List<String> = ModelCatalog.translations
        .flatMap { it.name.split('-') }
        .distinct()

    /** The models that take [from] to [to], in order; null when no route exists. */
    fun route(from: String, to: String): List<AiModel>? {
        if (from == to) return null
        ModelCatalog.translation(from, to)?.let { return listOf(it) }
        val toEnglish = ModelCatalog.translation(from, ENGLISH) ?: return null
        val fromEnglish = ModelCatalog.translation(ENGLISH, to) ?: return null
        return listOf(toEnglish, fromEnglish)
    }

    /** The languages [from] can be translated into. */
    fun targets(from: String): List<String> = languages.filter { route(from, it) != null }

    /** Whether every model of the route from [from] to [to] is on the phone. */
    fun isReady(from: String, to: String): Boolean =
        route(from, to)?.all(models::isInstalled) == true

    /**
     * Translates [lines] from [from] to [to], one line per caption, keeping their order.
     * The models must be installed ([isReady]). Runs on a background thread.
     */
    suspend fun translate(lines: List<String>, from: String, to: String): List<String> =
        withContext(Dispatchers.Default) {
            val route = route(from, to) ?: throw IOException("No translation from $from to $to")
            var text = lines
            for (model in route) {
                val (weights, vocab, shortlist) = model.files.map {
                    models.file(model, it.installedName)
                        ?: throw IOException("${model.id} is not installed")
                }
                Marian.open(weights, vocab, shortlist).use { text = it.translate(text) }
            }
            text
        }

    private companion object {
        const val ENGLISH = "en"
    }
}
