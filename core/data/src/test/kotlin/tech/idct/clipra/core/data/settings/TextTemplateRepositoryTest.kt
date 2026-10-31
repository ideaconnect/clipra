package tech.idct.clipra.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.TextTemplate

/** Saved text templates (#34) are kept for every project, newest first. */
class TextTemplateRepositoryTest {
    private fun template(id: String) = TextTemplate("saved:$id", TextSpec(id), y = 0.3f)

    @Test
    fun savedTemplatesComeNewestFirstAndCanBeDeleted() = runTest {
        val templates = TextTemplateRepository(MemoryDataStore())

        assertTrue(templates.saved.first().isEmpty())
        templates.save(template("a"))
        templates.save(template("b"))
        assertEquals(listOf(template("b"), template("a")), templates.saved.first())
        // Saving one again moves it to the front instead of keeping two.
        templates.save(template("a"))
        assertEquals(listOf("saved:a", "saved:b"), templates.saved.first().map { it.id })
        templates.delete("saved:a")
        assertEquals(listOf(template("b")), templates.saved.first())
    }

    @Test
    fun theOldestGoPastTwentyFour() = runTest {
        val templates = TextTemplateRepository(MemoryDataStore())

        repeat(30) { templates.save(template("$it")) }
        val saved = templates.saved.first()
        assertEquals(24, saved.size)
        assertEquals("saved:29", saved.first().id)
        assertEquals("saved:6", saved.last().id)
    }

    /**
     * Settings in memory: DataStore's file can't be written twice in one test on Windows, where
     * it can't rename over its own file.
     */
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = transform(state.value).also { state.value = it }
    }
}
