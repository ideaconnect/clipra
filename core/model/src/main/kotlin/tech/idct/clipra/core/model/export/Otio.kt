package tech.idct.clipra.core.model.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.Project
import java.net.URLEncoder

/**
 * The project as an OpenTimelineIO timeline (#59), so another editor can carry on with it. Each
 * layer becomes a video track with its pictures and, when it plays sound, an audio track; L1 is
 * at the bottom of the stack, as in Clipra. Clips sit at their places with gaps between them, in
 * frames at the project's rate. Media clips point at their original files; text, captions and
 * adjustment clips have no file, so they keep what they are in their metadata. Transitions sit
 * between the clips they join; speed is a LinearTimeWarp.
 */
object Otio {
    fun write(project: Project): String {
        val rate = project.format.frameRate.fps.toDouble()
        val writer = Writer(project, rate)
        val tracks = project.layers.flatMap { layer ->
            listOfNotNull(writer.videoTrack(layer), writer.audioTrack(layer))
        }
        val size = project.format.outputSize
        val timeline = buildJsonObject {
            schema("Timeline.1")
            put("name", project.name)
            put("global_start_time", writer.time(0))
            put(
                "metadata",
                clipra {
                    put("width", size.width)
                    put("height", size.height)
                    put("ratio", project.format.ratio.label)
                },
            )
            put(
                "tracks",
                buildJsonObject {
                    schema("Stack.1")
                    item("tracks")
                    put("metadata", JsonObject(emptyMap()))
                    put("source_range", JsonNull)
                    put("children", JsonArray(tracks))
                },
            )
        }
        return json.encodeToString(JsonObject.serializer(), timeline)
    }

    private class Writer(private val project: Project, private val rate: Double) {
        fun time(us: Long): JsonObject = buildJsonObject {
            schema("RationalTime.1")
            put("rate", rate)
            put("value", us * rate / 1_000_000.0)
        }

        fun range(startUs: Long, durationUs: Long): JsonObject = buildJsonObject {
            schema("TimeRange.1")
            put("start_time", time(startUs))
            put("duration", time(durationUs))
        }

        /** The layer's pictures; null when it has only sound. */
        fun videoTrack(layer: Layer): JsonObject? {
            val clips = layer.clips.filter { it.type != ClipType.Audio }
            if (clips.isEmpty()) return null
            return track(layer, "Video", clips, enabled = !layer.hidden, transitions = true)
        }

        /** The layer's sound: its audio clips and the sound of its videos; null when silent. */
        fun audioTrack(layer: Layer): JsonObject? {
            val clips = layer.clips.filter { clip ->
                val asset = clip.source?.let { project.asset(it.mediaId) }
                (clip.type == ClipType.Audio && asset != null) ||
                    (clip.type == ClipType.Video && asset?.hasAudio == true)
            }
            if (clips.isEmpty()) return null
            return track(layer, "Audio", clips, enabled = !layer.muted, transitions = false)
        }

        private fun track(
            layer: Layer,
            kind: String,
            clips: List<Clip>,
            enabled: Boolean,
            transitions: Boolean,
        ): JsonObject {
            val children = mutableListOf<JsonElement>()
            var atUs = 0L
            var previous: Clip? = null
            for (clip in clips.sortedBy { it.startUs }) {
                if (clip.startUs > atUs) {
                    children += gap(clip.startUs - atUs)
                } else if (transitions && previous != null && clip.startUs == atUs) {
                    transitionAt(layer, atUs)?.let { children += it }
                }
                children += clip(clip, sound = kind == "Audio")
                atUs = maxOf(atUs, clip.endUs)
                previous = clip
            }
            return buildJsonObject {
                schema("Track.1")
                // Named as Clipra shows it: L1 at the bottom.
                item("L${project.layers.indexOf(layer) + 1}", enabled)
                put("source_range", JsonNull)
                put("kind", kind)
                put(
                    "metadata",
                    clipra {
                        put("layer", layer.id)
                        put("locked", layer.locked)
                    },
                )
                put("children", JsonArray(children))
            }
        }

        private fun gap(durationUs: Long) = buildJsonObject {
            schema("Gap.1")
            item("")
            put("metadata", JsonObject(emptyMap()))
            put("source_range", range(0, durationUs))
        }

        private fun transitionAt(layer: Layer, cutUs: Long): JsonObject? {
            val transition = project.transitions.firstOrNull {
                it.layerId == layer.id && it.cutTimeUs == cutUs
            } ?: return null
            val half = transition.durationUs / 2
            return buildJsonObject {
                schema("Transition.1")
                put("name", transition.type)
                put(
                    "transition_type",
                    if (transition.type in DISSOLVES) "SMPTE_Dissolve" else "Custom_Transition",
                )
                put("in_offset", time(half))
                put("out_offset", time(transition.durationUs - half))
                put("metadata", clipra { put("type", transition.type) })
            }
        }

        private fun clip(clip: Clip, sound: Boolean): JsonObject {
            val asset = clip.source?.let { project.asset(it.mediaId) }
            val name = when {
                clip.type == ClipType.Adjustment -> "Adjustment"
                clip.text != null -> clip.text.text
                clip.caption != null -> clip.caption.text
                asset != null -> asset.displayName.ifEmpty { asset.uri.substringAfterLast('/') }
                else -> clip.type.name
            }
            val reference = if (asset != null) {
                buildJsonObject {
                    schema("ExternalReference.1")
                    put("name", asset.displayName)
                    put("metadata", JsonObject(emptyMap()))
                    put("target_url", url(asset.uri))
                    put(
                        "available_range",
                        if (asset.kind != MediaKind.Photo && asset.durationUs > 0) {
                            range(0, asset.durationUs)
                        } else {
                            JsonNull
                        },
                    )
                }
            } else {
                buildJsonObject {
                    schema("MissingReference.1")
                    put("name", name)
                    put("metadata", JsonObject(emptyMap()))
                    put("available_range", JsonNull)
                }
            }
            val speed = clip.speed
            val warp = if (speed.rate != 1.0 || speed.reverse) {
                listOf(
                    buildJsonObject {
                        schema("LinearTimeWarp.1")
                        put("name", "")
                        put("metadata", JsonObject(emptyMap()))
                        put("effect_name", "LinearTimeWarp")
                        put("time_scalar", if (speed.reverse) -speed.rate else speed.rate)
                    },
                )
            } else {
                emptyList()
            }
            return buildJsonObject {
                schema("Clip.1")
                item(name, enabled = !sound || clip.audio.volume > 0f, effects = warp)
                put("source_range", range(clip.source?.inPointUs ?: 0, clip.durationUs))
                put("media_reference", reference)
                put(
                    "metadata",
                    clipra {
                        put("id", clip.id)
                        put("type", clip.type.name)
                        clip.text?.let { put("text", it.text) }
                        clip.caption?.let { put("text", it.text) }
                        speed.ramp?.let { put("speed_ramp", it) }
                    },
                )
            }
        }

        /** A content URI as it is; a path in the app's storage as a file URL. */
        private fun url(uri: String): String = if (uri.contains("://")) {
            uri
        } else {
            "file://" + uri.split('/').joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
        }
    }

    private fun JsonObjectBuilder.schema(name: String) {
        put("OTIO_SCHEMA", name)
    }

    /** What every item in a timeline has: its name, whether it plays, effects and markers. */
    private fun JsonObjectBuilder.item(
        name: String,
        enabled: Boolean = true,
        effects: List<JsonElement> = emptyList(),
    ) {
        put("name", name)
        put("enabled", enabled)
        put("effects", JsonArray(effects))
        put("markers", JsonArray(emptyList()))
    }

    private fun clipra(values: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("clipra", buildJsonObject(values))
    }

    /** Transitions other editors know as a dissolve. */
    private val DISSOLVES = setOf("dissolve")

    private val json = Json { prettyPrint = true }
}
