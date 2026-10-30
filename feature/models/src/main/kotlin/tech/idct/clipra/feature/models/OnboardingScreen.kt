package tech.idct.clipra.feature.models

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.TextAction
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.models.AiModel
import tech.idct.clipra.core.models.ModelCatalog

/** What onboarding can do. */
class OnboardingActions(
    val onToggle: (AiModel) -> Unit,
    val onStart: () -> Unit,
    val onSkip: () -> Unit,
)

/**
 * Onboarding (Onboarding board): no account, no upload, no watermark; three on-device
 * highlights; the models to download with their real sizes and the total; Start editing and
 * Skip downloads for now.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Onboarding"),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Image(
                    painter = painterResource(ClipraIcons.Logo),
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                )
                Text(
                    text = stringResource(R.string.models_onboarding_brand),
                    style = type.brand,
                    color = colors.textPrimary,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = buildAnnotatedString {
                        append(stringResource(R.string.models_onboarding_promise))
                        append('\n')
                        withStyle(SpanStyle(color = colors.accent)) {
                            append(stringResource(R.string.models_onboarding_no_watermark))
                        }
                    },
                    style = type.heroTitle,
                    color = colors.textPrimary,
                )
                Text(
                    text = stringResource(R.string.models_onboarding_body),
                    style = type.bodyStrong.copy(fontWeight = FontWeight.Normal),
                    color = colors.textSecondaryAlt,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Highlight(
                    ClipraIcons.Captions,
                    colors.feature.captions,
                    R.string.models_onboarding_captions,
                )
                Highlight(
                    ClipraIcons.Microphone,
                    colors.clip.voiceWave,
                    R.string.models_onboarding_voice,
                )
                Highlight(ClipraIcons.Face, colors.onDeviceText, R.string.models_onboarding_faces)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.models_onboarding_download_now),
                    style = type.sectionLabel,
                    color = colors.textSecondary,
                )
                state.choices.forEach { choice ->
                    ChoiceRow(choice, onToggle = { actions.onToggle(choice.model) })
                }
                Text(
                    text = totalLine(state),
                    style = type.caption,
                    color = colors.textTertiary,
                    modifier = Modifier.testTag("onboarding-total"),
                )
            }
        }
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(
                text = stringResource(R.string.models_onboarding_start),
                onClick = actions.onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding-start"),
            )
            TextAction(
                text = stringResource(R.string.models_onboarding_skip),
                onClick = actions.onSkip,
                color = colors.textSecondaryAlt,
                modifier = Modifier.testTag("onboarding-skip"),
            )
        }
    }
}

@Composable
private fun Highlight(@DrawableRes icon: Int, tint: Color, @StringRes text: Int) {
    val colors = ClipraTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            ClipraIcon(icon = icon, contentDescription = null, tint = tint, size = 20.dp)
        }
        Text(
            text = stringResource(text),
            style = ClipraTheme.typography.body,
            color = colors.textPrimary,
        )
    }
}

/** A model to download: a checkbox row, warm when selected, with the download's real size. */
@Composable
private fun ChoiceRow(choice: DownloadChoice, onToggle: () -> Unit) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val context = LocalContext.current
    val shape = RoundedCornerShape(14.dp)
    val on = choice.selected || choice.installed
    val (title, detail) = labels(choice.model)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(shape)
            .background(if (on) colors.accentSelectedBackground else colors.surface)
            .border(BorderStroke(1.5.dp, if (on) colors.accent else colors.border), shape)
            .toggleable(
                value = on,
                enabled = !choice.installed,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("onboarding:${choice.model.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (on) colors.accent else Color.Transparent)
                .border(
                    BorderStroke(2.dp, if (on) colors.accent else colors.textDisabled),
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (on) {
                ClipraIcon(
                    icon = ClipraIcons.Check,
                    contentDescription = null,
                    tint = colors.onAccent,
                    size = 14.dp,
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = stringResource(title),
                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
            )
            Text(text = stringResource(detail), style = type.caption, color = colors.textSecondary)
        }
        Text(
            text = if (choice.installed) {
                stringResource(R.string.models_installed)
            } else {
                Formatter.formatShortFileSize(context, choice.model.download?.bytes ?: 0)
            },
            style = type.meta.copy(fontSize = type.caption.fontSize),
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun totalLine(state: OnboardingUiState): String {
    val bytes = state.downloadBytes
    if (bytes == 0L) return stringResource(R.string.models_onboarding_nothing)
    val size = Formatter.formatShortFileSize(LocalContext.current, bytes)
    val line = if (state.wifiOnly) {
        R.string.models_onboarding_total_wifi
    } else {
        R.string.models_onboarding_total
    }
    return stringResource(line, size)
}

/** The choice's title and detail, as in the design. */
private fun labels(model: AiModel): Pair<Int, Int> = when (model) {
    ModelCatalog.whisperBase ->
        R.string.models_onboarding_base to R.string.models_onboarding_base_detail

    ModelCatalog.deepFilterNet ->
        R.string.models_onboarding_cleanup to R.string.models_onboarding_cleanup_detail

    else -> R.string.models_onboarding_small to R.string.models_onboarding_small_detail
}
