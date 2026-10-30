package tech.idct.clipra.feature.captions

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ChipStyle
import tech.idct.clipra.core.designsystem.component.ClipraChip
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.Segment
import tech.idct.clipra.core.designsystem.component.SegmentedControl
import tech.idct.clipra.core.designsystem.component.SegmentedStyle
import tech.idct.clipra.core.designsystem.component.TextAction
import tech.idct.clipra.core.model.CaptionPosition
import tech.idct.clipra.core.model.CaptionStyle
import tech.idct.clipra.core.model.CaptionStyles
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.models.ModelCatalog
import java.util.Locale
import kotlin.math.roundToInt

/** What the Captions screen can do. */
class CaptionsEditActions(
    val onDone: () -> Unit,
    val onStyle: (CaptionStyle) -> Unit,
    val onPosition: (CaptionPosition) -> Unit,
    val onScale: (Float) -> Unit,
    val onScaleDone: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onEditByText: () -> Unit,
)

/**
 * The CaptionsEdit board (#45): the preview, the five styles, position and size for every
 * caption, and the captions in time order with the words Whisper was unsure of underlined. Tap
 * a caption to see it. Editing words, .srt and translation come with #46 and #47.
 */
@Composable
fun CaptionsEditScreen(
    state: CaptionsEditUiState,
    scale: Float?,
    actions: CaptionsEditActions,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit = {},
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val captions = state.captions
    val look = state.look
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:CaptionsEdit"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.captions_title_captions_edit),
            subtitle = subtitle(state),
            navigation = HeaderNavigation.Back,
            onNavigate = actions.onDone,
            actions = {
                PrimaryButton(
                    text = stringResource(R.string.captions_done),
                    onClick = actions.onDone,
                    compact = true,
                    modifier = Modifier.testTag("captions-done"),
                )
            },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(colors.timelineBackground)
                .clipToBounds(),
        ) {
            preview()
            Text(
                text = clock(state.playheadUs),
                style = type.metaStrong,
                color = colors.textPrimary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (look != null) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    STYLES.forEach { (style, label) ->
                        ClipraChip(
                            label = stringResource(label),
                            selected = look.style == style,
                            onClick = { actions.onStyle(style) },
                            selectedStyle = ChipStyle.Outline,
                            modifier = Modifier.testTag("style:${style.name}"),
                        )
                    }
                }
                SegmentedControl(
                    segments = POSITIONS.map { Segment(stringResource(it.second)) },
                    selectedIndex = POSITIONS.indexOfFirst { it.first == look.position },
                    onSelect = { actions.onPosition(POSITIONS[it].first) },
                    style = SegmentedStyle.Tabs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("captions-position"),
                )
                val shown = scale ?: look.scale
                val points = (CaptionStyles.look(look.style).basePt * shown).roundToInt()
                LabelledSlider(
                    label = stringResource(R.string.captions_size),
                    value = shown,
                    onValueChange = actions.onScale,
                    onValueChangeFinished = actions.onScaleDone,
                    valueText = stringResource(R.string.captions_points, points),
                    valueRange = 0.5f..2f,
                    labelWidth = 64.dp,
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("captions-size"),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.captions_count, captions.size),
                    style = type.sectionLabel,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.width(4.dp))
                TextAction(
                    text = stringResource(R.string.captions_title_transcript),
                    onClick = actions.onEditByText,
                )
                Spacer(Modifier.weight(1f))
                if (state.wordsToCheck > 0) {
                    Text(
                        text = stringResource(R.string.captions_to_check, state.wordsToCheck),
                        style = type.caption,
                        color = colors.textSecondary,
                    )
                }
            }
            captions.forEach { clip ->
                val active = state.playheadUs in clip.startUs until clip.endUs
                CaptionRow(clip, active) { actions.onSeek(clip.startUs) }
            }
        }
    }
}

/** A caption: its time, and its words with the ones to check underlined. */
@Composable
private fun CaptionRow(clip: Clip, active: Boolean, onClick: () -> Unit) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val words = clip.caption?.words.orEmpty()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("caption:${clip.id}"),
        shape = ClipraTheme.shapes.button,
        color = if (active) colors.accentSelectedBackground else colors.surface,
        contentColor = colors.textPrimary,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${clock(
                    clip.startUs,
                    tenths = true,
                )} → ${clock(clip.endUs, tenths = true)}",
                style = type.meta,
                color = if (active) colors.accent else colors.textSecondary,
            )
            Text(
                text = buildAnnotatedString {
                    words.forEachIndexed { i, word ->
                        if (i > 0) append(" ")
                        if (word.confidence < CaptionsEditUiState.LOW_CONFIDENCE) {
                            withStyle(
                                SpanStyle(
                                    color = colors.lowConfidenceUnderline,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ) { append(word.text) }
                        } else {
                            append(word.text)
                        }
                    }
                },
                style = type.body,
            )
        }
    }
}

/** "Whisper Base · English (detected)". */
@Composable
private fun subtitle(state: CaptionsEditUiState): String? {
    val transcript = state.transcript ?: return null
    val model = ModelCatalog.byId(transcript.model)?.name
    val locale = LocalConfiguration.current.locales[0]
    val language = Locale.forLanguageTag(transcript.language).getDisplayLanguage(locale)
        .replaceFirstChar { it.titlecase(locale) }
    val spoken = if (transcript.detected) {
        stringResource(R.string.captions_detected, language)
    } else {
        language
    }
    return listOfNotNull(model?.let { stringResource(R.string.captions_whisper, it) }, spoken)
        .joinToString(" · ")
}

/** 00:08.30 (minutes, seconds, hundredths), or 00:08.3 with [tenths]. */
private fun clock(us: Long, tenths: Boolean = false): String {
    val minutes = us / 60_000_000
    val seconds = us / 1_000_000 % 60
    return if (tenths) {
        String.format(Locale.ROOT, "%02d:%02d.%d", minutes, seconds, us / 100_000 % 10)
    } else {
        String.format(Locale.ROOT, "%02d:%02d.%02d", minutes, seconds, us / 10_000 % 100)
    }
}

private val STYLES = listOf(
    CaptionStyle.Clean to R.string.captions_style_clean,
    CaptionStyle.BoldPop to R.string.captions_style_bold_pop,
    CaptionStyle.Boxed to R.string.captions_style_boxed,
    CaptionStyle.Karaoke to R.string.captions_style_karaoke,
    CaptionStyle.Minimal to R.string.captions_style_minimal,
)

private val POSITIONS = listOf(
    CaptionPosition.Top to R.string.captions_top,
    CaptionPosition.Middle to R.string.captions_middle,
    CaptionPosition.Bottom to R.string.captions_bottom,
)
