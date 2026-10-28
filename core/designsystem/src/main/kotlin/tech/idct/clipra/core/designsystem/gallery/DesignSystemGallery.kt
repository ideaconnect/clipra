package tech.idct.clipra.core.designsystem.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.R
import tech.idct.clipra.core.designsystem.component.ButtonsSample
import tech.idct.clipra.core.designsystem.component.CardsSample
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.NavigationSample
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.SelectionSample
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens

/**
 * Debug builds only: every colour, text style, component and icon of the design system on one
 * scrolling page, to compare against the design boards.
 */
@Composable
fun DesignSystemGallery(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ClipraTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding(),
    ) {
        ScreenHeader(
            title = stringResource(R.string.designsystem_gallery_title),
            onNavigate = onBack,
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("gallery"),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { Section("Colours") { ColorsSample() } }
            item { Section("Type") { TypeSample() } }
            item { Section("Buttons") { ButtonsSample() } }
            item { Section("Selection") { SelectionSample() } }
            item { Section("Cards") { CardsSample() } }
            item { Section("Navigation") { NavigationSample() } }
            item { Section("Icons") { IconsSample() } }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = title,
            style = ClipraTheme.typography.sectionLabel,
            color = ClipraTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = ClipraDimens.ScreenPadding),
        )
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorsSample() {
    val c = ClipraTheme.colors
    val swatches = listOf(
        "background" to c.background, "surface" to c.surface, "surfaceRaised" to c.surfaceRaised,
        "border" to c.border, "textPrimary" to c.textPrimary, "textSecondary" to c.textSecondary,
        "accent" to c.accent, "onAccent" to c.onAccent,
        "accentSelected" to c.accentSelectedBackground,
        "onDevice" to c.onDeviceText, "danger" to c.danger, "proCard" to c.proCardBackground,
        "clip.text" to c.clip.text, "clip.photo" to c.clip.photo, "clip.caption" to c.clip.caption,
        "clip.music" to c.clip.music, "clip.voice" to c.clip.voice, "musicWave" to c.clip.musicWave,
    )
    FlowRow(
        modifier = Modifier.padding(ClipraDimens.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        swatches.forEach { (name, color) -> Swatch(name, color) }
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    Column(modifier = Modifier.width(76.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(76.dp, 40.dp)
                .clip(ClipraTheme.shapes.thumb)
                .background(color)
                .border(1.dp, ClipraTheme.colors.border, ClipraTheme.shapes.thumb),
        )
        Text(
            text = name,
            style = ClipraTheme.typography.clipLabel,
            color = ClipraTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun TypeSample() {
    val t = ClipraTheme.typography
    val c = ClipraTheme.colors
    Column(
        modifier = Modifier.padding(ClipraDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("New project", style = t.heroTitle, color = c.textPrimary)
        Text("Clipra", style = t.brand, color = c.textPrimary)
        Text("Export", style = t.screenTitle, color = c.textPrimary)
        Text("Lisbon weekend", style = t.headerTitle, color = c.textPrimary)
        Text("Aspect ratio", style = t.sectionLabel, color = c.textSecondary)
        Text("No account · no upload · no watermark", style = t.body, color = c.textPrimary)
        Text("Edited 2 h ago", style = t.caption, color = c.textSecondary)
        Text("00:07.12 / 00:42.00 · 1080×1920", style = t.meta, color = c.textSecondary)
        Text("sunset.mp4", style = t.clipLabel, color = c.textPrimary)
        Text("67%", style = t.numberLarge, color = c.textPrimary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconsSample() {
    FlowRow(
        modifier = Modifier.padding(ClipraDimens.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ClipraIcons.all.forEach { (name, icon) ->
            Column(
                modifier = Modifier.width(56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (icon == ClipraIcons.Logo) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(icon),
                        contentDescription = null,
                        modifier = Modifier.size(ClipraDimens.IconSize),
                    )
                } else {
                    ClipraIcon(
                        icon = icon,
                        contentDescription = null,
                        tint = ClipraTheme.colors.textPrimary,
                    )
                }
                Text(
                    text = name,
                    style = ClipraTheme.typography.clipLabel,
                    color = ClipraTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun GalleryPreview() {
    ClipraTheme {
        DesignSystemGallery(onBack = {})
    }
}
