package tech.idct.clipra.feature.export

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tech.idct.clipra.core.data.project.ProjectRepository
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraCard
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.OnDeviceCard
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.component.TextAction
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.engine.export.ExportEvent
import tech.idct.clipra.core.engine.export.ExportFailure
import tech.idct.clipra.core.engine.export.ExportManager
import tech.idct.clipra.core.engine.export.ExportState
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.monetization.ads.AdGate
import tech.idct.clipra.monetization.ads.AdSlot
import tech.idct.clipra.monetization.ads.ExportAds
import javax.inject.Inject

@HiltViewModel
class ExportProgressViewModel @Inject constructor(
    private val exports: ExportManager,
    projects: ProjectRepository,
    private val exportAds: ExportAds,
    @param:ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
) : ViewModel() {
    val state: StateFlow<ExportState> = exports.state

    private val startAdDue = MutableStateFlow(false)

    /** The render started here, so Ad 1 is due once this screen is in front. */
    val startAd: StateFlow<Boolean> = startAdDue.asStateFlow()

    private val finishAdState = MutableStateFlow<Boolean?>(null)

    /** For a finished export: whether See your video plays Ad 2 first; null until known. */
    val finishAd: StateFlow<Boolean?> = finishAdState.asStateFlow()

    private var seeing = false

    init {
        // Opened after the export finished, from the notification: the video, without an ad.
        if (exports.state.value is ExportState.Done) finishAdState.value = false
        viewModelScope.launch {
            exports.events.collect { event ->
                val watching = exports.progressScreenVisible.value
                when (event) {
                    ExportEvent.Started -> Unit

                    ExportEvent.Finished -> {
                        exportAds.exportEnded(succeeded = true, watching = watching)
                        finishAdState.value = exportAds.finishAdExpected()
                    }

                    ExportEvent.Failed, ExportEvent.Cancelled ->
                        exportAds.exportEnded(succeeded = false, watching = watching)
                }
            }
        }
        // Opened from Export with a project: start it. Opened from the notification: just show.
        val projectId = savedState.get<String>("projectId")
        if (projectId != null && exports.state.value !is ExportState.Running) {
            viewModelScope.launch {
                val project = projects.load(projectId) ?: return@launch
                exportAds.exportStarted()
                exports.start(
                    project,
                    project.exportSettings ?: ExportSettings.forProject(project.format),
                )
                ExportService.start(context)
                startAdDue.value = true
            }
        }
    }

    /**
     * Ad 1, over the running export (docs/product/monetization.md, section 1): the render is
     * already going and carries on behind it. Skipped unless the rules allow it and it is ready
     * within 2 s.
     */
    fun showStartAd(activity: Activity) {
        if (!startAdDue.value) return
        startAdDue.value = false
        viewModelScope.launch {
            // The screen is in front a moment after navigation shows it; never wait long.
            withTimeoutOrNull(AdGate.LOAD_TIMEOUT_MS) {
                exports.progressScreenVisible.first { it }
            }
            exportAds.show(AdSlot.Start, activity) { exports.progressScreenVisible.value }
        }
    }

    /** See your video: Ad 2 first when the rules allow it, then [then] opens the saved video. */
    fun seeVideo(activity: Activity, then: () -> Unit) {
        if (seeing) return
        seeing = true
        viewModelScope.launch {
            exportAds.show(AdSlot.Finish, activity) { exports.progressScreenVisible.value }
            then()
        }
    }

    fun pause() = exports.pause()

    fun resume() = exports.resume()

    fun cancel() = exports.cancel()

    fun setVisible(visible: Boolean) = exports.setProgressScreenVisible(visible)
}

/**
 * Exporting (ExportProgress board): percent, frame count and time left, the progress bar, the
 * files being saved, Pause or Resume, Keep editing and Cancel export. [finished] is the
 * ExportProgress--finished state, whose See your video plays Ad 2 first.
 */
@Composable
fun ExportProgressScreen(
    state: ExportState,
    onKeepEditing: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    finished: Boolean = false,
    onSeeVideo: () -> Unit = {},
) {
    val done = (state as? ExportState.Done)?.takeIf { finished }
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:ExportProgress"),
    ) {
        ScreenHeader(
            title = stringResource(
                if (done !=
                    null
                ) {
                    R.string.export_finished_title
                } else {
                    R.string.export_progress_title
                },
            ),
            navigation = HeaderNavigation.Close,
            onNavigate = onKeepEditing,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                state is ExportState.Running -> Running(state)
                state is ExportState.Failed -> Failure(state.failure)
                done != null -> Finished(done)
            }
        }
        if (done != null) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PrimaryButton(
                    text = stringResource(R.string.export_see_video),
                    onClick = onSeeVideo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("see-video"),
                )
                Text(
                    text = stringResource(R.string.export_ad_note),
                    style = type.caption,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        } else if (state is ExportState.Running) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PrimaryButton(
                    text = stringResource(R.string.export_keep_editing),
                    onClick = onKeepEditing,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = stringResource(
                        if (state.paused) R.string.export_resume else R.string.export_pause,
                    ),
                    onClick = if (state.paused) onResume else onPause,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .testTag("pause"),
                )
            }
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextAction(
                    text = stringResource(R.string.export_cancel),
                    onClick = onCancel,
                    color = colors.danger,
                    modifier = Modifier.testTag("cancel"),
                )
            }
        } else {
            PrimaryButton(
                text = stringResource(R.string.export_back_to_editing),
                onClick = onKeepEditing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            )
        }
    }
}

@Composable
private fun Running(state: ExportState.Running) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val percent = (state.progress * 100).toInt()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "$percent%",
            style = type.numberLarge,
            color = colors.textPrimary,
            modifier = Modifier.testTag("percent"),
        )
        Text(
            text = stringResource(R.string.export_frames, state.framesDone, state.framesTotal),
            style = type.meta.copy(fontSize = type.body.fontSize),
            color = colors.textSecondary,
        )
        val left = state.secondsLeft
        Text(
            text = when {
                state.paused -> stringResource(R.string.export_paused)
                left == null -> stringResource(R.string.export_starting)
                else -> stringResource(R.string.export_seconds_left, left)
            },
            style = type.meta.copy(fontSize = type.body.fontSize),
            color = colors.textSecondary,
        )
    }
    ProgressBar(state.progress, if (state.paused) colors.textTertiary else colors.accent)
    Text(
        text = stringResource(
            R.string.export_settings_line,
            state.settings.codecLabel(),
            state.width,
            state.height,
            state.settings.frameRate.fps,
        ),
        style = type.meta.copy(fontSize = type.chip.fontSize),
        color = colors.textSecondary,
    )
    OnDeviceCard(
        title = stringResource(R.string.export_leave_title),
        subtitle = stringResource(R.string.export_leave_body),
        icon = ClipraIcons.Notification,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.export_saving),
        style = type.sectionLabel,
        color = colors.textSecondary,
    )
    ClipraCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.fileName,
                style = type.bodyStrong,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(text = "$percent%", style = type.chip, color = colors.accent)
        }
    }
}

@Composable
private fun Finished(state: ExportState.Done) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val frames = (state.durationUs * state.settings.frameRate.fps / 1_000_000).toInt()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "100%",
            style = type.numberLarge,
            color = colors.textPrimary,
            modifier = Modifier.testTag("percent"),
        )
        Text(
            text = stringResource(R.string.export_frames, frames, frames),
            style = type.meta.copy(fontSize = type.body.fontSize),
            color = colors.textSecondary,
        )
        Text(
            text = stringResource(R.string.export_saved_to_gallery),
            style = type.meta.copy(fontSize = type.body.fontSize),
            color = colors.textSecondary,
        )
    }
    ProgressBar(1f, colors.onDeviceText)
    Text(
        text = stringResource(
            R.string.export_settings_line,
            state.settings.codecLabel(),
            state.width,
            state.height,
            state.settings.frameRate.fps,
        ),
        style = type.meta.copy(fontSize = type.chip.fontSize),
        color = colors.textSecondary,
    )
    OnDeviceCard(
        title = stringResource(R.string.export_leave_title),
        subtitle = stringResource(R.string.export_leave_body),
        icon = ClipraIcons.Notification,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.export_saving),
        style = type.sectionLabel,
        color = colors.textSecondary,
    )
    ClipraCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.fileName,
                style = type.bodyStrong,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.export_file_done),
                style = type.chip,
                color = colors.onDeviceText,
            )
        }
    }
}

@Composable
private fun ProgressBar(progress: Float, color: androidx.compose.ui.graphics.Color) {
    val colors = ClipraTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(colors.surfaceRaised),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
private fun Failure(failure: ExportFailure) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Spacer(Modifier.size(8.dp))
    Text(
        text = stringResource(R.string.export_failed_title),
        style = type.screenTitle,
        color = colors.danger,
    )
    Text(
        text = stringResource(
            when (failure) {
                ExportFailure.Unsupported -> R.string.export_failed_unsupported
                ExportFailure.MissingMedia -> R.string.export_failed_media
                ExportFailure.Storage -> R.string.export_failed_storage
                ExportFailure.Other -> R.string.export_failed_other
            },
        ),
        style = type.body,
        color = colors.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .testTag("failure"),
    )
}

internal fun ExportSettings.codecLabel(): String = when (codec) {
    tech.idct.clipra.core.model.export.VideoCodec.H264 -> "H.264"
    tech.idct.clipra.core.model.export.VideoCodec.HEVC -> "HEVC"
    tech.idct.clipra.core.model.export.VideoCodec.AV1 -> "AV1"
}
