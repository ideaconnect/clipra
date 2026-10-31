package tech.idct.clipra.feature.editor.tablet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.feature.editor.R

/**
 * The Audio library of the tablet editor (#70, TabletAudio board): the project's sounds, top
 * layer first (voiceovers, music and the sound of videos), Import, and Record voiceover. A tap
 * selects the sound, and the Audio inspector beside shows it.
 */
@Composable
internal fun AudioLibrary(
    project: Project,
    selectedClipId: String?,
    onSelect: (String) -> Unit,
    onImport: () -> Unit,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val sounds = project.layers.asReversed().flatMap { layer ->
        layer.clips.sortedBy { it.startUs }.mapNotNull { clip ->
            val asset = clip.source?.let { project.asset(it.mediaId) } ?: return@mapNotNull null
            val sounds = clip.type == ClipType.Audio ||
                (clip.type == ClipType.Video && asset.hasAudio)
            if (sounds) clip to asset else null
        }
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("audio-library"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.editor_tool_audio),
                style = type.bodyStrong,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Surface(
                onClick = onImport,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .height(36.dp)
                    .semantics { role = Role.Button }
                    .testTag("import-sound"),
                shape = RoundedCornerShape(10.dp),
                color = colors.surfaceRaised,
                contentColor = colors.textPrimary,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ClipraIcon(icon = ClipraIcons.Add, contentDescription = null, size = 16.dp)
                    Text(text = stringResource(R.string.editor_tablet_import), style = type.chip)
                }
            }
        }
        if (sounds.isEmpty()) {
            Text(
                text = stringResource(R.string.editor_tablet_no_sounds),
                style = type.caption,
                color = colors.textSecondary,
            )
        }
        sounds.forEach { (clip, asset) ->
            SoundRow(clip, asset, selected = clip.id == selectedClipId, onClick = {
                onSelect(clip.id)
            })
        }
        Surface(
            onClick = onRecord,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics { role = Role.Button }
                .testTag("record-voiceover"),
            shape = ClipraTheme.shapes.button,
            color = colors.danger.copy(alpha = RECORD_FILL),
            contentColor = colors.textPrimary,
            border = BorderStroke(1.dp, colors.danger.copy(alpha = RECORD_BORDER)),
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(colors.danger),
                )
                Text(
                    text = stringResource(R.string.editor_add_voiceover),
                    style = type.button,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        Text(
            text = stringResource(R.string.editor_tablet_record_hint),
            style = type.caption,
            color = colors.textSecondary,
        )
    }
}

/** One sound: what it is, its length, and "AI clean" when its voice is cleaned up. */
@Composable
private fun SoundRow(clip: Clip, asset: MediaAsset, selected: Boolean, onClick: () -> Unit) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val voice = clip.type == ClipType.Audio && asset.recorded
    val kind = stringResource(
        when {
            voice -> R.string.editor_tablet_sound_voice
            clip.type == ClipType.Audio -> R.string.editor_tablet_sound_music
            else -> R.string.editor_tablet_sound_clip
        },
    )
    val name = if (clip.type == ClipType.Video) {
        stringResource(R.string.editor_tablet_sound_of, asset.displayName)
    } else {
        asset.displayName
    }
    val details = listOfNotNull(
        kind,
        formatLength(clip.durationUs),
        stringResource(R.string.editor_tablet_sound_recorded).takeIf { voice },
    ).joinToString(" · ")
    val tint: Color = when {
        voice -> colors.clip.voice
        clip.type == ClipType.Audio -> colors.clip.music
        else -> colors.textSecondary
    }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                this.selected = selected
            }
            .testTag("sound:${clip.id}"),
        shape = ClipraTheme.shapes.button,
        color = if (selected) colors.accentSelectedBackground else colors.surface,
        contentColor = colors.textPrimary,
        border = if (selected) BorderStroke(1.5.dp, colors.accent) else null,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = ICON_FILL)),
                contentAlignment = Alignment.Center,
            ) {
                ClipraIcon(
                    icon = when {
                        voice -> ClipraIcons.Microphone
                        clip.type == ClipType.Audio -> ClipraIcons.Music
                        else -> ClipraIcons.Video
                    },
                    contentDescription = null,
                    tint = tint,
                    size = 18.dp,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = type.bodyStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = details, style = type.caption, color = colors.textSecondary)
            }
            if (clip.audio.voiceCleanup != null) {
                Text(
                    text = stringResource(R.string.editor_tablet_ai_clean),
                    style = type.toolLabel,
                    color = colors.onDeviceText,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.onDeviceBackground)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

private const val RECORD_FILL = 0.16f
private const val RECORD_BORDER = 0.4f
private const val ICON_FILL = 0.18f
