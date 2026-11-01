package tech.idct.clipra.feature.captions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.IconAction
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.component.Segment
import tech.idct.clipra.core.designsystem.component.SegmentedControl
import tech.idct.clipra.core.designsystem.component.SegmentedStyle
import tech.idct.clipra.core.designsystem.component.TextAction
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.CaptionText

/**
 * The transcript in the tablet editor (#70, TabletCaptions board): every caption in time order
 * with its words, the ones to check underlined, beside the editor's preview and timeline. A tap
 * on a caption moves the playhead there; a tap on a word retypes it, splits or merges. Search
 * and replace, Edit by text, and .srt in and out. With no captions yet, Auto captions.
 */
@Composable
fun CaptionsTranscriptPanel(
    playheadUs: Long,
    onSeek: (Long) -> Unit,
    onEditByText: () -> Unit,
    onAutoCaptions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CaptionsEditViewModel = hiltViewModel(key = PANEL_KEY),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(playheadUs) { viewModel.seek(playheadUs) }
    // .srt files the user picks (#46).
    val saveSrt = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SrtFiles.MIME),
    ) { uri -> uri?.let(viewModel::saveSrt) }
    val importSrt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::importSrt)
    }
    CaptionsTranscript(
        state = state,
        actions = CaptionsEditActions(
            onDone = {},
            onStyle = viewModel::setStyle,
            onPosition = viewModel::setPosition,
            onScale = {},
            onScaleDone = {},
            onSeek = {
                viewModel.seek(it)
                onSeek(it)
            },
            onEditByText = onEditByText,
            onEditWord = viewModel::editWord,
            onSplit = viewModel::split,
            onMerge = viewModel::merge,
            onReplaceAll = viewModel::replaceAll,
            onSaveSrt = { state.project?.let { saveSrt.launch(SrtFiles.fileName(it)) } },
            onImportSrt = { importSrt.launch(SrtFiles.types) },
            onDismissMessage = viewModel::dismissMessage,
            onShowLanguage = { viewModel.showLanguage(it) },
            onTranslate = viewModel::openTranslate,
            onPickSource = viewModel::pickSource,
            onTranslateTo = viewModel::translate,
            onCloseTranslate = viewModel::closeTranslate,
        ),
        onAutoCaptions = onAutoCaptions,
        modifier = modifier,
    )
}

/** The captions of [state] as a transcript, with the word bar or the .srt buttons below. */
@Composable
internal fun CaptionsTranscript(
    state: CaptionsEditUiState,
    actions: CaptionsEditActions,
    onAutoCaptions: () -> Unit,
    modifier: Modifier = Modifier,
    initialPick: WordPick? = null,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val captions = state.captions
    var pick by remember { mutableStateOf(initialPick) }
    var find by rememberSaveable { mutableStateOf<String?>(null) }
    val query = find.orEmpty()
    val message = state.message?.let { messageText(it) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(MESSAGE_MS)
            actions.onDismissMessage()
        }
    }
    Column(
        modifier = modifier
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("captions-transcript"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.captions_transcript),
                style = type.bodyStrong,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            TextAction(
                text = stringResource(R.string.captions_title_transcript),
                onClick = actions.onEditByText,
                modifier = Modifier.testTag("captions-edit-by-text"),
            )
            IconAction(
                icon = ClipraIcons.Search,
                contentDescription = stringResource(R.string.captions_search),
                onClick = { find = if (find == null) "" else null },
                modifier = Modifier.testTag("captions-search-open"),
            )
        }
        if (captions.isEmpty()) {
            Text(
                text = stringResource(R.string.captions_none_yet),
                style = type.caption,
                color = colors.textSecondary,
            )
            SecondaryButton(
                text = stringResource(R.string.captions_title_captions_setup),
                onClick = onAutoCaptions,
                leadingIcon = ClipraIcons.Captions,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("captions-auto"),
            )
            SecondaryButton(
                text = stringResource(R.string.captions_import_srt),
                onClick = actions.onImportSrt,
                outlined = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("captions-import-srt"),
            )
            return@Column
        }
        Text(
            text = listOfNotNull(
                stringResource(R.string.captions_count, captions.size),
                state.wordsToCheck.takeIf { it > 0 }
                    ?.let { stringResource(R.string.captions_to_check, it) },
            ).joinToString(" · "),
            style = type.caption,
            color = colors.textSecondary,
        )
        // The languages and Translate (#47), as the TabletCaptions board shows them.
        LanguageChips(state, actions, withTranslate = true)
        if (message != null) {
            Text(text = message, style = type.caption, color = colors.accent)
        }
        if (find != null) {
            SearchBar(
                find = query,
                onFind = { find = it },
                matches = captions.sumOf { CaptionText.matches(it.caption!!.words, query).size },
                onReplaceAll = { actions.onReplaceAll(query, it) },
                onClose = { find = null },
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            captions.forEach { clip ->
                CaptionRow(
                    clip = clip,
                    active = state.playheadUs in clip.startUs until clip.endUs,
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
            captions.find { it.id == p.clipId }?.takeIf { p.index in it.caption!!.words.indices }
        }
        if (pick != null && picked != null) {
            val index = pick!!.index
            val words = picked.caption!!.words
            WordBar(
                word = words[index].text,
                canSplit = index > 0,
                canMerge = captions.any { it !== picked && it.startUs >= picked.endUs },
                onSave = { text ->
                    if (text != words[index].text) actions.onEditWord(picked.id, index, text)
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
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.captions_save_srt),
                    onClick = actions.onSaveSrt,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("captions-save-srt"),
                )
                SecondaryButton(
                    text = stringResource(R.string.captions_import_srt),
                    onClick = actions.onImportSrt,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("captions-import-srt"),
                )
            }
        }
    }
    state.translate?.let { TranslateDialog(it, actions) }
}

/**
 * The captions inspector in the tablet editor (#70, TabletCaptions board). Style holds the look
 * every caption shares; the size is committed when its slider is let go. Transcribe makes the
 * captions again (#181, TabletCaptions--transcribe-tab): another model or language, and the
 * clips added since.
 */
@Composable
fun CaptionsInspectorPanel(
    modifier: Modifier = Modifier,
    viewModel: CaptionsEditViewModel = hiltViewModel(key = PANEL_KEY),
    setup: CaptionsSetupViewModel = hiltViewModel(key = SETUP_KEY),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val setupState by setup.state.collectAsStateWithLifecycle()
    LaunchedEffect(setup) { setup.redoCaptioned() }
    var liveScale by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(state.project) { liveScale = null }
    var tab by rememberSaveable { mutableStateOf(CaptionsTab.Style) }
    CaptionsInspector(
        tab = tab,
        onTab = { tab = it },
        state = state,
        scale = liveScale,
        actions = CaptionsEditActions(
            onDone = {},
            onStyle = viewModel::setStyle,
            onPosition = viewModel::setPosition,
            onScale = { liveScale = it },
            onScaleDone = { liveScale?.let(viewModel::setScale) },
            onSeek = {},
            onEditByText = {},
        ),
        setup = setupState,
        setupActions = CaptionsSetupActions(
            onBack = {},
            onListenTo = setup::setListenTo,
            onLanguage = setup::setLanguage,
            onTranslate = setup::setTranslate,
            onModel = setup::selectModel,
            onDownload = setup::download,
            onOptions = setup::setOptions,
            onGenerate = setup::generate,
            onCancel = setup::cancel,
        ),
        modifier = modifier,
    )
}

/** The tabs of the captions inspector. */
internal enum class CaptionsTab { Style, Transcribe }

/** The captions inspector: the [tab] of Style and Transcribe that is open. */
@Composable
internal fun CaptionsInspector(
    tab: CaptionsTab,
    onTab: (CaptionsTab) -> Unit,
    state: CaptionsEditUiState,
    scale: Float?,
    actions: CaptionsEditActions,
    setup: CaptionsSetupUiState,
    setupActions: CaptionsSetupActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("captions-inspector"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SegmentedControl(
            segments = listOf(
                Segment(stringResource(R.string.captions_tab_style)),
                Segment(stringResource(R.string.captions_tab_transcribe)),
            ),
            selectedIndex = tab.ordinal,
            onSelect = { onTab(CaptionsTab.entries[it]) },
            style = SegmentedStyle.Tabs,
            modifier = Modifier.fillMaxWidth(),
        )
        when (tab) {
            CaptionsTab.Style -> CaptionsLookInspector(state, scale, actions)

            CaptionsTab.Transcribe ->
                TranscribeTab(setup, setupActions, again = state.captions.isNotEmpty())
        }
    }
}

/** The look of [state]'s captions, or a word that there are none yet. */
@Composable
private fun CaptionsLookInspector(
    state: CaptionsEditUiState,
    scale: Float?,
    actions: CaptionsEditActions,
) {
    Column(
        modifier = Modifier.testTag("captions-look"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val look = state.look
        if (look == null) {
            Text(
                text = stringResource(R.string.captions_none_yet),
                style = ClipraTheme.typography.caption,
                color = ClipraTheme.colors.textSecondary,
            )
        } else {
            CaptionsLook(look, scale, actions, wrap = true)
        }
    }
}

/**
 * The Transcribe tab (#181): the Whisper models on this device, the spoken language, and
 * Generate, which [again] replaces the captions there are.
 */
@Composable
private fun TranscribeTab(
    state: CaptionsSetupUiState,
    actions: CaptionsSetupActions,
    again: Boolean,
) {
    Column(
        modifier = Modifier.testTag("captions-transcribe"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Section(stringResource(R.string.captions_model_here)) {
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.models.forEach { model ->
                    ModelOption(
                        model = model,
                        selected = model.model == state.model,
                        onSelect = { actions.onModel(model.model) },
                        onDownload = { actions.onDownload(model.model) },
                        compact = true,
                    )
                }
            }
        }
        // One under the other: side by side, the language's name didn't fit.
        LanguageField(state.language, actions.onLanguage, Modifier.fillMaxWidth())
        TranslateToggle(state.translate, actions.onTranslate, Modifier.fillMaxWidth(), wide = true)
        GenerateNote(state)
        val idle = if (again) R.string.captions_regenerate_with else R.string.captions_generate_with
        PrimaryButton(
            text = generateText(state, stringResource(idle, state.model.name)),
            onClick = actions.onGenerate,
            enabled = !state.running && state.sources.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("captions-generate"),
        )
        if (state.running) {
            TextAction(
                text = stringResource(R.string.captions_cancel),
                onClick = actions.onCancel,
                color = ClipraTheme.colors.textSecondary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .testTag("captions-cancel"),
            )
        }
    }
}

/** The transcript and the look share one view model in the editor's screen. */
private const val PANEL_KEY = "tablet-captions"

/** The Transcribe tab's Auto captions, in the editor's screen. */
private const val SETUP_KEY = "tablet-captions-setup"

private const val MESSAGE_MS = 4_000L
