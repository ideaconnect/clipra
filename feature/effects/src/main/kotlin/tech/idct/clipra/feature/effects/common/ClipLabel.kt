package tech.idct.clipra.feature.effects.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.feature.effects.R

/**
 * What a screen's header calls [clip]: its media's file name, or "Adjustment clip" (#23) for one
 * with no media of its own.
 */
@Composable
internal fun clipLabel(project: Project, clip: Clip): String =
    if (clip.type == ClipType.Adjustment) {
        stringResource(R.string.effects_adjustment_clip)
    } else {
        clip.source?.let { project.asset(it.mediaId) }?.displayName.orEmpty()
    }
