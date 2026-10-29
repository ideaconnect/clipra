package tech.idct.clipra.feature.effects.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.theme.ClipraDimens

// The pieces Filters and Transitions share: the preview, category pills and thumbnail tiles.

/** Five tiles a row. */
internal const val PICKER_COLUMNS = 5

/** Thumbnail height; the view models render them at this size. */
internal val TILE_HEIGHT = 48.dp

/** The 240 dp preview, letterboxed at the project's ratio, with [overlay] on top. */
@Composable
internal fun PickerPreview(
    ratio: Float,
    preview: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clipToBounds()
            .background(ClipraTheme.colors.ad.background),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                .testTag("preview"),
        ) {
            preview()
        }
        overlay()
    }
}

/** A category pill with its count, as on Filters and Transitions. */
@Composable
internal fun PickerCategory(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val content = if (selected) colors.background else colors.textPrimary
    Row(
        modifier = modifier
            .heightIn(min = ClipraDimens.TouchTarget)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(ClipraTheme.shapes.chip)
                .background(if (selected) colors.textPrimary else colors.surface)
                .height(ClipraDimens.ChipHeight)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = label, style = type.chip, color = content)
            Text(text = count.toString(), style = type.meta, color = content.copy(alpha = 0.7f))
        }
    }
}

/** A choice in the five-column grid: a live thumbnail and its name. */
@Composable
internal fun PickerTile(
    name: String,
    thumbnail: Bitmap?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val shape = ClipraTheme.shapes.segment
    Column(
        modifier = modifier.selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = onClick,
        ),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TILE_HEIGHT)
                .border(2.dp, if (selected) colors.accent else Color.Transparent, shape)
                .padding(if (selected) 2.dp else 0.dp)
                .clip(shape)
                .background(colors.surfaceRaised),
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // Longer translations shrink to fit rather than being cut.
        val label = ClipraTheme.typography.toolLabel
        BasicText(
            text = name,
            style = label.copy(
                color = if (selected) colors.accent else colors.textSecondaryAlt,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = label.fontSize),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
