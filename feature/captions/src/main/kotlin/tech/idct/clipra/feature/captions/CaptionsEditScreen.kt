package tech.idct.clipra.feature.captions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ChipStyle
import tech.idct.clipra.core.designsystem.component.ClipraChip
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.IconAction
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.component.Segment
import tech.idct.clipra.core.designsystem.component.SegmentedControl
import tech.idct.clipra.core.designsystem.component.SegmentedStyle
import tech.idct.clipra.core.designsystem.component.TextAction
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.CaptionPosition
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.CaptionStyle
import tech.idct.clipra.core.model.CaptionStyles
import tech.idct.clipra.core.model.CaptionText
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
    /** Retypes a word; empty takes it out (#46). */
    val onEditWord: (clipId: String, index: Int, text: String) -> Unit = { _, _, _ -> },
    /** Splits a caption where a word starts. */
    val onSplit: (clipId: String, index: Int) -> Unit = { _, _ -> },
    val onMerge: (clipId: String) -> Unit = {},
    val onReplaceAll: (find: String, replacement: String) -> Unit = { _, _ -> },
    val onSaveSrt: () -> Unit = {},
    val onImportSrt: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)

/**
 * The CaptionsEdit board (#45): the preview, the five styles, position and size for every
 * caption, and the captions in time order with the words Whisper was unsure of underlined. Tap
 * a caption to see it. Tap a word to retype it, split the caption there or merge it with the
 * next; search and replace in every caption; save and import .srt (#46). Translation comes
 * with #47.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptionsEditScreen(
    state: CaptionsEditUiState,
    scale: Float?,
    actions: CaptionsEditActions,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit = {},
    initialPick: WordPick? = null,
    initialFind: String? = null,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val captions = state.captions
    val look = state.look
    var pick by remember { mutableStateOf(initialPick) }
    var find by rememberSaveable { mutableStateOf(initialFind) }
    val query = find.orEmpty()
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let { messageText(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            actions.onDismissMessage()
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
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
                    IconAction(
                        icon = ClipraIcons.Search,
                        contentDescription = stringResource(R.string.captions_search),
                        onClick = { find = if (find == null) "" else null },
                        modifier = Modifier.testTag("captions-search-open"),
                    )
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
                    // Smaller while typing, so the caption being edited stays in view.
                    .height(if (WindowInsets.isImeVisible) 160.dp else 280.dp)
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
                // While search is open (#46), it takes the place of the look.
                if (find != null) {
                    SearchBar(
                        find = query,
                        onFind = { find = it },
                        matches = captions.sumOf {
                            CaptionText.matches(it.caption!!.words, query).size
                        },
                        onReplaceAll = { actions.onReplaceAll(query, it) },
                        onClose = { find = null },
                    )
                }
                // The look steps aside while searching or editing a word, for the captions.
                if (look != null && find == null && pick == null) {
                    CaptionsLook(look, scale, actions)
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
                        // Opens the first word to check.
                        Text(
                            text = stringResource(R.string.captions_to_check, state.wordsToCheck),
                            style = type.caption.copy(textDecoration = TextDecoration.Underline),
                            color = colors.textSecondary,
                            modifier = Modifier
                                .clip(ClipraTheme.shapes.thumb)
                                .clickable(role = Role.Button) {
                                    state.firstToCheck?.let { (clipId, index) ->
                                        pick = WordPick(clipId, index)
                                    }
                                }
                                .padding(vertical = 12.dp)
                                .testTag("captions-check"),
                        )
                    }
                }
                captions.forEach { clip ->
                    val active = state.playheadUs in clip.startUs until clip.endUs
                    CaptionRow(
                        clip = clip,
                        active = active,
                        found = CaptionText.matches(clip.caption!!.words, query)
                            .flatMap { it.toList() }.toSet(),
                        picked = pick?.takeIf { it.clipId == clip.id }?.index,
                        onClick = { actions.onSeek(clip.startUs) },
                        onWord = { index ->
                            actions.onSeek(clip.startUs)
                            pick = WordPick(clip.id, index)
                        },
                    )
                }
            }
            val picked = pick?.let { p ->
                captions.find {
                    it.id == p.clipId
                }?.takeIf { p.index in it.caption!!.words.indices }
            }
            if (pick != null && picked != null) {
                val index = pick!!.index
                val words = picked.caption!!.words
                WordBar(
                    word = words[index].text,
                    canSplit = index > 0,
                    canMerge = captions.any { it !== picked && it.startUs >= picked.endUs },
                    onSave = { text ->
                        if (text != words[index].text) {
                            actions.onEditWord(picked.id, index, text)
                        }
                        pick = null
                    },
                    onSplit = {
                        actions.onSplit(picked.id, index)
                        pick = null
                    },
                    onMerge = {
                        actions.onMerge(picked.id)
                        pick = null
                    },
                    onClose = { pick = null },
                )
            } else if (!WindowInsets.isImeVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SecondaryButton(
                        text = stringResource(R.string.captions_save_srt),
                        onClick = actions.onSaveSrt,
                        leadingIcon = ClipraIcons.Download,
                        enabled = captions.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("captions-save-srt"),
                    )
                    SecondaryButton(
                        text = stringResource(R.string.captions_import_srt),
                        onClick = actions.onImportSrt,
                        leadingIcon = ClipraIcons.Captions,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("captions-import-srt"),
                    )
                }
            }
        }
        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp),
        )
    }
}

@Composable
internal fun messageText(message: CaptionsMessage): String = when (message) {
    CaptionsMessage.Saved -> stringResource(R.string.captions_saved_srt)

    is CaptionsMessage.Imported ->
        pluralStringResource(R.plurals.captions_imported, message.count, message.count)

    CaptionsMessage.Unreadable -> stringResource(R.string.captions_unreadable_srt)

    CaptionsMessage.NotHere -> stringResource(R.string.captions_not_here)

    CaptionsMessage.Locked -> stringResource(R.string.captions_locked)
}

/**
 * The look every caption shares: the five styles, the position and the size ([scale] while its
 * slider moves). [wrap] lays the styles out in rows, as the tablet's inspector (#70); the phone
 * scrolls them in one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CaptionsLook(
    look: CaptionSpec,
    scale: Float?,
    actions: CaptionsEditActions,
    wrap: Boolean = false,
) {
    val chips: @Composable () -> Unit = {
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
    if (wrap) {
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { chips() }
    } else {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { chips() }
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

/**
 * A caption: its time, and its words with the ones to check underlined and the ones search
 * [found] marked. Tap a word to edit it; the word being edited ([picked]) is marked and kept in
 * view above the word bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CaptionRow(
    clip: Clip,
    active: Boolean,
    found: Set<Int>,
    picked: Int?,
    onClick: () -> Unit,
    onWord: (Int) -> Unit,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val words = clip.caption?.words.orEmpty()
    val bring = remember { BringIntoViewRequester() }
    // And again while the keyboard makes the screen shorter, once each new layout is in.
    val keyboard = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(picked) {
        if (picked == null) return@LaunchedEffect
        snapshotFlow { keyboard.getBottom(density) }.collect {
            withFrameNanos { }
            bring.bringIntoView()
        }
    }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bring)
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                words.forEachIndexed { i, word ->
                    val unsure = word.confidence < CaptionsEditUiState.LOW_CONFIDENCE
                    val edit = stringResource(R.string.captions_edit_word)
                    Text(
                        text = word.text,
                        style = if (unsure) {
                            type.body.copy(textDecoration = TextDecoration.Underline)
                        } else {
                            type.body
                        },
                        color = when {
                            i == picked || i in found -> colors.accent
                            unsure -> colors.lowConfidenceUnderline
                            else -> colors.textPrimary
                        },
                        modifier = Modifier
                            .clip(ClipraTheme.shapes.thumb)
                            .background(
                                when {
                                    i == picked -> colors.accentSelectedBorder
                                    i in found -> colors.accentChipBackground
                                    else -> Color.Transparent
                                },
                            )
                            .clickable(role = Role.Button, onClickLabel = edit) { onWord(i) }
                            .padding(horizontal = 3.dp, vertical = 4.dp)
                            .testTag("word:${clip.id}:$i"),
                    )
                }
            }
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
internal fun clock(us: Long, tenths: Boolean = false): String {
    val minutes = us / 60_000_000
    val seconds = us / 1_000_000 % 60
    return if (tenths) {
        String.format(Locale.ROOT, "%02d:%02d.%d", minutes, seconds, us / 100_000 % 10)
    } else {
        String.format(Locale.ROOT, "%02d:%02d.%02d", minutes, seconds, us / 10_000 % 100)
    }
}

internal val STYLES = listOf(
    CaptionStyle.Clean to R.string.captions_style_clean,
    CaptionStyle.BoldPop to R.string.captions_style_bold_pop,
    CaptionStyle.Boxed to R.string.captions_style_boxed,
    CaptionStyle.Karaoke to R.string.captions_style_karaoke,
    CaptionStyle.Minimal to R.string.captions_style_minimal,
)

internal val POSITIONS = listOf(
    CaptionPosition.Top to R.string.captions_top,
    CaptionPosition.Middle to R.string.captions_middle,
    CaptionPosition.Bottom to R.string.captions_bottom,
)
