package tech.idct.clipra.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.OnDeviceCard
import tech.idct.clipra.core.designsystem.component.ProCard
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.Segment
import tech.idct.clipra.core.designsystem.component.SegmentedControl
import tech.idct.clipra.core.designsystem.component.TextAction
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.export.CaptionMode
import tech.idct.clipra.core.model.export.ExportQuality
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.model.export.VideoCodec
import tech.idct.clipra.monetization.billing.RestoreResult
import tech.idct.clipra.monetization.R as MonetizationR

/** What Settings can do. */
class SettingsActions(
    val onBack: () -> Unit,
    val onPro: () -> Unit,
    val onRestore: () -> Unit,
    val onExportDefaults: (ExportSettings) -> Unit,
    val onClearCache: () -> Unit,
    val onModels: () -> Unit,
    val onAdPrivacy: () -> Unit,
    val onLicenses: () -> Unit,
)

/**
 * Settings (#74; no board in the design, built from the design system): Clipra Pro and Restore,
 * export defaults, storage (proxies and caches, AI models), privacy and about.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Settings"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.settings_title),
            navigation = HeaderNavigation.Back,
            onNavigate = actions.onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProCard(
                title = stringResource(
                    if (state.pro) R.string.settings_pro_owned else R.string.settings_pro,
                ),
                subtitle = when {
                    state.pro -> stringResource(R.string.settings_pro_owned_detail)

                    state.proPrice != null ->
                        stringResource(R.string.settings_pro_detail_price, state.proPrice)

                    else -> stringResource(R.string.settings_pro_detail)
                },
                onClick = actions.onPro,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings-pro"),
                action = { Chevron() },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction(
                    text = stringResource(MonetizationR.string.monetization_pro_restore),
                    onClick = actions.onRestore,
                    modifier = Modifier.testTag("settings-restore"),
                )
                restoreLine(state)?.let {
                    Text(
                        text = it,
                        style = ClipraTheme.typography.caption,
                        color = colors.textSecondary,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings-restore-result"),
                    )
                }
            }

            SectionLabel(R.string.settings_export_defaults)
            ExportDefaults(state, actions.onExportDefaults)

            SectionLabel(R.string.settings_storage)
            SettingsRow(
                title = stringResource(R.string.settings_cache),
                value = state.cacheBytes?.let { Formatter.formatShortFileSize(context, it) },
                tag = "settings-cache",
            ) {
                TextAction(
                    text = stringResource(R.string.settings_clear),
                    onClick = { confirmClear = true },
                    color = if (state.exporting || state.cacheBytes == 0L) {
                        colors.textDisabled
                    } else {
                        colors.accent
                    },
                    modifier = Modifier.testTag("settings-clear"),
                )
            }
            Note(if (state.exporting) R.string.settings_cache_exporting else CacheNote)
            SettingsRow(
                title = stringResource(R.string.settings_models),
                value = Formatter.formatShortFileSize(context, state.modelBytes),
                tag = "settings-models",
                onClick = actions.onModels,
            ) { Chevron() }

            SectionLabel(R.string.settings_privacy)
            OnDeviceCard(
                title = stringResource(R.string.settings_on_device),
                subtitle = stringResource(R.string.settings_on_device_detail),
                icon = ClipraIcons.OnDevice,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.adPrivacyChoices) {
                SettingsRow(
                    title = stringResource(MonetizationR.string.monetization_ad_privacy),
                    tag = "settings-ad-privacy",
                    onClick = actions.onAdPrivacy,
                ) { Chevron() }
            }

            SectionLabel(R.string.settings_about)
            SettingsRow(
                title = stringResource(R.string.settings_licenses),
                tag = "settings-licenses",
                onClick = actions.onLicenses,
            ) { Chevron() }
            SettingsRow(
                title = stringResource(R.string.settings_version),
                value = state.version,
                tag = "settings-version",
            )
        }
    }
    if (confirmClear) {
        val enabled = !state.exporting && state.cacheBytes != 0L
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_clear_title)) },
            text = {
                Text(
                    stringResource(
                        if (enabled) ClearBody else R.string.settings_cache_exporting,
                    ),
                )
            },
            confirmButton = {
                if (enabled) {
                    TextButton(
                        onClick = {
                            actions.onClearCache()
                            confirmClear = false
                        },
                        modifier = Modifier.testTag("settings-clear-confirm"),
                    ) { Text(stringResource(R.string.settings_clear)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

/** Codec, quality and captions for a project's first export. */
@Composable
private fun ExportDefaults(state: SettingsUiState, onChange: (ExportSettings) -> Unit) {
    val defaults = state.exportDefaults
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(R.string.settings_codec)
        SegmentedControl(
            segments = VideoCodec.entries.map {
                Segment(label = it.label(), enabled = it in state.codecs)
            },
            selectedIndex = defaults.codec.ordinal,
            onSelect = { onChange(defaults.copy(codec = VideoCodec.entries[it])) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings-codec"),
        )
        Label(R.string.settings_quality)
        SegmentedControl(
            segments = listOf(
                R.string.settings_quality_smaller,
                R.string.settings_quality_recommended,
                R.string.settings_quality_high,
            ).map { Segment(stringResource(it)) },
            selectedIndex = defaults.quality.ordinal,
            onSelect = { onChange(defaults.copy(quality = ExportQuality.entries[it])) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings-quality"),
        )
        Label(R.string.settings_captions)
        SegmentedControl(
            segments = listOf(
                R.string.settings_captions_burn_in,
                R.string.settings_captions_srt,
                R.string.settings_captions_off,
            ).map { Segment(stringResource(it)) },
            selectedIndex = defaults.captions.ordinal,
            onSelect = { onChange(defaults.copy(captions = CaptionMode.entries[it])) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings-captions"),
        )
        Note(R.string.settings_export_note)
    }
}

/** A row on a card: a title, a value on the right and an optional trailing action. */
@Composable
private fun SettingsRow(
    title: String,
    tag: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(ClipraTheme.shapes.card)
            .background(colors.surface)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(start = 16.dp, end = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = type.bodyStrong,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                style = type.meta.copy(fontSize = type.label.fontSize),
                color = colors.textSecondary,
                modifier = Modifier.padding(end = if (onClick == null) 8.dp else 0.dp),
            )
        }
        trailing()
    }
}

@Composable
private fun Chevron() {
    ClipraIcon(
        icon = ClipraIcons.ChevronRight,
        contentDescription = null,
        tint = ClipraTheme.colors.textSecondary,
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
private fun SectionLabel(text: Int) {
    Text(
        text = stringResource(text),
        style = ClipraTheme.typography.sectionLabel,
        color = ClipraTheme.colors.textSecondary,
        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
    )
}

@Composable
private fun Label(text: Int) {
    Text(
        text = stringResource(text),
        style = ClipraTheme.typography.label,
        color = ClipraTheme.colors.textPrimary,
    )
}

@Composable
private fun Note(text: Int) {
    Text(
        text = stringResource(text),
        style = ClipraTheme.typography.caption,
        color = ClipraTheme.colors.textTertiary,
    )
}

@Composable
private fun restoreLine(state: SettingsUiState): String? = when {
    state.restoring -> stringResource(R.string.settings_restoring)

    state.restore == RestoreResult.Owned ->
        stringResource(MonetizationR.string.monetization_pro_restored)

    state.restore == RestoreResult.NotOwned ->
        stringResource(MonetizationR.string.monetization_pro_nothing_to_restore)

    state.restore == RestoreResult.Unavailable ->
        stringResource(MonetizationR.string.monetization_pro_unavailable)

    else -> null
}

private val CacheNote = R.string.settings_cache_note
private val ClearBody = R.string.settings_clear_body

private fun VideoCodec.label(): String = when (this) {
    VideoCodec.H264 -> "H.264"
    VideoCodec.HEVC -> "HEVC"
    VideoCodec.AV1 -> "AV1"
}
