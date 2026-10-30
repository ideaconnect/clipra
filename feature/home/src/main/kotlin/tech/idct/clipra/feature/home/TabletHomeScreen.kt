package tech.idct.clipra.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.data.project.ProjectSummary
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.IconAction
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.Format

/** Clipra Pro as the tablet home's header offers it: owned, or its price from Google Play. */
data class ProOffer(val owned: Boolean = false, val price: String? = null)

/** How the tablet home orders recent projects. */
enum class ProjectOrder { LastEdited, Name }

/** What the tablet home's New project panel shows and does (#71). */
class NewProjectPanel(
    val format: Format,
    val onFormatChange: (Format) -> Unit,
    val onCreate: () -> Unit,
    val fourKSupported: Boolean,
    /** Creates a project in the panel's format starting with a text card, then edits it. */
    val onTextCard: () -> Unit,
    /** Creates a project in the panel's format and picks its media from the gallery. */
    val onImport: () -> Unit,
)

/**
 * The tablet home (TabletHome board, #71): brand, search, Go Pro, AI models and settings over
 * the quick actions, the on-device AI card and the recent projects, with New project in a panel
 * beside them instead of a screen of its own.
 */
@Composable
fun TabletHomeScreen(
    recent: List<ProjectSummary>?,
    nowMillis: Long,
    aiStatus: AiStatus?,
    pro: ProOffer,
    actions: HomeActions,
    projectActions: ProjectActions,
    panel: NewProjectPanel,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(ProjectOrder.LastEdited) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:TabletHome"),
    ) {
        Header(query, { query = it }, pro, actions)
        Row(modifier = Modifier.weight(1f)) {
            val shown = recent.orEmpty()
                .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                .let { list ->
                    when (order) {
                        ProjectOrder.LastEdited -> list.sortedByDescending { it.updatedAtMillis }
                        ProjectOrder.Name -> list.sortedBy { it.name.lowercase() }
                    }
                }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag("home"),
                contentPadding = PaddingValues(
                    start = 30.dp,
                    end = 30.dp,
                    top = 24.dp,
                    bottom = 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                fullWidth { QuickActionsRow(actions, panel) }
                fullWidth { AiCard(aiStatus, actions.onModels) }
                fullWidth {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) { RecentHeader(recent?.size ?: 0) }
                        OrderPicker(order) { order = it }
                    }
                }
                if (recent != null && shown.isEmpty()) {
                    fullWidth {
                        Text(
                            text = stringResource(
                                if (recent.isEmpty()) {
                                    R.string.home_recent_empty
                                } else {
                                    R.string.home_tablet_no_match
                                },
                            ),
                            style = ClipraTheme.typography.body,
                            color = colors.textSecondary,
                        )
                    }
                }
                items(shown, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        nowMillis = nowMillis,
                        onOpen = { actions.onOpenProject(project.id) },
                        actions = projectActions,
                    )
                }
            }
            NewProjectSide(
                panel = panel,
                modifier = Modifier
                    .width(PANEL_WIDTH.dp)
                    .fillMaxHeight()
                    .drawBehind {
                        drawLine(
                            colors.borderSubtle,
                            Offset(0f, 0f),
                            Offset(0f, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    },
            )
        }
    }
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

/** Brand, search, Go Pro, AI models and settings. */
@Composable
private fun Header(query: String, onQuery: (String) -> Unit, pro: ProOffer, actions: HomeActions) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .drawBehind {
                drawLine(
                    colors.borderSubtle,
                    Offset(0f, size.height),
                    Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(start = 30.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(ClipraIcons.Logo),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = stringResource(R.string.home_brand),
            style = type.brand,
            color = colors.textPrimary,
        )
        Spacer(modifier = Modifier.weight(1f))
        SearchField(query, onQuery)
        HeaderButton(
            text = when {
                pro.owned -> stringResource(R.string.home_tablet_pro_owned)
                pro.price != null -> stringResource(R.string.home_tablet_go_pro_price, pro.price)
                else -> stringResource(R.string.home_tablet_go_pro)
            },
            icon = ClipraIcons.Crown,
            accent = true,
            onClick = actions.onPro,
            tag = "go-pro",
        )
        HeaderButton(
            text = stringResource(R.string.home_tablet_models),
            icon = ClipraIcons.OnDevice,
            onClick = actions.onModels,
            tag = "ai-models",
        )
        IconAction(
            icon = ClipraIcons.Settings,
            contentDescription = stringResource(R.string.home_nav_settings),
            onClick = actions.onSettings,
            tint = colors.textSecondaryAlt,
        )
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val label = stringResource(R.string.home_tablet_search)
    Row(
        modifier = Modifier
            .width(SEARCH_WIDTH.dp)
            .height(48.dp)
            .background(colors.background, ClipraTheme.shapes.button)
            .drawBehind {
                drawRoundRect(
                    colors.border,
                    style = Stroke(1.dp.toPx()),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ClipraIcon(
            icon = ClipraIcons.Search,
            contentDescription = null,
            tint = colors.textSecondary,
            size = 18.dp,
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(text = label, style = type.body, color = colors.textTertiary)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = type.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = label }
                    .testTag("search"),
            )
        }
    }
}

@Composable
private fun HeaderButton(
    text: String,
    icon: Int,
    onClick: () -> Unit,
    tag: String,
    accent: Boolean = false,
) {
    val colors = ClipraTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(48.dp)
            .semantics { role = Role.Button }
            .testTag(tag),
        shape = ClipraTheme.shapes.button,
        color = if (accent) colors.accentChipBackground else colors.surface,
        contentColor = if (accent) colors.accent else colors.textPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ClipraIcon(icon = icon, contentDescription = null, size = 18.dp)
            Text(text = text, style = ClipraTheme.typography.chip)
        }
    }
}

/** Auto captions, Text card, Mosaic layout and Import from Gallery. */
@Composable
private fun QuickActionsRow(actions: HomeActions, panel: NewProjectPanel) {
    val feature = ClipraTheme.colors.feature
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        QuickAction(
            R.string.home_quick_captions,
            ClipraIcons.Captions,
            feature.captions,
            actions.onAutoCaptions,
        )
        QuickAction(R.string.home_quick_text, ClipraIcons.Text, feature.text, panel.onTextCard)
        QuickAction(
            R.string.home_quick_mosaic,
            ClipraIcons.Mosaic,
            feature.mosaic,
            actions.onMosaic,
        )
        QuickAction(
            R.string.home_tablet_import,
            ClipraIcons.Image,
            ClipraTheme.colors.onDeviceText,
            panel.onImport,
        )
    }
}

/** "Last edited ▾": a menu of the orders. */
@Composable
private fun OrderPicker(order: ProjectOrder, onOrder: (ProjectOrder) -> Unit) {
    val colors = ClipraTheme.colors
    var open by remember { mutableStateOf(false) }
    val label = @Composable { o: ProjectOrder ->
        stringResource(
            when (o) {
                ProjectOrder.LastEdited -> R.string.home_tablet_order_edited
                ProjectOrder.Name -> R.string.home_tablet_order_name
            },
        )
    }
    Box {
        Surface(
            onClick = { open = true },
            modifier = Modifier
                .height(44.dp)
                .semantics { role = Role.DropdownList }
                .testTag("order"),
            color = colors.background,
            contentColor = colors.textSecondary,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = label(order), style = ClipraTheme.typography.label)
                ClipraIcon(icon = ClipraIcons.ChevronDown, contentDescription = null, size = 16.dp)
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = colors.surfaceRaised,
        ) {
            ProjectOrder.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label(option),
                            color = if (option == order) colors.accent else colors.textPrimary,
                        )
                    },
                    onClick = {
                        open = false
                        onOrder(option)
                    },
                )
            }
        }
    }
}

/** New project beside the projects: its choices, then the output size and Create project. */
@Composable
private fun NewProjectSide(panel: NewProjectPanel, modifier: Modifier) {
    Column(modifier = modifier.testTag("new-project-panel")) {
        Text(
            text = stringResource(R.string.home_title_new_project),
            style = ClipraTheme.typography.screenTitle,
            color = ClipraTheme.colors.textPrimary,
            modifier = Modifier.padding(start = 30.dp, end = 30.dp, top = 24.dp, bottom = 12.dp),
        )
        NewProjectForm(
            format = panel.format,
            onFormatChange = panel.onFormatChange,
            fourKSupported = panel.fourKSupported,
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 30.dp)
                .padding(bottom = 12.dp),
        )
        Summary(panel.format, panel.onCreate, inset = 30.dp)
    }
}

/** The board's New project panel is 440 dp wide; its search field 320 dp. */
private const val PANEL_WIDTH = 440
private const val SEARCH_WIDTH = 320
