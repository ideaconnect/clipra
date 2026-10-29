package tech.idct.clipra.feature.models

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.SwitchRow
import tech.idct.clipra.core.designsystem.component.TextAction
import tech.idct.clipra.core.designsystem.theme.ClipraColors
import tech.idct.clipra.core.designsystem.theme.ClipraDimens
import tech.idct.clipra.core.models.AiModel
import tech.idct.clipra.core.models.DownloadFailure
import tech.idct.clipra.core.models.ModelCatalog
import tech.idct.clipra.core.models.ModelGroup
import tech.idct.clipra.core.models.ModelState
import tech.idct.clipra.core.models.ModelStatus

/** What the AI models screen can do. */
class ModelsActions(
    val onBack: () -> Unit,
    val onDownload: (AiModel) -> Unit,
    val onCancel: (AiModel) -> Unit,
    val onRemove: (AiModel) -> Unit,
    val onWifiOnly: (Boolean) -> Unit,
    val onUseAccelerator: (Boolean) -> Unit,
)

/**
 * The AI models board (#43): storage on this phone, the download settings and every model with
 * its real size and state. Get downloads from the official source, Installed removes after a
 * confirmation. Translation pairs join with caption translation.
 */
@Composable
fun ModelsScreen(state: ModelsUiState, actions: ModelsActions, modifier: Modifier = Modifier) {
    val colors = ClipraTheme.colors
    var removing by remember { mutableStateOf<AiModel?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Models"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.models_title_models),
            navigation = HeaderNavigation.Back,
            onNavigate = actions.onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StorageCard(state.models)
            Column {
                SwitchRow(
                    title = stringResource(R.string.models_wifi_only),
                    checked = state.wifiOnly,
                    onCheckedChange = actions.onWifiOnly,
                    modifier = Modifier.testTag("models-wifi-only"),
                )
                SwitchRow(
                    title = stringResource(R.string.models_accelerator),
                    checked = state.useAccelerator,
                    onCheckedChange = actions.onUseAccelerator,
                    modifier = Modifier.testTag("models-accelerator"),
                )
            }
            GROUPS.forEach { (group, title) ->
                val models = state.models.filter { it.model.group == group }
                if (models.isEmpty()) return@forEach
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(title),
                        style = ClipraTheme.typography.sectionLabel,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    models.forEach { model ->
                        ModelRow(
                            model,
                            onDownload = { actions.onDownload(model.model) },
                            onCancel = { actions.onCancel(model.model) },
                            onRemove = { removing = model.model },
                        )
                    }
                }
            }
        }
    }
    removing?.let { model ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.models_remove_title, model.name)) },
            text = { Text(stringResource(R.string.models_remove_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onRemove(model)
                        removing = null
                    },
                    modifier = Modifier.testTag("models-remove-confirm"),
                ) {
                    Text(stringResource(R.string.models_remove), color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) {
                    Text(stringResource(R.string.models_cancel))
                }
            },
        )
    }
}

/** "157 MB speech and voice models on this phone", with a bar of what takes the space. */
@Composable
private fun StorageCard(models: List<ModelState>) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val shape = ClipraTheme.shapes.card
    val installed = models.mapNotNull { state ->
        (state.status as? ModelStatus.Installed)?.let { state.model to it.bytes }
    }
    val total = installed.sumOf { it.second }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.onDeviceCardBackground)
            .border(1.dp, colors.onDeviceCardBorder, shape)
            .padding(12.dp)
            .testTag("models-storage"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = fileSize(total),
                style = type.cardTitle,
                color = colors.textPrimary,
            )
            Text(
                text = stringResource(R.string.models_storage),
                style = type.caption,
                color = colors.onDeviceCardText,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(colors.onDeviceCardBorder),
        ) {
            installed.forEach { (model, bytes) ->
                Box(
                    Modifier
                        .weight(bytes.coerceAtLeast(1).toFloat())
                        .fillMaxHeight()
                        .background(colors.modelColor(model)),
                )
            }
        }
        Text(
            text = stringResource(R.string.models_offline),
            style = type.caption,
            color = colors.onDeviceCardText,
        )
    }
}

/** A model: name, size and Default badge, and what can be done with it. */
@Composable
private fun ModelRow(
    state: ModelState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val model = state.model
    val status = state.status
    val downloadBytes = model.download?.bytes ?: 0
    val detail = when (status) {
        ModelStatus.BuiltIn -> stringResource(R.string.models_lite)

        is ModelStatus.Installed -> fileSize(status.bytes)

        is ModelStatus.Downloading -> stringResource(
            R.string.models_progress,
            percent(status.bytes, status.total),
            fileSize(status.total),
        )

        is ModelStatus.Waiting -> stringResource(
            if (status.wifiOnly) R.string.models_waiting_wifi else R.string.models_waiting_network,
        )

        is ModelStatus.Missing -> when (status.failure) {
            DownloadFailure.Corrupt -> stringResource(R.string.models_failed_check)
            DownloadFailure.Unavailable -> stringResource(R.string.models_failed_source)
            null -> fileSize(downloadBytes)
        }
    }
    val failed = (status as? ModelStatus.Missing)?.failure != null
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ClipraTheme.shapes.button)
            .background(colors.surface)
            .testTag("model:${model.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ClipraDimens.TouchTarget)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = model.name,
                    style = type.bodyStrong,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                Text(
                    text = detail,
                    style = type.meta,
                    color = if (failed) colors.danger else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (model.isDefault) DefaultBadge()
            }
            when (status) {
                ModelStatus.BuiltIn -> Text(
                    text = stringResource(R.string.models_built_in),
                    style = type.button,
                    color = colors.onDeviceText,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )

                is ModelStatus.Installed -> {
                    val description = stringResource(R.string.models_remove_model, model.name)
                    TextAction(
                        text = stringResource(R.string.models_installed),
                        onClick = onRemove,
                        color = colors.onDeviceText,
                        modifier = Modifier.semantics { contentDescription = description },
                    )
                }

                is ModelStatus.Downloading, is ModelStatus.Waiting -> TextAction(
                    text = stringResource(R.string.models_cancel),
                    onClick = onCancel,
                    color = colors.textSecondary,
                )

                is ModelStatus.Missing -> {
                    val description = stringResource(R.string.models_download_model, model.name)
                    PrimaryButton(
                        text = stringResource(
                            if (failed) R.string.models_retry else R.string.models_get,
                        ),
                        onClick = onDownload,
                        compact = true,
                        modifier = Modifier.semantics { contentDescription = description },
                    )
                }
            }
        }
        if (status is ModelStatus.Downloading) {
            DownloadBar(status.bytes, status.total, Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun DownloadBar(bytes: Long, total: Long, modifier: Modifier) {
    val fraction = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(ClipraTheme.colors.border),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(ClipraTheme.colors.accent),
        )
    }
}

@Composable
private fun DefaultBadge() {
    Text(
        text = stringResource(R.string.models_default),
        style = ClipraTheme.typography.clipLabel,
        color = ClipraTheme.colors.accent,
        modifier = Modifier
            .clip(ClipraTheme.shapes.thumb)
            .background(ClipraTheme.colors.accentChipBackground)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Sizes as Android's storage settings show them: decimal, 1 MB = 1,000,000 bytes. */
@Composable
private fun fileSize(bytes: Long): String =
    Formatter.formatShortFileSize(LocalContext.current, bytes)

private fun percent(bytes: Long, total: Long) = if (total > 0) (bytes * 100 / total).toInt() else 0

/** Each model's colour in the storage bar, as in the design. */
private fun ClipraColors.modelColor(model: AiModel): Color = when (model) {
    ModelCatalog.whisperTiny -> clip.musicWave
    ModelCatalog.whisperBase -> onDeviceText
    ModelCatalog.whisperSmall -> feature.captions
    ModelCatalog.whisperLargeTurbo -> feature.mosaic
    else -> clip.voiceWave
}

private val GROUPS = listOf(
    ModelGroup.Captions to R.string.models_group_captions,
    ModelGroup.VoiceCleanup to R.string.models_group_voice,
)
