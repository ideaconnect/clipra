package tech.idct.clipra.feature.editor.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ChipStyle
import tech.idct.clipra.core.designsystem.component.ClipraChip
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.IconAction
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.Segment
import tech.idct.clipra.core.designsystem.component.SegmentedControl
import tech.idct.clipra.core.designsystem.component.SegmentedStyle
import tech.idct.clipra.core.designsystem.component.SwitchRow
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.Animation
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.BlendMode
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.ColorGrade
import tech.idct.clipra.core.model.Fit
import tech.idct.clipra.core.model.Keyframes
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.LoudnessTargets
import tech.idct.clipra.core.model.Luts
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Speed
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.edit.PasteColor
import tech.idct.clipra.core.model.edit.takesFilters
import tech.idct.clipra.core.model.keyframeTimes
import tech.idct.clipra.core.model.opacityAt
import tech.idct.clipra.core.model.transformAt
import tech.idct.clipra.feature.editor.EditorCallbacks
import tech.idct.clipra.feature.editor.EditorTool
import tech.idct.clipra.feature.editor.R
import tech.idct.clipra.feature.editor.animate.AnimateControls
import tech.idct.clipra.feature.editor.layers.NumberField
import tech.idct.clipra.feature.editor.layers.blendLabel
import tech.idct.clipra.feature.editor.layers.displayName
import tech.idct.clipra.feature.editor.layers.normalizeDegrees
import tech.idct.clipra.feature.editor.speed.SpeedControls
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/** The inspector's tabs (TabletEditor board and its tab states). */
enum class InspectorTab { Clip, Color, Audio, Speed, Animate }

/** What the inspector changes on the selected clip; each change is one undo step. */
class InspectorActions(
    val onTransform: (clipId: String, Transform) -> Unit,
    val onBlend: (clipId: String, BlendMode, opacity: Float) -> Unit,
    val onColor: (clipId: String, ColorGrade?) -> Unit,
    val onAutoColor: (clipId: String) -> Unit,
    val onPasteColor: (clipId: String) -> Unit,
    val onAudio: (clipId: String, AudioSettings) -> Unit,
    val onNormalize: (clipId: String, on: Boolean) -> Unit,
    val onSpeed: (clipId: String, Speed) -> Unit,
    val onAnimation: (clipId: String, Animation) -> Unit,
    /** An effect's name, as the Effects screens call it. */
    val effectName: @Composable (type: String) -> String = { it },
)

/**
 * The tablet inspector (#69): the selected clip's name, layer and length, then its tabs. Tabs
 * the clip has nothing for (Color on a sound, Speed on a still) are left out.
 */
@Composable
internal fun Inspector(
    project: Project,
    selected: Pair<Layer, Clip>?,
    tab: InspectorTab,
    onTab: (InspectorTab) -> Unit,
    callbacks: EditorCallbacks,
    actions: InspectorActions,
    modifier: Modifier = Modifier,
    playheadUs: Long = 0,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(
        modifier = modifier
            .padding(start = 16.dp, end = 16.dp, top = 10.dp)
            .testTag("inspector"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (selected == null) {
            Text(
                text = stringResource(R.string.editor_tablet_inspector_empty),
                style = type.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }
        val (layer, clip) = selected
        val number = project.layers.indexOfFirst { it.id == layer.id } + 1
        val locale = LocalConfiguration.current.locales[0]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.textPrimary),
            )
            Text(
                text = displayName(project, clip),
                style = type.bodyStrong,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            Text(
                text = stringResource(
                    R.string.editor_tablet_inspector_meta,
                    number,
                    String.format(locale, "%.1f", clip.durationUs / 1_000_000.0),
                ),
                style = type.meta.copy(fontSize = type.chip.fontSize),
                color = colors.textTertiary,
            )
        }
        val tabs = tabsFor(project, clip)
        val shown = tab.takeIf { it in tabs } ?: tabs.first()
        SegmentedControl(
            segments = tabs.map { Segment(stringResource(it.label)) },
            selectedIndex = tabs.indexOf(shown),
            onSelect = { onTab(tabs[it]) },
            style = SegmentedStyle.Tabs,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("inspector-tabs"),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
                .testTag("inspector:${shown.name.lowercase()}"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (shown) {
                InspectorTab.Clip -> ClipTab(project, clip, playheadUs, callbacks, actions)

                InspectorTab.Color -> ColorTab(project, clip, actions)

                InspectorTab.Audio -> AudioTab(clip, callbacks, actions)

                InspectorTab.Speed -> SpeedControls(clip.speed, { actions.onSpeed(clip.id, it) })

                InspectorTab.Animate -> AnimateControls(
                    clip = clip,
                    onAnimation = { actions.onAnimation(clip.id, it) },
                    wrap = true,
                    inset = 0.dp,
                )
            }
        }
    }
}

private val InspectorTab.label: Int
    get() = when (this) {
        InspectorTab.Clip -> R.string.editor_tablet_tab_clip
        InspectorTab.Color -> R.string.editor_tablet_tab_color
        InspectorTab.Audio -> R.string.editor_tablet_tab_audio
        InspectorTab.Speed -> R.string.editor_tablet_tab_speed
        InspectorTab.Animate -> R.string.editor_tablet_tab_animate
    }

/** The tabs [clip] has something for. */
internal fun tabsFor(project: Project, clip: Clip): List<InspectorTab> = buildList {
    val sound = clip.type == ClipType.Audio || (
        clip.type == ClipType.Video &&
            clip.source?.let { project.asset(it.mediaId) }?.hasAudio == true
        )
    if (clip.type != ClipType.Audio) add(InspectorTab.Clip)
    if (clip.takesFilters()) add(InspectorTab.Color)
    if (sound) add(InspectorTab.Audio)
    if ((clip.type == ClipType.Video || clip.type == ClipType.Audio) && clip.source != null) {
        add(InspectorTab.Speed)
    }
    if (clip.type != ClipType.Audio && clip.type != ClipType.Caption) add(InspectorTab.Animate)
    if (isEmpty()) add(InspectorTab.Clip)
}

/** Transform, opacity, blend, crop, flip and fit, and the clip's effects. */
@Composable
private fun ClipTab(
    project: Project,
    clip: Clip,
    playheadUs: Long,
    callbacks: EditorCallbacks,
    actions: InspectorActions,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val time = (playheadUs - clip.startUs).coerceIn(0, clip.durationUs)
    val transform = clip.transformAt(time)
    val frame = project.format.outputSize
    val keyed = clip.keyframeTimes.any { abs(it - time) <= Keyframes.SNAP_US }
    val set = { t: Transform -> actions.onTransform(clip.id, t) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.editor_layers_tab_transform),
            style = type.sectionLabel,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        IconAction(
            icon = ClipraIcons.Keyframe,
            contentDescription = stringResource(
                if (keyed) R.string.editor_keyframe_delete else R.string.editor_keyframe,
            ),
            onClick = callbacks.onKeyframe,
            tint = if (keyed) colors.accent else colors.textSecondary,
            modifier = Modifier.testTag("inspector-keyframe"),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumberField(
            label = stringResource(R.string.editor_tablet_x),
            value = (transform.x * frame.width).roundToInt(),
            unit = stringResource(R.string.editor_transform_px),
            onValue = { set(transform.copy(x = it / frame.width)) },
            labelWidth = FIELD_LABEL,
            modifier = Modifier
                .weight(1f)
                .testTag("transform-x"),
        )
        NumberField(
            label = stringResource(R.string.editor_tablet_y),
            value = (transform.y * frame.height).roundToInt(),
            unit = stringResource(R.string.editor_transform_px),
            onValue = { set(transform.copy(y = it / frame.height)) },
            labelWidth = FIELD_LABEL,
            modifier = Modifier
                .weight(1f)
                .testTag("transform-y"),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumberField(
            label = stringResource(R.string.editor_tablet_scale),
            value = (transform.scale * 100).roundToInt(),
            unit = "%",
            onValue = { set(transform.copy(scale = (it / 100).coerceIn(0.01f, 20f))) },
            labelWidth = FIELD_LABEL,
            modifier = Modifier
                .weight(1f)
                .testTag("transform-scale"),
        )
        NumberField(
            label = stringResource(R.string.editor_tablet_rotate),
            value = transform.rotation.roundToInt(),
            unit = "°",
            onValue = { set(transform.copy(rotation = normalizeDegrees(it))) },
            labelWidth = FIELD_LABEL,
            modifier = Modifier
                .weight(1f)
                .testTag("transform-rotation"),
        )
    }
    val committed = clip.opacityAt(time)
    var opacity by remember(committed) { mutableFloatStateOf(committed) }
    LabelledSlider(
        label = stringResource(R.string.editor_layers_opacity),
        value = opacity,
        onValueChange = { opacity = it },
        onValueChangeFinished = { actions.onBlend(clip.id, clip.blend, opacity) },
        valueText = stringResource(R.string.editor_layers_percent, (opacity * 100).roundToInt()),
        labelWidth = 72.dp,
        modifier = Modifier
            .height(44.dp)
            .testTag("opacity"),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BlendPicker(
            blend = clip.blend,
            onBlend = { actions.onBlend(clip.id, it, opacity) },
            modifier = Modifier.weight(1f),
        )
        SquareButton(
            icon = ClipraIcons.Crop,
            description = stringResource(R.string.editor_tool_crop),
            onClick = callbacks.onCrop,
            tag = "inspector-crop",
        )
        SquareButton(
            icon = ClipraIcons.Flip,
            description = stringResource(R.string.editor_transform_flip),
            onClick = { set(transform.copy(flipHorizontal = !transform.flipHorizontal)) },
            selected = transform.flipHorizontal,
            tag = "inspector-flip",
        )
        val fit = transform.fit == Fit.Fit
        SquareButton(
            icon = ClipraIcons.Reframe,
            description = stringResource(
                if (fit) R.string.editor_transform_fill else R.string.editor_transform_fit,
            ),
            onClick = {
                set(
                    transform.copy(
                        fit = if (fit) Fit.Fill else Fit.Fit,
                        x = 0f,
                        y = 0f,
                        scale = 1f,
                    ),
                )
            },
            selected = fit,
            tag = "inspector-fit",
        )
    }
    if (clip.takesFilters()) {
        Text(
            text = stringResource(R.string.editor_tablet_clip_effects),
            style = type.sectionLabel,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            clip.effects.filter { it.enabled }.forEach { effect ->
                ClipraChip(
                    label = actions.effectName(effect.type),
                    selected = true,
                    onClick = { callbacks.onTool(EditorTool.Effects) },
                    selectedStyle = ChipStyle.Accent,
                )
            }
            Surface(
                onClick = { callbacks.onTool(EditorTool.Effects) },
                modifier = Modifier
                    .size(44.dp)
                    .semantics { role = Role.Button }
                    .testTag("inspector-add-effect"),
                shape = RoundedCornerShape(22.dp),
                color = Color.Transparent,
                contentColor = colors.textSecondary,
            ) {
                Box(
                    modifier = Modifier.border(
                        1.dp,
                        colors.borderStrong,
                        RoundedCornerShape(22.dp),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    ClipraIcon(
                        icon = ClipraIcons.Add,
                        contentDescription = stringResource(R.string.editor_tablet_add_effect),
                        size = 18.dp,
                    )
                }
            }
        }
    }
}

/** "Blend · Normal ▾", a menu of the blend modes. */
@Composable
private fun BlendPicker(blend: BlendMode, onBlend: (BlendMode) -> Unit, modifier: Modifier) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            onClick = { open = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .semantics { role = Role.DropdownList }
                .testTag("inspector-blend"),
            shape = ClipraTheme.shapes.button,
            color = colors.surface,
            contentColor = colors.textPrimary,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.editor_tablet_blend),
                    style = type.caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = blendLabel(blend),
                    style = type.chip,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp),
                )
                ClipraIcon(icon = ClipraIcons.ChevronDown, contentDescription = null, size = 16.dp)
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = colors.surfaceRaised,
        ) {
            BlendMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = blendLabel(mode),
                            style = type.body,
                            color = if (mode == blend) colors.accent else colors.textPrimary,
                        )
                    },
                    onClick = {
                        open = false
                        onBlend(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun SquareButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    tag: String,
    selected: Boolean = false,
) {
    val colors = ClipraTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(width = 44.dp, height = 52.dp)
            .semantics { role = Role.Button }
            .testTag(tag),
        shape = ClipraTheme.shapes.button,
        color = if (selected) colors.accentSelectedBackground else colors.surface,
        contentColor = if (selected) colors.accent else colors.textPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ClipraIcon(icon = icon, contentDescription = description, size = 20.dp)
        }
    }
}

/** Auto and Reset, the four main sliders (-100 to +100), the LUT and paste to other clips. */
@Composable
private fun ColorTab(project: Project, clip: Clip, actions: InspectorActions) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val committed = clip.color ?: ColorGrade()
    var grade by remember(committed) { mutableStateOf(committed) }
    val commit = { g: ColorGrade -> actions.onColor(clip.id, g.takeIf { !it.isNeutral }) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.editor_tablet_grade),
            style = type.sectionLabel,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        SmallButton(
            text = stringResource(R.string.editor_tablet_auto),
            onClick = { actions.onAutoColor(clip.id) },
            accent = true,
            tag = "color-auto",
        )
        SmallButton(
            text = stringResource(R.string.editor_tablet_reset),
            onClick = { actions.onColor(clip.id, null) },
            tag = "color-reset",
        )
    }
    val sliders = listOf(
        Triple(R.string.editor_tablet_exposure, grade.exposure) { v: Float ->
            grade.copy(exposure = v)
        },
        Triple(R.string.editor_tablet_contrast, grade.contrast) { v: Float ->
            grade.copy(contrast = v)
        },
        Triple(R.string.editor_tablet_saturation, grade.saturation) { v: Float ->
            grade.copy(saturation = v)
        },
        Triple(R.string.editor_tablet_temperature, grade.temperature) { v: Float ->
            grade.copy(temperature = v)
        },
    )
    sliders.forEach { (label, value, with) ->
        LabelledSlider(
            label = stringResource(label),
            value = value,
            onValueChange = { grade = with(it.roundToInt().toFloat()) },
            onValueChangeFinished = { commit(grade) },
            valueText = signed(value.roundToInt()),
            valueRange = -100f..100f,
            labelWidth = 96.dp,
            modifier = Modifier
                .height(40.dp)
                .testTag("color-${stringTag(label)}"),
        )
    }
    Text(
        text = stringResource(R.string.editor_tablet_lut),
        style = type.sectionLabel,
        color = colors.textSecondary,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ClipraChip(
            label = stringResource(R.string.editor_tablet_lut_none),
            selected = grade.lut == null,
            onClick = { commit(grade.copy(lut = null)) },
        )
        Luts.builtInNames.forEach { name ->
            val id = Luts.builtInId(name)
            ClipraChip(
                label = stringResource(lutLabel(name)),
                selected = grade.lut == id,
                onClick = { commit(grade.copy(lut = id, lutStrength = 1f)) },
            )
        }
    }
    val others = PasteColor.targets(project, clip.id)
    if (others > 0 && clip.color != null) {
        Surface(
            onClick = { actions.onPasteColor(clip.id) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .semantics { role = Role.Button }
                .testTag("color-paste"),
            shape = ClipraTheme.shapes.button,
            color = colors.surface,
            contentColor = colors.textPrimary,
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClipraIcon(icon = ClipraIcons.Copy, contentDescription = null, size = 18.dp)
                Text(
                    text = pluralStringResource(
                        R.plurals.editor_tablet_paste_grade,
                        others,
                        others,
                    ),
                    style = type.chip,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** Volume, fades, normalize, then cleanup in the Audio screen and Detach. */
@Composable
private fun AudioTab(clip: Clip, callbacks: EditorCallbacks, actions: InspectorActions) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val audio = clip.audio
    val locale = LocalConfiguration.current.locales[0]
    var volume by remember(audio.volume) { mutableFloatStateOf(audio.volume) }
    LabelledSlider(
        label = stringResource(R.string.editor_tablet_volume),
        value = volume,
        onValueChange = { volume = it },
        onValueChangeFinished = { actions.onAudio(clip.id, audio.copy(volume = volume)) },
        valueText = decibels(volume, locale),
        valueRange = 0f..2f,
        labelWidth = 72.dp,
        modifier = Modifier
            .height(44.dp)
            .testTag("audio-volume"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumberField(
            label = stringResource(R.string.editor_tablet_fade_in),
            value = (audio.fadeInUs / 100_000).toInt(),
            shown = seconds(audio.fadeInUs, locale),
            unit = stringResource(R.string.editor_tablet_seconds),
            onValue = {
                val us = (it * 1_000_000).toLong().coerceIn(0, clip.durationUs)
                actions.onAudio(clip.id, audio.copy(fadeInUs = us))
            },
            labelWidth = 56.dp,
            modifier = Modifier
                .weight(1f)
                .testTag("audio-fade-in"),
        )
        NumberField(
            label = stringResource(R.string.editor_tablet_fade_out),
            value = (audio.fadeOutUs / 100_000).toInt(),
            shown = seconds(audio.fadeOutUs, locale),
            unit = stringResource(R.string.editor_tablet_seconds),
            onValue = {
                val us = (it * 1_000_000).toLong().coerceIn(0, clip.durationUs)
                actions.onAudio(clip.id, audio.copy(fadeOutUs = us))
            },
            labelWidth = 56.dp,
            modifier = Modifier
                .weight(1f)
                .testTag("audio-fade-out"),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ClipraTheme.shapes.button)
            .background(colors.surface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SwitchRow(
            title = stringResource(R.string.editor_tablet_normalize),
            subtitle = stringResource(R.string.editor_tablet_lufs, LoudnessTargets.SOCIAL),
            checked = audio.loudnessTarget != null,
            onCheckedChange = { actions.onNormalize(clip.id, it) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("audio-normalize"),
        )
    }
    Surface(
        onClick = { callbacks.onTool(EditorTool.Audio) },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics { role = Role.Button }
            .testTag("audio-cleanup"),
        shape = ClipraTheme.shapes.button,
        color = Color.Transparent,
        contentColor = colors.accent,
    ) {
        Box(
            modifier = Modifier.border(1.5.dp, colors.accent, ClipraTheme.shapes.button),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = stringResource(R.string.editor_tablet_cleanup), style = type.chip)
        }
    }
    if (clip.type == ClipType.Video) {
        Surface(
            onClick = callbacks.onExtract,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .semantics { role = Role.Button }
                .testTag("audio-detach"),
            shape = ClipraTheme.shapes.button,
            color = colors.surface,
            contentColor = colors.textPrimary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = stringResource(R.string.editor_tablet_detach_free), style = type.chip)
            }
        }
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit, tag: String, accent: Boolean = false) {
    val colors = ClipraTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(36.dp)
            .semantics { role = Role.Button }
            .testTag(tag),
        shape = RoundedCornerShape(10.dp),
        color = if (accent) colors.accentChipBackground else colors.surfaceRaised,
        contentColor = if (accent) colors.accent else colors.textPrimary,
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(text = text, style = ClipraTheme.typography.chip)
        }
    }
}

/** "+10", "0" or "-8": a grade value as the board shows it. */
private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

/** A volume (1 is as recorded) in dB, as the board shows it: "0.0 dB". */
private fun decibels(volume: Float, locale: Locale): String = if (volume <= 0.001f) {
    "-∞ dB"
} else {
    String.format(locale, "%.1f dB", 20 * log10(volume))
}

/** "0.5": seconds with one decimal, in the user's number format. */
private fun seconds(us: Long, locale: Locale): String =
    String.format(locale, "%.1f", us / 1_000_000.0)

private fun stringTag(label: Int): String = when (label) {
    R.string.editor_tablet_exposure -> "exposure"
    R.string.editor_tablet_contrast -> "contrast"
    R.string.editor_tablet_saturation -> "saturation"
    else -> "temperature"
}

private fun lutLabel(name: String): Int = when (name) {
    "teal_orange" -> R.string.editor_tablet_lut_teal_orange
    "warm_film" -> R.string.editor_tablet_lut_warm_film
    "cool_night" -> R.string.editor_tablet_lut_cool_night
    "silver_mono" -> R.string.editor_tablet_lut_silver_mono
    else -> R.string.editor_tablet_lut_faded
}

/** Labels of the transform fields fit beside their numbers in the inspector's columns. */
private val FIELD_LABEL = 44.dp
