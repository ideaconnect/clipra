package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Project

// Layer commands (#12). Layers are numbered from the bottom: L1 is `layers[0]`, and higher
// layers cover lower ones. Layers only have hide (picture), mute (sound) and lock (no edits);
// every other setting belongs to clips.

/** Adds an empty layer above the top one. */
data class AddLayer(val layerId: String) : EditCommand {
    override fun apply(project: Project): Project = project.copy(
        layers = project.layers + Layer(layerId),
    )
}

/** Moves a layer to [toIndex] (0 = L1, the bottom); the others keep their order. */
data class MoveLayer(val layerId: String, val toIndex: Int) : EditCommand {
    override fun apply(project: Project): Project {
        val from = project.layerIndexOf(layerId)
        if (from < 0) throw EditRejected(EditRejected.Reason.LayerNotFound)
        if (toIndex !in project.layers.indices) throw EditRejected(EditRejected.Reason.OutOfRange)
        val layers = project.layers.toMutableList()
        layers.add(toIndex, layers.removeAt(from))
        return project.copy(layers = layers)
    }
}

/** Removes an empty layer; a layer with clips must be emptied first. The last layer stays. */
data class DeleteLayer(val layerId: String) : EditCommand {
    override fun apply(project: Project): Project {
        val layer = project.layer(layerId).requireUnlocked()
        if (layer.clips.isNotEmpty() || project.layers.size == 1) {
            throw EditRejected(EditRejected.Reason.OutOfRange)
        }
        return project.copy(layers = project.layers - layer)
    }
}

/** Hides or shows a layer's picture; its sound still plays. Works on locked layers too. */
data class SetLayerHidden(val layerId: String, val hidden: Boolean) : EditCommand {
    override fun apply(project: Project): Project {
        project.layer(layerId)
        return project.updateLayer(layerId) { it.copy(hidden = hidden) }
    }
}

/** Mutes or unmutes a layer's sound; its picture still shows. Works on locked layers too. */
data class SetLayerMuted(val layerId: String, val muted: Boolean) : EditCommand {
    override fun apply(project: Project): Project {
        project.layer(layerId)
        return project.updateLayer(layerId) { it.copy(muted = muted) }
    }
}

/** Locks a layer against edits, or unlocks it. */
data class SetLayerLocked(val layerId: String, val locked: Boolean) : EditCommand {
    override fun apply(project: Project): Project {
        project.layer(layerId)
        return project.updateLayer(layerId) { it.copy(locked = locked) }
    }
}

/** Puts a new clip on a layer, or on a new layer above the top one. */
data class AddClip(val clip: Clip, val target: LayerTarget) : EditCommand {
    override fun apply(project: Project): Project {
        val (withLayer, layerId) = project.resolveTarget(target)
        return withLayer.updateLayer(layerId) { it.withClip(clip) }
    }
}

/**
 * Moves a clip in time and, optionally, to another layer: a higher layer makes it cover what's
 * below. Dropping it above the top layer ([LayerTarget.NewOnTop]) creates a layer. Linked clips
 * move by the same time.
 */
data class MoveClip(val clipId: String, val startUs: Long, val target: LayerTarget? = null) :
    EditCommand {
    override fun apply(project: Project): Project {
        if (startUs < 0) throw EditRejected(EditRejected.Reason.OutOfRange)
        val (from, clip) = project.clipWithLayer(clipId)
        from.requireUnlocked()
        val (withLayer, toLayerId) = project.resolveTarget(target ?: LayerTarget.Existing(from.id))
        val moved = clip.copy(startUs = startUs)
        val removed = withLayer.updateLayer(from.id) { layer ->
            layer.copy(clips = layer.clips.filter { it.id != clipId })
        }
        // Linked partners (a caption, detached sound) keep their layers and follow in time.
        return removed.updateLayer(toLayerId) { it.withClip(moved) }
            .shiftClips(project.linkedPartners(clipId), startUs - clip.startUs)
    }
}
