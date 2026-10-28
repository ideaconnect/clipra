package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Project

/**
 * A change to a project. Projects are immutable, so a command returns the edited project and the
 * undo history keeps the one before it.
 */
fun interface EditCommand {
    /** The edited project; throws [EditRejected] when the edit isn't allowed. */
    fun apply(project: Project): Project
}

/** Why an edit was refused; the editor tells the user instead of changing anything. */
class EditRejected(val reason: Reason, message: String = reason.name) : Exception(message) {
    enum class Reason {
        /** The clip or one of the layers involved is locked. */
        LayerLocked,
        LayerNotFound,
        ClipNotFound,

        /** The clip would overlap another clip on its layer. */
        Overlap,

        /** A time or index outside what the clip or project allows. */
        OutOfRange,
    }
}

/** Where a clip goes: an existing layer, or a new layer above the top one. */
sealed interface LayerTarget {
    data class Existing(val layerId: String) : LayerTarget

    data class NewOnTop(val layerId: String) : LayerTarget
}

internal fun Project.layer(layerId: String): Layer =
    layers.firstOrNull { it.id == layerId } ?: throw EditRejected(EditRejected.Reason.LayerNotFound)

internal fun Project.clipWithLayer(clipId: String): Pair<Layer, Clip> =
    findClip(clipId) ?: throw EditRejected(EditRejected.Reason.ClipNotFound)

internal fun Layer.requireUnlocked(): Layer {
    if (locked) throw EditRejected(EditRejected.Reason.LayerLocked)
    return this
}

internal fun Project.updateLayer(layerId: String, change: (Layer) -> Layer): Project =
    copy(layers = layers.map { if (it.id == layerId) change(it) else it })

/** Adds an empty layer above the top one when [target] asks for it; returns its id. */
internal fun Project.resolveTarget(target: LayerTarget): Pair<Project, String> = when (target) {
    is LayerTarget.Existing -> {
        layer(target.layerId).requireUnlocked()
        this to target.layerId
    }

    is LayerTarget.NewOnTop -> copy(layers = layers + Layer(target.layerId)) to target.layerId
}

/** Clips on a layer are in time order and never overlap. */
internal fun Layer.withClip(clip: Clip): Layer {
    if (clips.any { it.id != clip.id && it.overlaps(clip) }) {
        throw EditRejected(EditRejected.Reason.Overlap)
    }
    return copy(clips = (clips.filter { it.id != clip.id } + clip).sortedBy { it.startUs })
}

internal fun Clip.overlaps(other: Clip): Boolean = startUs < other.endUs && other.startUs < endUs
