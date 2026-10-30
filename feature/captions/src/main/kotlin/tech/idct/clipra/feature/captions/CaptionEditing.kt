package tech.idct.clipra.feature.captions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.IconAction
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.TextAction
import tech.idct.clipra.core.designsystem.icon.ClipraIcons

/** A word picked to edit (#46): its caption and where it is in it. */
data class WordPick(val clipId: String, val index: Int)

/**
 * Editing a word (#46, "Tap a word to edit it" on TabletCaptions), in place of the bottom bar:
 * retype it (empty takes it out), split the caption there, or merge it with the next caption.
 */
@Composable
internal fun WordBar(
    word: String,
    canSplit: Boolean,
    canMerge: Boolean,
    onSave: (String) -> Unit,
    onSplit: () -> Unit,
    onMerge: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = ClipraTheme.colors
    var value by remember(word) {
        mutableStateOf(TextFieldValue(word, TextRange(0, word.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(word) { runCatching { focus.requestFocus() } }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 12.dp)
            .testTag("word-bar"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.captions_edit_word),
                style = ClipraTheme.typography.sectionLabel,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconAction(
                icon = ClipraIcons.Close,
                contentDescription = stringResource(R.string.captions_cancel),
                onClick = onClose,
                modifier = Modifier.testTag("word-close"),
            )
        }
        Row(
            modifier = Modifier.padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Field(
                label = stringResource(R.string.captions_word),
                value = value,
                onValue = { value = it },
                onDone = { onSave(value.text) },
                modifier = Modifier
                    .focusRequester(focus)
                    .testTag("word-input"),
                rowModifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = stringResource(R.string.captions_save),
                onClick = { onSave(value.text) },
                compact = true,
                modifier = Modifier.testTag("word-save"),
            )
        }
        Row {
            if (canSplit) {
                TextAction(
                    text = stringResource(R.string.captions_split_here),
                    onClick = onSplit,
                    modifier = Modifier.testTag("word-split"),
                )
            }
            if (canMerge) {
                TextAction(
                    text = stringResource(R.string.captions_merge_next),
                    onClick = onMerge,
                    modifier = Modifier.testTag("word-merge"),
                )
            }
        }
    }
}

/** Search and replace in every caption (#46), with how many places it finds. */
@Composable
internal fun SearchBar(
    find: String,
    onFind: (String) -> Unit,
    matches: Int,
    onReplaceAll: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = ClipraTheme.colors
    var findValue by remember { mutableStateOf(TextFieldValue(find, TextRange(find.length))) }
    var replacement by remember { mutableStateOf(TextFieldValue("")) }
    Column(
        modifier = Modifier
            .clip(ClipraTheme.shapes.card)
            .background(colors.surface)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)
            .testTag("captions-search"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (find.isBlank()) {
                    stringResource(R.string.captions_search)
                } else {
                    pluralStringResource(R.plurals.captions_matches, matches, matches)
                },
                style = ClipraTheme.typography.sectionLabel,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            IconAction(
                icon = ClipraIcons.Close,
                contentDescription = stringResource(R.string.captions_search_close),
                onClick = onClose,
                modifier = Modifier.testTag("search-close"),
            )
        }
        Column(
            modifier = Modifier.padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Field(
                label = stringResource(R.string.captions_find),
                value = findValue,
                onValue = {
                    findValue = it
                    onFind(it.text)
                },
                modifier = Modifier.testTag("search-find"),
            )
            Field(
                label = stringResource(R.string.captions_replace_with),
                value = replacement,
                onValue = { replacement = it },
                modifier = Modifier.testTag("search-replace"),
            )
            PrimaryButton(
                text = stringResource(R.string.captions_replace_all),
                onClick = { onReplaceAll(replacement.text) },
                enabled = matches > 0,
                compact = true,
                modifier = Modifier.testTag("search-replace-all"),
            )
        }
    }
}

/** A labelled one-line field, as on the Text board. */
@Composable
private fun Field(
    label: String,
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
    rowModifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Row(
        modifier = rowModifier
            .height(48.dp)
            .clip(ClipraTheme.shapes.button)
            .background(colors.surfaceSunken)
            .border(1.5.dp, colors.border, ClipraTheme.shapes.button)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = label, style = type.caption, color = colors.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = type.body.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(
                imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = modifier
                .weight(1f)
                .semantics { contentDescription = label },
        )
    }
}
