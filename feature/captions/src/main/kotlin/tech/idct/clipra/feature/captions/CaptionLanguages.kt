package tech.idct.clipra.feature.captions

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraChip
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens
import java.util.Locale

/**
 * The captions' languages (#47): "EN · original", each translation ("PL"), and, with
 * [withTranslate], a Translate chip, as the TabletCaptions board shows.
 */
@Composable
internal fun LanguageChips(
    state: CaptionsEditUiState,
    actions: CaptionsEditActions,
    withTranslate: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val spoken = state.spokenLanguage
        ClipraChip(
            label = if (spoken != null) {
                stringResource(R.string.captions_language_original, code(spoken))
            } else {
                stringResource(R.string.captions_language_heard)
            },
            selected = state.shownLanguage.isEmpty(),
            onClick = { actions.onShowLanguage("") },
            modifier = Modifier.testTag("captions-language:"),
        )
        state.translations.forEach { language ->
            ClipraChip(
                label = code(language),
                selected = state.shownLanguage == language,
                onClick = { actions.onShowLanguage(language) },
                modifier = Modifier.testTag("captions-language:$language"),
            )
        }
        if (withTranslate) {
            ClipraChip(
                label = stringResource(R.string.captions_translate_short),
                selected = false,
                onClick = actions.onTranslate,
                role = Role.Button,
                leading = {
                    Icon(
                        painter = painterResource(ClipraIcons.Translate),
                        contentDescription = null,
                        tint = ClipraTheme.colors.textPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                },
                modifier = Modifier.testTag("captions-translate"),
            )
        }
    }
}

/**
 * Translate offline (#47): the languages the captions can go into, each ready on the phone or
 * with what it downloads first; when no transcript says what the captions are in, that first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TranslateDialog(sheet: TranslateSheet, actions: CaptionsEditActions) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val locale = LocalConfiguration.current.locales[0]
    AlertDialog(
        onDismissRequest = actions.onCloseTranslate,
        title = { Text(stringResource(R.string.captions_translate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.captions_translate_body),
                    style = type.caption,
                    color = colors.textSecondary,
                )
                val from = sheet.from
                if (from == null) {
                    Text(
                        text = stringResource(R.string.captions_translate_spoken),
                        style = type.sectionLabel,
                        color = colors.textSecondary,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sheet.sources.forEach { language ->
                            ClipraChip(
                                label = name(language, locale),
                                selected = false,
                                onClick = { actions.onPickSource(language) },
                                modifier = Modifier.testTag("translate-source:$language"),
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.captions_translate_from, name(from, locale)),
                        style = type.sectionLabel,
                        color = colors.textSecondary,
                    )
                    sheet.targets.forEach { target ->
                        TargetRow(target, sheet.busy, locale) {
                            actions.onTranslateTo(target.language)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = actions.onCloseTranslate,
                modifier = Modifier.testTag("translate-cancel"),
            ) {
                Text(stringResource(R.string.captions_cancel))
            }
        },
    )
}

/** A language to translate into: its name, and what it still needs or what it is doing. */
@Composable
private fun TargetRow(
    target: TranslateTarget,
    busy: TranslateBusy?,
    locale: Locale,
    onClick: () -> Unit,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val context = LocalContext.current
    val detail = when {
        busy?.language == target.language -> stringResource(
            when (busy.step) {
                TranslateBusy.Step.Downloading -> R.string.captions_translate_downloading
                TranslateBusy.Step.WaitingForWifi -> R.string.captions_translate_waiting_wifi
                TranslateBusy.Step.Translating -> R.string.captions_translating
            },
        )

        target.downloadBytes > 0 -> stringResource(
            R.string.captions_translate_download,
            Formatter.formatShortFileSize(context, target.downloadBytes),
        )

        else -> stringResource(R.string.captions_translate_ready)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ClipraDimens.TouchTarget)
            .clip(ClipraTheme.shapes.button)
            .clickable(enabled = busy == null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp)
            .testTag("translate-to:${target.language}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name(target.language, locale),
            style = type.bodyStrong,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = detail,
            style = type.caption,
            color = if (busy?.language == target.language) colors.accent else colors.textSecondary,
        )
    }
}

/** "EN": a language code as the chips show it. */
private fun code(language: String) = language.uppercase(Locale.ROOT)

/** "English", "Polish": a language's name in [locale]. */
internal fun name(language: String, locale: Locale): String =
    Locale.forLanguageTag(language).getDisplayLanguage(locale)
        .replaceFirstChar { it.titlecase(locale) }
