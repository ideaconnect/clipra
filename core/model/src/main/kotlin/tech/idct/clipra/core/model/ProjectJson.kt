package tech.idct.clipra.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads and writes project documents. Every document carries a schema `version`; reading an
 * older document runs the [Migration]s from its version up to [SCHEMA_VERSION] on the JSON tree
 * before decoding it. Unknown keys are ignored and new fields need defaults, so adding a field
 * doesn't need a migration; renaming, moving or changing the meaning of one does.
 */
class ProjectJson(private val migrations: List<Migration> = MIGRATIONS) {
    /** Moves a document from [fromVersion] to `fromVersion + 1`. */
    fun interface Migration {
        fun migrate(document: JsonObject): JsonObject
    }

    /** A document from a newer Clipra than this one. */
    class NewerVersionException(val version: Int) :
        IllegalStateException("Project schema $version is newer than $SCHEMA_VERSION")

    fun encode(project: Project): String = json.encodeToString(Project.serializer(), project)

    fun decode(text: String): Project {
        var document = json.parseToJsonElement(text).jsonObject
        var version = document["version"]?.jsonPrimitive?.intOrNull ?: 1
        if (version > currentVersion) throw NewerVersionException(version)
        while (version < currentVersion) {
            document = migrations[version - 1].migrate(document)
            version++
            document = JsonObject(document + ("version" to JsonPrimitive(version)))
        }
        return json.decodeFromJsonElement(Project.serializer(), document)
    }

    /** The newest version these migrations reach: 1 plus one per migration. */
    private val currentVersion: Int get() = 1 + migrations.size

    companion object {
        /** The schema version new documents are written with. */
        const val SCHEMA_VERSION: Int = 1

        /** `MIGRATIONS[n - 1]` moves a document from version n to n + 1. */
        val MIGRATIONS: List<Migration> = emptyList()

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        init {
            check(1 + MIGRATIONS.size == SCHEMA_VERSION) {
                "SCHEMA_VERSION must be 1 plus the number of migrations"
            }
        }
    }
}
