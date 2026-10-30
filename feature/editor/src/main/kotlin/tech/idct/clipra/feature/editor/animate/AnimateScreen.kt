package tech.idct.clipra.feature.editor.animate

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ChipStyle
import tech.idct.clipra.core.designsystem.component.ClipraChip
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.model.Animation
import tech.idct.clipra.core.model.Animations
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.feature.editor.R
import tech.idct.clipra.feature.editor.layers.DoneButton
import tech.idct.clipra.feature.editor.layers.PREVIEW_HEIGHT
import tech.idct.clipra.feature.editor.layers.displayName
import tech.idct.clipra.feature.editor.layers.typeName
import tech.idct.clipra.feature.editor.timeline.formatTimecode

/** What the Animate board can change. */
class AnimateActions(val onDone: () -> Unit, val onAnimation: (Animation) -> Unit)

/**
 * The Animate board (#33, the tablet's Animate tab): how the clip comes in, goes out and moves
 * in a loop; text comes in and goes out letter by letter. Each pick is one edit, shown in the
 * preview.
 */
@Composable
fun AnimateScreen(
    project: Project,
    clip: Clip,
    actions: AnimateActions,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val animation = clip.animation ?: Animation()
    val text = clip.type == ClipType.Text || clip.type == ClipType.TextCard
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Animate"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.editor_animate_header, displayName(project, clip)),
            subtitle = stringResource(
                R.string.editor_layers_subtitle,
                typeName(clip.type),
                formatTimecode(clip.startUs),
                formatTimecode(clip.endUs),
            ),
            navigation = HeaderNavigation.Back,
            onNavigate = actions.onDone,
            actions = { DoneButton(actions.onDone) },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .clipToBounds()
                .background(colors.ad.background),
            contentAlignment = Alignment.Center,
        ) {
            val format = project.format
            Box(
                modifier = Modifier
                    .aspectRatio(
                        format.ratio.width.toFloat() / format.ratio.height,
                        matchHeightConstraintsFirst = true,
                    )
                    .testTag("preview"),
            ) {
                preview()
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ChoiceRow(
                title = stringResource(R.string.editor_animate_in),
                choices = if (text) Animations.textIn else Animations.clipIn,
                selected = animation.enter,
                onPick = { actions.onAnimation(animation.copy(enter = it)) },
                tag = "animate-in",
            )
            ChoiceRow(
                title = stringResource(R.string.editor_animate_out),
                choices = if (text) Animations.textOut else Animations.clipOut,
                selected = animation.exit,
                onPick = { actions.onAnimation(animation.copy(exit = it)) },
                tag = "animate-out",
            )
            if (!text) {
                ChoiceRow(
                    title = stringResource(R.string.editor_animate_loop),
                    choices = Animations.clipLoop,
                    selected = animation.loop,
                    onPick = { actions.onAnimation(animation.copy(loop = it)) },
                    tag = "animate-loop",
                )
            }
        }
    }
}

/** A title and None plus [choices] as chips, the picked one filled. */
@Composable
private fun ChoiceRow(
    title: String,
    choices: List<String>,
    selected: String?,
    onPick: (String?) -> Unit,
    tag: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = ClipraTheme.typography.sectionLabel,
            color = ClipraTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .testTag(tag),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (listOf<String?>(null) + choices).forEach { id ->
                ClipraChip(
                    label = stringResource(animationName(id)),
                    selected = id == selected,
                    onClick = { onPick(id) },
                    selectedStyle = ChipStyle.Light,
                    modifier = Modifier.testTag("$tag:${id ?: "none"}"),
                )
            }
        }
    }
}

/** The name of animation [id] (#33). */
internal fun animationName(id: String?): Int = when (id) {
    "fade" -> R.string.editor_animation_fade
    "zoom_in" -> R.string.editor_animation_zoom_in
    "slide_up" -> R.string.editor_animation_slide_up
    "spin" -> R.string.editor_animation_spin
    "zoom_out" -> R.string.editor_animation_zoom_out
    "slide_down" -> R.string.editor_animation_slide_down
    "ken_burns" -> R.string.editor_animation_ken_burns
    "shake" -> R.string.editor_animation_shake
    "pulse" -> R.string.editor_animation_pulse
    "typewriter" -> R.string.editor_animation_typewriter
    "fade_up" -> R.string.editor_animation_fade_up
    "pop" -> R.string.editor_animation_pop
    "slide" -> R.string.editor_animation_slide
    "bounce" -> R.string.editor_animation_bounce
    "wave" -> R.string.editor_animation_wave
    "glitch" -> R.string.editor_animation_glitch
    else -> R.string.editor_animation_none
}
