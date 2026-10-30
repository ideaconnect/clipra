package tech.idct.clipra.feature.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.data.project.ProjectSummary
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.BottomNav
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.IconAction
import tech.idct.clipra.core.designsystem.component.NavItem
import tech.idct.clipra.core.designsystem.component.OnDeviceCard
import tech.idct.clipra.core.designsystem.component.TextAction
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Layout
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Where Home's buttons lead; the app connects them to the other features. */
class HomeActions(
    val onNewProject: () -> Unit,
    val onOpenProject: (String) -> Unit,
    val onAutoCaptions: () -> Unit,
    val onTextCard: () -> Unit,
    val onMosaic: () -> Unit,
    val onModels: () -> Unit,
    val onSettings: () -> Unit,
    val onPro: () -> Unit,
    /** Debug builds only: the screen index. */
    val onScreenIndex: (() -> Unit)? = null,
    /** Clipra Pro for the tablet header (#71): owned, or its price from Google Play. */
    val proOffer: @Composable () -> ProOffer = { ProOffer() },
)

/** What the on-device AI card says; the AI models issue (#43) supplies the real status. */
data class AiStatus(val ready: Boolean, val summary: String)

/** The project menu's actions on Home. */
class ProjectActions(
    val onRename: (ProjectSummary) -> Unit,
    val onDuplicate: (ProjectSummary) -> Unit,
    val onDelete: (ProjectSummary) -> Unit,
)

/**
 * Home (Main board): brand and Pro, New project, quick actions, on-device AI status, recent
 * projects and the bottom navigation.
 */
@Composable
fun HomeScreen(
    recent: List<ProjectSummary>?,
    nowMillis: Long,
    aiStatus: AiStatus?,
    actions: HomeActions,
    projectActions: ProjectActions,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Main"),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .weight(1f)
                .testTag("home"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            fullWidth { Header(actions) }
            fullWidth { NewProjectCard(actions.onNewProject) }
            fullWidth { QuickActions(actions) }
            fullWidth { AiCard(aiStatus, actions.onModels) }
            fullWidth { RecentHeader(recent?.size ?: 0) }
            if (recent != null && recent.isEmpty()) {
                fullWidth {
                    Text(
                        text = stringResource(R.string.home_recent_empty),
                        style = ClipraTheme.typography.body,
                        color = colors.textSecondary,
                    )
                }
            }
            items(recent.orEmpty(), key = { it.id }) { project ->
                ProjectCard(
                    project = project,
                    nowMillis = nowMillis,
                    onOpen = { actions.onOpenProject(project.id) },
                    actions = projectActions,
                )
            }
            if (actions.onScreenIndex != null) {
                fullWidth {
                    TextAction(
                        text = stringResource(R.string.home_screen_index),
                        onClick = actions.onScreenIndex,
                        modifier = Modifier.testTag("link:BoardIndex"),
                    )
                }
            }
        }
        BottomNav(
            items = listOf(
                NavItem(stringResource(R.string.home_nav_projects), ClipraIcons.Projects),
                NavItem(stringResource(R.string.home_nav_models), ClipraIcons.OnDevice),
                NavItem(stringResource(R.string.home_nav_settings), ClipraIcons.Settings),
            ),
            selectedIndex = 0,
            onSelect = { index ->
                when (index) {
                    1 -> actions.onModels()
                    2 -> actions.onSettings()
                }
            },
        )
    }
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun Header(actions: HomeActions) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painter = painterResource(ClipraIcons.Logo),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
            )
            Text(
                text = stringResource(R.string.home_brand),
                style = type.brand,
                color = colors.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            ProPill(onClick = actions.onPro)
            IconAction(
                icon = ClipraIcons.Settings,
                contentDescription = stringResource(R.string.home_nav_settings),
                onClick = actions.onSettings,
                tint = colors.textSecondaryAlt,
            )
        }
        Text(
            text = stringResource(R.string.home_tagline),
            style = type.caption,
            color = colors.textTertiary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun ProPill(onClick: () -> Unit) {
    val colors = ClipraTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(ClipraDimens.TouchTarget)
            .semantics { role = Role.Button },
        shape = CircleShape,
        color = colors.accentChipBackground,
        contentColor = colors.accent,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ClipraIcon(icon = ClipraIcons.Crown, contentDescription = null, size = 16.dp)
            Text(text = stringResource(R.string.home_pro), style = ClipraTheme.typography.chip)
        }
    }
}

@Composable
private fun NewProjectCard(onClick: () -> Unit) {
    val colors = ClipraTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .semantics { role = Role.Button }
            .testTag("new-project"),
        shape = ClipraTheme.shapes.cardLarge,
        color = colors.accent,
        contentColor = colors.onAccent,
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_title_new_project),
                    style = ClipraTheme.typography.cardTitle,
                )
                Text(
                    text = stringResource(R.string.home_new_project_formats),
                    style = ClipraTheme.typography.label,
                    color = colors.onAccentMuted,
                )
            }
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(colors.onAccent),
                contentAlignment = Alignment.Center,
            ) {
                ClipraIcon(icon = ClipraIcons.Add, contentDescription = null, tint = colors.accent)
            }
        }
    }
}

@Composable
private fun QuickActions(actions: HomeActions) {
    val feature = ClipraTheme.colors.feature
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickAction(
            R.string.home_quick_captions,
            ClipraIcons.Captions,
            feature.captions,
            actions.onAutoCaptions,
        )
        QuickAction(R.string.home_quick_text, ClipraIcons.Text, feature.text, actions.onTextCard)
        QuickAction(
            R.string.home_quick_mosaic,
            ClipraIcons.Mosaic,
            feature.mosaic,
            actions.onMosaic,
        )
    }
}

@Composable
internal fun RowScope.QuickAction(
    label: Int,
    @DrawableRes icon: Int,
    tint: Color,
    onClick: () -> Unit,
) {
    val colors = ClipraTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .height(80.dp)
            .semantics { role = Role.Button },
        shape = ClipraTheme.shapes.card,
        color = colors.surface,
        contentColor = colors.textPrimary,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            ClipraIcon(icon = icon, contentDescription = null, tint = tint)
            Text(text = stringResource(label), style = ClipraTheme.typography.chip, maxLines = 2)
        }
    }
}

@Composable
internal fun AiCard(status: AiStatus?, onModels: () -> Unit) {
    OnDeviceCard(
        title = stringResource(
            if (status?.ready ==
                true
            ) {
                R.string.home_ai_ready
            } else {
                R.string.home_ai_title
            },
        ),
        subtitle = status?.summary ?: stringResource(R.string.home_ai_setup),
        subtitleMaxLines = 1,
        modifier = Modifier.fillMaxWidth(),
        action = {
            TextAction(text = stringResource(R.string.home_nav_models), onClick = onModels)
        },
    )
}

@Composable
internal fun RecentHeader(count: Int) {
    Row(
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.home_recent),
            style = ClipraTheme.typography.sectionTitle,
            color = ClipraTheme.colors.textPrimary,
        )
        if (count > 0) {
            Text(
                text = pluralStringResource(R.plurals.home_recent_count, count, count),
                style = ClipraTheme.typography.label,
                color = ClipraTheme.colors.textTertiary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProjectCard(
    project: ProjectSummary,
    nowMillis: Long,
    onOpen: () -> Unit,
    actions: ProjectActions,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    var menu by remember { mutableStateOf(false) }
    val rename = stringResource(R.string.home_menu_rename)
    val duplicate = stringResource(R.string.home_menu_duplicate)
    val delete = stringResource(R.string.home_menu_delete)
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(rename) {
                            actions.onRename(project)
                            true
                        },
                        CustomAccessibilityAction(duplicate) {
                            actions.onDuplicate(project)
                            true
                        },
                        CustomAccessibilityAction(delete) {
                            actions.onDelete(project)
                            true
                        },
                    )
                }
                .testTag("project:${project.id}"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Thumbnail(project)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = project.name,
                    style = type.body.copy(fontWeight = type.chip.fontWeight),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = editedAgoText(EditedAgo.of(nowMillis, project.updatedAtMillis)),
                    style = type.caption,
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(rename) }, onClick = {
                menu = false
                actions.onRename(project)
            })
            DropdownMenuItem(text = { Text(duplicate) }, onClick = {
                menu = false
                actions.onDuplicate(project)
            })
            DropdownMenuItem(text = { Text(delete, color = colors.danger) }, onClick = {
                menu = false
                actions.onDelete(project)
            })
        }
    }
}

/** The format frame, sized to the project's ratio, with the badges over it. */
@Composable
private fun Thumbnail(project: ProjectSummary) {
    val colors = ClipraTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        val (width, height) = frameSize(project.ratio)
        LayoutFrame(project.layout, width, height)
        Badge(
            text = formatBadge(project.ratio, project.layout, project.resolution.label),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        )
        Badge(
            text = formatDuration(project.durationUs),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
        )
    }
}

/** Fits the ratio into the 136 × 100 dp area the design uses. */
private fun frameSize(ratio: AspectRatio): Pair<Dp, Dp> {
    val scale = minOf(136f / ratio.width, 100f / ratio.height)
    return (ratio.width * scale).dp to (ratio.height * scale).dp
}

@Composable
private fun LayoutFrame(layout: Layout, width: Dp, height: Dp) {
    val colors = ClipraTheme.colors
    LayoutCells(
        layout = layout,
        portrait = height > width,
        width = width,
        height = height,
        cell = colors.surfaceRaised,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.background),
    )
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    val colors = ClipraTheme.colors
    Text(
        text = text,
        style = ClipraTheme.typography.meta,
        color = colors.textPrimary,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.background.copy(alpha = 0.82f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun formatBadge(ratio: AspectRatio, layout: Layout, resolution: String): String =
    if (layout == Layout.Single) {
        stringResource(R.string.home_badge, ratio.label, resolution)
    } else {
        stringResource(R.string.home_badge_mosaic, stringResource(layout.labelRes()), resolution)
    }

@Composable
private fun editedAgoText(ago: EditedAgo): String = when (ago) {
    EditedAgo.JustNow -> stringResource(R.string.home_edited_just_now)

    is EditedAgo.Minutes -> stringResource(R.string.home_edited_minutes, ago.count)

    is EditedAgo.Hours -> stringResource(R.string.home_edited_hours, ago.count)

    EditedAgo.Yesterday -> stringResource(R.string.home_edited_yesterday)

    is EditedAgo.Days -> pluralStringResource(R.plurals.home_edited_days, ago.count, ago.count)

    EditedAgo.LastWeek -> stringResource(R.string.home_edited_last_week)

    is EditedAgo.On -> stringResource(
        R.string.home_edited_on,
        ago.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
    )
}
