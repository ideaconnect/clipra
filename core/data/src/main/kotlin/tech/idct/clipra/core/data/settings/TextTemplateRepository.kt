package tech.idct.clipra.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tech.idct.clipra.core.model.TextTemplate
import tech.idct.clipra.core.model.TextTemplates
import javax.inject.Inject

/**
 * The text templates the user saved (#34), newest first, kept with the settings; they work in
 * every project.
 */
class TextTemplateRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {
    val saved: Flow<List<TextTemplate>> = dataStore.data.map { TextTemplates.decode(it[SAVED]) }

    suspend fun save(template: TextTemplate) {
        dataStore.edit { prefs ->
            val others = TextTemplates.decode(prefs[SAVED]).filter { it.id != template.id }
            prefs[SAVED] = TextTemplates.encode((listOf(template) + others).take(MAX_SAVED))
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { prefs ->
            prefs[SAVED] = TextTemplates.encode(
                TextTemplates.decode(prefs[SAVED]).filter { it.id != id },
            )
        }
    }

    private companion object {
        val SAVED = stringPreferencesKey("text_templates")

        /** Enough for anyone's own looks; the oldest goes past this. */
        const val MAX_SAVED = 24
    }
}
