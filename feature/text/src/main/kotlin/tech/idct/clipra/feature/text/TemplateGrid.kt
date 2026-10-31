package tech.idct.clipra.feature.text

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.engine.render.TextRenderer
import tech.idct.clipra.core.model.TextColors
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.TextTemplate
import tech.idct.clipra.core.model.TextTemplates
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign

/**
 * Templates (#34) as the tablet Text board shows them: the built-in looks and the user's saved
 * ones as tiles drawn in their own look by the renderer that draws the clip. A tap gives the clip
 * that look and keeps its words; "Save as template" keeps the clip's look for every project; a
 * long press on a saved one deletes it.
 */
@Composable
internal fun TemplateGrid(
    spec: TextSpec?,
    templates: List<TextTemplate>,
    onTemplate: (TextTemplate) -> Unit,
    onSave: (() -> Unit)?,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    val samples = templateSamples()
    // The last place is Save as template, when there is a look to save.
    val choices: List<TextTemplate?> = if (onSave != null) templates + null else templates
    BoxWithConstraints(Modifier.testTag("text-templates")) {
        // Drawn in the shape the tiles have here, so no tile is cut at its sides.
        val tileWidth = (maxWidth - 8.dp * (TEMPLATE_COLUMNS - 1)) / TEMPLATE_COLUMNS
        val tiles = remember(templates, samples, tileWidth) {
            val width = (TILE_HEIGHT * tileWidth.value / TILE_DP).roundToInt()
                .coerceAtLeast(TILE_HEIGHT)
            templates.associate { template ->
                val look = template.spec
                template.id to TextRenderer.render(
                    context,
                    look.copy(
                        text = samples[template.id] ?: look.text,
                        size = (look.size * TILE_SCALE).coerceIn(TILE_TEXT_MIN, TILE_TEXT_MAX),
                        background = look.background ?: TextColors.BLACK,
                    ),
                    card = true,
                    width,
                    TILE_HEIGHT,
                ).asImageBitmap()
            }
        }
        TemplateTiles(spec, choices, tiles, onTemplate, onSave, onDelete)
    }
}

@Composable
private fun TemplateTiles(
    spec: TextSpec?,
    choices: List<TextTemplate?>,
    tiles: Map<String, ImageBitmap>,
    onTemplate: (TextTemplate) -> Unit,
    onSave: (() -> Unit)?,
    onDelete: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.text_templates),
            style = ClipraTheme.typography.sectionLabel,
            color = ClipraTheme.colors.textSecondary,
        )
        choices.chunked(TEMPLATE_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { template ->
                    val tile = Modifier.weight(1f)
                    if (template == null) {
                        SaveTile(onSave ?: {}, tile.testTag("save-template"))
                    } else {
                        val saved = template.id.startsWith(TextTemplates.SAVED)
                        TemplateTile(
                            name = TemplateSample.of(template.id)?.let { stringResource(it.title) }
                                ?: template.spec.text.lineSequence().first(),
                            tile = tiles.getValue(template.id),
                            selected = spec != null && sameLook(template.spec, spec),
                            onClick = { onTemplate(template) },
                            onDelete = if (saved) ({ onDelete(template.id) }) else null,
                            modifier = tile.testTag("template:${template.id}"),
                        )
                    }
                }
                repeat(TEMPLATE_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Whether [spec] has [template]'s look, whatever its words, card colour and effect: the effect
 * is picked on its own, and the template stays outlined (TabletText board).
 */
private fun sameLook(template: TextSpec, spec: TextSpec): Boolean =
    template.font == spec.font && template.weight == spec.weight &&
        template.size == spec.size && template.color == spec.color &&
        template.align == spec.align

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TemplateTile(
    name: String,
    tile: ImageBitmap,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val shape = ClipraTheme.shapes.button
    Column(
        modifier = modifier
            .clip(shape)
            .border(2.dp, if (selected) colors.accent else colors.border, shape)
            .combinedClickable(
                role = Role.Button,
                onLongClickLabel = onDelete?.let {
                    stringResource(R.string.text_template_delete)
                },
                onLongClick = onDelete,
                onClick = onClick,
            )
            .semantics { this.selected = selected },
    ) {
        Image(
            bitmap = tile,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(TILE_DP.dp),
        )
        TileName(name, if (selected) colors.accent else colors.textSecondary)
    }
}

@Composable
private fun SaveTile(onSave: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ClipraTheme.colors
    val shape = ClipraTheme.shapes.button
    Column(
        modifier = modifier
            .clip(shape)
            .border(2.dp, colors.border, shape)
            .combinedClickable(role = Role.Button, onClick = onSave),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TILE_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            ClipraIcon(
                icon = ClipraIcons.Add,
                contentDescription = null,
                tint = colors.textSecondary,
            )
        }
        TileName(stringResource(R.string.text_template_save), colors.textSecondary)
    }
}

@Composable
private fun TileName(name: String, color: Color) {
    Text(
        text = name,
        style = ClipraTheme.typography.caption,
        color = color,
        textAlign = ComposeTextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(ClipraTheme.colors.surface)
            .padding(vertical = 4.dp, horizontal = 4.dp),
    )
}

/** The words each built-in template's tile shows, by template id, in the user's language. */
@Composable
internal fun templateSamples(): Map<String, String> =
    TemplateSample.entries.associate { it.id to stringResource(it.sample) }

/** The built-in templates' names and the words their tiles show, in the user's language. */
private enum class TemplateSample(val id: String, val title: Int, val sample: Int) {
    Card("card", R.string.text_template_card, R.string.text_template_card_sample),
    Title("title", R.string.text_template_title, R.string.text_template_title_sample),
    LowerThird(
        "lower_third",
        R.string.text_template_lower_third,
        R.string.text_template_lower_third_sample,
    ),
    Subtitle("subtitle", R.string.text_template_subtitle, R.string.text_template_subtitle_sample),
    Callout("callout", R.string.text_template_callout, R.string.text_template_callout_sample),
    EndCredits(
        "end_credits",
        R.string.text_template_end_credits,
        R.string.text_template_end_credits_sample,
    ),
    ;

    companion object {
        fun of(id: String): TemplateSample? = entries.firstOrNull { it.id == id }
    }
}

private const val TEMPLATE_COLUMNS = 3

// Tiles are 46 dp high as in the design, drawn at this size in pixels; the words keep their
// sizes relative to each other, within what fits a tile.
private const val TILE_DP = 46
private const val TILE_HEIGHT = 92
private const val TILE_SCALE = 3.6f
private const val TILE_TEXT_MIN = 0.18f
private const val TILE_TEXT_MAX = 0.36f
