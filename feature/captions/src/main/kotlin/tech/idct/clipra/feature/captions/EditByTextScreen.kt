package tech.idct.clipra.feature.captions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ChoiceCard
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.TextAction
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.Token
import tech.idct.clipra.core.model.TokenKind
import tech.idct.clipra.core.model.TranscriptSource
import java.util.Locale

/** What Edit by text can do. */
class EditByTextActions(
    val onBack: () -> Unit,
    val onDone: () -> Unit,
    val onFillers: (Boolean) -> Unit,
    val onPauses: (Boolean) -> Unit,
    val onThreshold: (Long) -> Unit,
    val onToken: (Token) -> Unit,
    val onRestoreAll: () -> Unit,
    val onPlay: () -> Unit,
    val onAutoCaptions: () -> Unit,
)

/**
 * The Transcript board (#48): the new length and what the cuts save, Cut filler words and Cut
 * pauses with the pause threshold, and each transcribed source's words, fillers and pauses.
 * Tap one to cut or restore it. Done makes the cuts, rippling through every layer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditByTextScreen(
    state: EditByTextUiState,
    playing: Boolean,
    actions: EditByTextActions,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit = {},
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Transcript"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.captions_title_transcript),
            subtitle = stringResource(R.string.captions_ripple),
            navigation = HeaderNavigation.Back,
            onNavigate = actions.onBack,
            actions = {
                PrimaryButton(
                    text = stringResource(R.string.captions_done),
                    onClick = actions.onDone,
                    compact = true,
                    modifier = Modifier.testTag("text-done"),
                )
            },
        )
        if (state.loaded && state.sources.isEmpty()) {
            NoTranscript(actions.onAutoCaptions)
            return
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(colors.timelineBackground),
        ) {
            Box(
                modifier = Modifier
                    .width(160.dp)
                    .fillMaxHeight()
                    .clipToBounds(),
            ) { preview() }
            LengthCard(state, playing, actions.onPlay)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val choices = state.choices
            val summary = state.summary
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceCard(
                    title = stringResource(R.string.captions_cut_fillers),
                    subtitle = fillerNote(state),
                    selected = choices.fillers,
                    onClick = { actions.onFillers(!choices.fillers) },
                    compact = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cut-fillers"),
                )
                ChoiceCard(
                    title = stringResource(R.string.captions_cut_pauses),
                    subtitle = stringResource(
                        R.string.captions_long_pauses,
                        summary.longPauses,
                        seconds(choices.thresholdUs),
                    ),
                    selected = choices.pauses,
                    onClick = { actions.onPauses(!choices.pauses) },
                    compact = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cut-pauses"),
                )
            }
            LabelledSlider(
                label = stringResource(R.string.captions_pauses_longer),
                value = choices.thresholdUs / 1e6f,
                onValueChange = { actions.onThreshold((it * 10).toInt() * 100_000L) },
                valueText = stringResource(R.string.captions_seconds, seconds(choices.thresholdUs)),
                valueRange = 0.3f..2f,
                labelWidth = 150.dp,
                modifier = Modifier
                    .height(44.dp)
                    .testTag("pause-threshold"),
            )
            if (summary.cuts > 0) {
                TextAction(
                    text = stringResource(R.string.captions_restore_all),
                    onClick = actions.onRestoreAll,
                    modifier = Modifier.testTag("restore-all"),
                )
            }
            state.sources.forEach { source ->
                SourceText(source, state, actions.onToken)
            }
        }
        Legend()
    }
}

@Composable
private fun LengthCard(state: EditByTextUiState, playing: Boolean, onPlay: () -> Unit) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val project = state.project ?: return
    val summary = state.summary
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(
            stringResource(R.string.captions_length),
            style = type.caption,
            color = colors.textSecondary,
        )
        Column {
            Text(
                text = length(project.durationUs),
                style = type.meta,
                color = colors.textTertiary,
                textDecoration = if (summary.cuts > 0) TextDecoration.LineThrough else null,
            )
            Text(
                text = length(project.durationUs - summary.cutUs),
                style = type.numberMedium,
                color = colors.textPrimary,
                modifier = Modifier.testTag("new-length"),
            )
        }
        Text(
            text = if (summary.cuts == 0) {
                stringResource(R.string.captions_no_cuts)
            } else {
                pluralStringResource(
                    R.plurals.captions_cuts,
                    summary.cuts,
                    summary.cuts,
                    seconds(summary.cutUs),
                )
            },
            style = type.caption,
            color = colors.textSecondary,
        )
        Surface(
            onClick = onPlay,
            modifier = Modifier
                .size(44.dp)
                .testTag("text-play"),
            shape = CircleShape,
            color = colors.textPrimary,
            contentColor = colors.background,
        ) {
            Box(contentAlignment = Alignment.Center) {
                ClipraIcon(
                    icon = if (playing) ClipraIcons.Pause else ClipraIcons.Play,
                    contentDescription = stringResource(R.string.captions_play_edited),
                    size = 20.dp,
                )
            }
        }
    }
}

/** One source: its name, layer and start, then its tokens flowing like text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceText(
    source: TranscriptSource,
    state: EditByTextUiState,
    onToken: (Token) -> Unit,
) {
    val colors = ClipraTheme.colors
    val project = state.project ?: return
    val clip = source.clip
    val name = clip.source?.let { project.asset(it.mediaId) }?.displayName.orEmpty()
    val layer = project.layers.indexOfFirst { l -> l.clips.any { it.id == clip.id } } + 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = listOf(name, "L$layer", length(clip.startUs, clock = true))
                .filter { it.isNotEmpty() }.joinToString(" · "),
            style = ClipraTheme.typography.meta,
            color = colors.textSecondary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val now = state.originalPlayheadUs
            source.tokens.forEach { token ->
                TokenChip(
                    token = token,
                    cut = state.choices.isCut(token),
                    spoken = token.kind == TokenKind.Word && now in token.startUs until token.endUs,
                    onClick = { onToken(token) },
                )
            }
        }
    }
}

@Composable
private fun TokenChip(token: Token, cut: Boolean, spoken: Boolean, onClick: () -> Unit) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val pause = token.kind == TokenKind.Pause
    val background = when {
        cut -> colors.cutBand
        spoken -> colors.accent
        pause -> colors.surfaceRaised
        else -> colors.background
    }
    val content = when {
        cut -> colors.danger
        spoken -> colors.onAccent
        pause -> colors.textSecondary
        else -> colors.textPrimary
    }
    val decoration = when {
        cut -> TextDecoration.LineThrough
        token.kind == TokenKind.Filler -> TextDecoration.Underline
        else -> null
    }
    // Words flow like text, so they are tapped as text is, without the 48 dp button minimum.
    Box(
        modifier = Modifier
            .clip(ClipraTheme.shapes.thumb)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("token:${token.id}"),
    ) {
        Text(
            text = if (pause) {
                stringResource(R.string.captions_pause_token, seconds(token.durationUs))
            } else {
                token.text
            },
            style = if (pause) type.meta else type.body,
            color = if (token.kind == TokenKind.Filler && !cut) colors.fillerUnderline else content,
            textDecoration = decoration,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** "4 found · um, uh, you know": how many fillers, and which. */
@Composable
internal fun fillerNote(state: EditByTextUiState): String {
    val words = state.sources.flatMap { it.tokens }.filter { it.kind == TokenKind.Filler }
        .map { it.text.lowercase().trim { c -> !c.isLetter() && c != ' ' } }
        .distinct()
        .take(3)
    val found = stringResource(R.string.captions_fillers_found, state.summary.fillers)
    return if (words.isEmpty()) found else "$found · ${words.joinToString(", ")}"
}

@Composable
internal fun NoTranscript(onAutoCaptions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.captions_no_transcript),
            style = ClipraTheme.typography.body,
            color = ClipraTheme.colors.textSecondary,
        )
        PrimaryButton(
            text = stringResource(R.string.captions_title_captions_setup),
            onClick = onAutoCaptions,
            leadingIcon = ClipraIcons.Captions,
            modifier = Modifier.testTag("text-auto-captions"),
        )
    }
}

/** The board's footer: what a cut and a filler look like. */
@Composable
private fun Legend() {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(ClipraTheme.shapes.thumb)
                .background(colors.cutBand),
        )
        Text(
            stringResource(R.string.captions_cut),
            style = type.caption,
            color = colors.textSecondary,
        )
        Text(
            text = stringResource(R.string.captions_filler_sample),
            style = type.caption,
            color = colors.fillerUnderline,
            textDecoration = TextDecoration.Underline,
        )
        Text(
            stringResource(R.string.captions_filler),
            style = type.caption,
            color = colors.textSecondary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.captions_tap_word),
            style = type.caption,
            color = colors.textSecondary,
        )
    }
}

/** 0:42.0, or 00:05.5 as a [clock]. */
internal fun length(us: Long, clock: Boolean = false): String {
    val minutes = us / 60_000_000
    val seconds = us / 1_000_000 % 60
    val tenths = us / 100_000 % 10
    val pattern = if (clock) "%02d:%02d.%d" else "%d:%02d.%d"
    return String.format(Locale.ROOT, pattern, minutes, seconds, tenths)
}

internal fun seconds(us: Long) = String.format(Locale.getDefault(), "%.1f", us / 1e6)
