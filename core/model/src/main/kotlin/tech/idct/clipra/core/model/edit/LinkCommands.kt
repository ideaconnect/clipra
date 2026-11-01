package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.LinkGroup
import tech.idct.clipra.core.model.Project

// The Linked tool (#184): clips linked move and get cut together; these make and break links.

/**
 * Links clip [clipId] with [otherId], and so with the clips either is linked with already: they
 * move and get cut together from now on. One undo step.
 */
class LinkClips(
    private val clipId: String,
    private val otherId: String,
    private val newId: () -> String = ::randomId,
) : EditCommand {
    override fun apply(project: Project): Project {
        project.clipWithLayer(clipId)
        project.clipWithLayer(otherId)
        if (clipId == otherId) throw EditRejected(EditRejected.Reason.OutOfRange)
        val groups = project.links.filter { clipId in it.clipIds || otherId in it.clipIds }
        val ids = (groups.flatMap { it.clipIds } + clipId + otherId).distinct()
        val group = LinkGroup(groups.firstOrNull()?.id ?: newId(), ids)
        return project.copy(links = project.links.filterNot { it in groups } + group)
    }
}

/** Takes [clipId] out of its links: it moves and gets cut on its own. One undo step. */
data class UnlinkClip(val clipId: String) : EditCommand {
    override fun apply(project: Project): Project {
        project.clipWithLayer(clipId)
        return project.copy(
            links = project.links.map { it.copy(clipIds = it.clipIds - clipId) }
                .filter { it.clipIds.size > 1 },
        )
    }
}
