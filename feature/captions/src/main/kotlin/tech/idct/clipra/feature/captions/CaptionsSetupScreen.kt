package tech.idct.clipra.feature.captions

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ChipStyle
import tech.idct.clipra.core.designsystem.component.ClipraChip
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.ClipraSwitch
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.OnDeviceCard
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.SwitchRow
import tech.idct.clipra.core.designsystem.component.TextAction
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens
import tech.idct.clipra.core.engine.media.Transcriber
import tech.idct.clipra.core.model.CaptionOptions
import tech.idct.clipra.core.models.AiModel
import tech.idct.clipra.core.models.ModelCatalog
import tech.idct.clipra.core.models.ModelState
import tech.idct.clipra.core.models.ModelStatus
import java.util.Locale

/** What the Auto captions setup can do. */
class CaptionsSetupActions(
    val onBack: () -> Unit,
    val onListenTo: (ListenTo) -> Unit,
    val onLanguage: (String) -> Unit,
    val onTranslate: (Boolean) -> Unit,
    val onModel: (AiModel) -> Unit,
    val onDownload: (AiModel) -> Unit,
    val onOptions: (CaptionOptions) -> Unit,
    val onGenerate: () -> Unit,
    val onCancel: () -> Unit,
    /** Captions from an .srt file instead (#46). */
    val onImportSrt: () -> Unit = {},
)

/**
 * The CaptionsSetup board (#44): what to listen to, the spoken language (or English), the
 * Whisper model with its real size and state, the caption options and Generate. A model that is
 * not downloaded is downloaded first ("Download 488 MB and generate"); while Whisper runs, the
 * button shows its progress and Cancel stops it.
 */
@Composable
fun CaptionsSetupScreen(
    state: CaptionsSetupUiState,
    actions: CaptionsSetupActions,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:CaptionsSetup"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.captions_title_captions_setup),
            navigation = HeaderNavigation.Back,
            onNavigate = actions.onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OnDeviceCard(
                title = stringResource(R.string.captions_on_device_title),
                subtitle = stringResource(R.string.captions_on_device_detail),
                modifier = Modifier.fillMaxWidth(),
            )
            Section(stringResource(R.string.captions_listen_to)) {
                Row(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LISTEN_TO.forEach { (listenTo, label) ->
                        ClipraChip(
                            label = stringResource(label),
                            selected = state.listenTo == listenTo,
                            onClick = { actions.onListenTo(listenTo) },
                            selectedStyle = ChipStyle.Light,
                            modifier = Modifier.testTag("listen:${listenTo.name}"),
                        )
                    }
                }
            }
            Section(stringResource(R.string.captions_language)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LanguageField(state.language, actions.onLanguage, Modifier.weight(1f))
                    Row(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = state.translate,
                                role = Role.Switch,
                                onValueChange = actions.onTranslate,
                            )
                            .testTag("captions-translate"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.captions_translate),
                            style = ClipraTheme.typography.chip,
                            color = colors.textPrimary,
                        )
                        ClipraSwitch(checked = state.translate, onCheckedChange = null)
                    }
                }
            }
            Section(stringResource(R.string.captions_model)) {
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
                        )
                    }
                }
                if (state.language == POLISH && state.model in BASIC_MODELS) {
                    Text(
                        text = stringResource(R.string.captions_polish_hint),
                        style = ClipraTheme.typography.caption,
                        color = colors.textSecondary,
                    )
                }
            }
            Column {
                val options = state.options
                SwitchRow(
                    title = stringResource(R.string.captions_highlight),
                    checked = options.highlightWords,
                    onCheckedChange = { actions.onOptions(options.copy(highlightWords = it)) },
                )
                SwitchRow(
                    title = stringResource(R.string.captions_fillers),
                    checked = options.removeFillers,
                    onCheckedChange = { actions.onOptions(options.copy(removeFillers = it)) },
                )
                SwitchRow(
                    title = stringResource(R.string.captions_new_layer),
                    checked = options.newLayer,
                    onCheckedChange = { actions.onOptions(options.copy(newLayer = it)) },
                )
            }
        }
        GenerateBar(state, actions)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = ClipraTheme.typography.sectionLabel,
            color = ClipraTheme.colors.textSecondary,
        )
        content()
    }
}

/** The spoken language: Auto-detect or one of Whisper's languages, by their own names. */
@Composable
private fun LanguageField(language: String, onPick: (String) -> Unit, modifier: Modifier) {
    val colors = ClipraTheme.colors
    var open by remember { mutableStateOf(false) }
    val shape = ClipraTheme.shapes.button
    Box(modifier = modifier) {
        Surface(
            onClick = { open = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .border(ClipraDimens.BorderWidth, colors.border, shape)
                .testTag("captions-language"),
            shape = shape,
            color = colors.surface,
            contentColor = colors.textPrimary,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ClipraIcon(icon = ClipraIcons.Translate, contentDescription = null, size = 20.dp)
                Text(
                    text = languageName(language),
                    style = ClipraTheme.typography.bodyStrong,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ClipraIcon(
                    icon = ClipraIcons.ChevronDown,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    size = 18.dp,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LANGUAGES.forEach { code ->
                DropdownMenuItem(
                    text = { Text(languageName(code)) },
                    onClick = {
                        open = false
                        onPick(code)
                    },
                )
            }
        }
    }
}

@Composable
private fun languageName(code: String): String = if (code == Transcriber.AUTO) {
    stringResource(R.string.captions_auto_detect)
} else {
    val locale = LocalConfiguration.current.locales[0]
    Locale.forLanguageTag(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

/** A Whisper model: radio, name, real size, what it's good for and its download state. */
@Composable
private fun ModelOption(
    model: ModelState,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val shape = ClipraTheme.shapes.button
    val context = LocalContext.current
    val status = model.status
    val bytes = (status as? ModelStatus.Installed)?.bytes ?: model.model.downloadBytes
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(shape)
            .background(if (selected) colors.accentSelectedBackground else colors.surface)
            .border(ClipraDimens.BorderWidth, if (selected) colors.accent else colors.border, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .testTag("model:${model.model.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(2.dp, if (selected) colors.accent else colors.textDisabled, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = model.model.name, style = type.bodyStrong, color = colors.textPrimary)
                Text(
                    text = Formatter.formatShortFileSize(context, bytes),
                    style = type.meta,
                    color = colors.textSecondary,
                )
                if (model.model.isDefault) {
                    Text(
                        text = stringResource(R.string.captions_recommended),
                        style = type.clipLabel,
                        color = colors.accent,
                        modifier = Modifier
                            .clip(ClipraTheme.shapes.thumb)
                            .background(colors.accentChipBackground)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = stringResource(MODEL_NOTES[model.model.id] ?: R.string.captions_model_base),
                style = type.caption,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Availability(status, onDownload)
    }
}

@Composable
private fun Availability(status: ModelStatus, onDownload: () -> Unit) {
    val colors = ClipraTheme.colors
    val label = ClipraTheme.typography.navLabel
    when (status) {
        is ModelStatus.Installed -> Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ClipraIcon(
                icon = ClipraIcons.Check,
                contentDescription = null,
                tint = colors.onDeviceText,
                size = 16.dp,
            )
            Text(
                stringResource(R.string.captions_installed),
                style = label,
                color = colors.onDeviceText,
            )
        }

        is ModelStatus.Downloading -> Text(
            text = stringResource(
                R.string.captions_percent,
                (status.bytes * 100 / status.total.coerceAtLeast(1)).toInt(),
            ),
            style = ClipraTheme.typography.meta,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 10.dp),
        )

        is ModelStatus.Waiting -> Text(
            text = stringResource(R.string.captions_waiting_wifi),
            style = label,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 10.dp),
        )

        else -> Surface(
            onClick = onDownload,
            modifier = Modifier.heightIn(min = ClipraDimens.TouchTarget),
            shape = ClipraTheme.shapes.button,
            color = Color.Transparent,
            contentColor = colors.accent,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ClipraIcon(icon = ClipraIcons.Download, contentDescription = null, size = 16.dp)
                Text(stringResource(R.string.captions_get), style = label)
            }
        }
    }
}

/** Generate captions, or what it is doing: downloading the model, then transcribing. */
@Composable
private fun GenerateBar(state: CaptionsSetupUiState, actions: CaptionsSetupActions) {
    val colors = ClipraTheme.colors
    val context = LocalContext.current
    val transcription = state.transcription
    val status = state.modelStatus
    val running = transcription is TranscriptionState.Running || state.waitingForModel
    val text = when {
        transcription is TranscriptionState.Running -> stringResource(
            R.string.captions_transcribing,
            (transcription.progress * 100).toInt(),
        )

        state.waitingForModel && status is ModelStatus.Downloading -> stringResource(
            R.string.captions_downloading_model,
            (status.bytes * 100 / status.total.coerceAtLeast(1)).toInt(),
        )

        state.waitingForModel -> stringResource(R.string.captions_waiting_model)

        status !is ModelStatus.Installed -> stringResource(
            R.string.captions_download_generate,
            Formatter.formatShortFileSize(context, state.model.downloadBytes),
        )

        else -> stringResource(R.string.captions_generate)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val note = when {
            state.importFailed -> R.string.captions_unreadable_srt
            state.sources.isEmpty() && state.loaded -> R.string.captions_no_sources
            transcription is TranscriptionState.Failed && !running -> R.string.captions_failed
            else -> null
        }
        if (note != null) {
            val failed = transcription is TranscriptionState.Failed || state.importFailed
            Text(
                text = stringResource(note),
                style = ClipraTheme.typography.caption,
                color = if (failed) colors.danger else colors.textSecondary,
            )
        }
        PrimaryButton(
            text = text,
            onClick = actions.onGenerate,
            leadingIcon = ClipraIcons.Captions,
            enabled = !running && state.sources.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("captions-generate"),
        )
        if (running) {
            TextAction(
                text = stringResource(R.string.captions_cancel),
                onClick = actions.onCancel,
                color = colors.textSecondary,
                modifier = Modifier.testTag("captions-cancel"),
            )
        } else {
            TextAction(
                text = stringResource(R.string.captions_import_srt_instead),
                onClick = actions.onImportSrt,
                color = colors.textSecondary,
                modifier = Modifier.testTag("captions-import-srt"),
            )
        }
    }
}

private val LISTEN_TO = listOf(
    ListenTo.MainClips to R.string.captions_main_clips,
    ListenTo.Voiceover to R.string.captions_voiceover,
    ListenTo.AllTracks to R.string.captions_all_tracks,
)

private val MODEL_NOTES = mapOf(
    ModelCatalog.whisperTiny.id to R.string.captions_model_tiny,
    ModelCatalog.whisperBase.id to R.string.captions_model_base,
    ModelCatalog.whisperSmall.id to R.string.captions_model_small,
    ModelCatalog.whisperLargeTurbo.id to R.string.captions_model_turbo,
)

private const val POLISH = "pl"

// The spike (docs/spikes/whisper.md): Polish needs Small or larger.
private val BASIC_MODELS = setOf(ModelCatalog.whisperTiny, ModelCatalog.whisperBase)

/** Whisper's languages offered by name; others are found by Auto-detect. */
private val LANGUAGES = listOf(
    Transcriber.AUTO, "en", "pl", "de", "es", "fr", "it", "pt", "nl", "uk", "cs", "sk", "sv",
    "da", "no", "fi", "hu", "ro", "el", "tr", "ru", "ar", "hi", "ja", "ko", "zh",
)
