package tech.idct.clipra.feature.audio

import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Project

/** A sound clip not recorded in Clipra: music or an effect, which ducks under speech. */
internal fun Project.isMusic(clip: Clip): Boolean = clip.type == ClipType.Audio &&
    clip.source?.let { asset(it.mediaId) }?.recorded != true
