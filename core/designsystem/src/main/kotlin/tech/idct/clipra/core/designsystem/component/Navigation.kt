package tech.idct.clipra.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.R
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens

/** The left button of a header: back or close. */
enum class HeaderNavigation { Back, Close }

/**
 * A screen header, 52 dp: back or close on the left, the title, and actions on the right. With a
 * [subtitle] (the editor) the title is 15/600 over a Geist Mono line; without one it is the
 * Bricolage screen title. [onSubtitle] makes the title and subtitle a button, with a chevron
 * after the subtitle and [onSubtitleLabel] for TalkBack.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    navigation: HeaderNavigation? = HeaderNavigation.Back,
    onNavigate: (() -> Unit)? = null,
    subtitle: String? = null,
    onSubtitle: (() -> Unit)? = null,
    onSubtitleLabel: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ClipraDimens.HeaderHeight)
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (navigation != null && onNavigate != null) {
            when (navigation) {
                HeaderNavigation.Back -> IconAction(
                    icon = ClipraIcons.Back,
                    contentDescription = stringResource(R.string.designsystem_back),
                    onClick = onNavigate,
                    modifier = Modifier.testTag("back"),
                )

                HeaderNavigation.Close -> IconAction(
                    icon = ClipraIcons.Close,
                    contentDescription = stringResource(R.string.designsystem_close),
                    onClick = onNavigate,
                    modifier = Modifier.testTag("close"),
                )
            }
        } else {
            Box(Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onSubtitle != null) {
                        Modifier
                            .heightIn(min = ClipraDimens.TouchTarget)
                            .clip(ClipraTheme.shapes.button)
                            .clickable(
                                onClickLabel = onSubtitleLabel,
                                role = Role.Button,
                                onClick = onSubtitle,
                            )
                            .testTag("header-subtitle")
                    } else {
                        Modifier
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = title,
                style = if (subtitle != null) type.headerTitle else type.screenTitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = subtitle,
                        style = type.meta,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (onSubtitle != null) {
                        ClipraIcon(
                            icon = ClipraIcons.ChevronDown,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            size = 14.dp,
                        )
                    }
                }
            }
        }
        actions()
    }
}

/** One destination of the [BottomNav] or the [ToolRail], or one tool of the [ToolStrip]. */
@Immutable
data class NavItem(val label: String, @DrawableRes val icon: Int)

/** The phone home's bottom navigation: icon in a pill over a label, the active one accented. */
@Composable
fun BottomNav(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Grows with large fonts (#75) instead of cutting the labels.
                .heightIn(min = ClipraDimens.BottomNavHeight)
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    val pill = if (selected) colors.accentChipBackground else Color.Transparent
                    Box(
                        modifier = Modifier
                            .size(width = 56.dp, height = 30.dp)
                            .clip(CircleShape)
                            .background(pill),
                        contentAlignment = Alignment.Center,
                    ) {
                        ClipraIcon(
                            icon = item.icon,
                            contentDescription = null,
                            tint = if (selected) colors.accent else colors.textSecondary,
                        )
                    }
                    Text(
                        text = item.label,
                        style = ClipraTheme.typography.navLabel,
                        color = if (selected) colors.textPrimary else colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The tablet's 72 dp tool rail: icon over label, the active item on a warm tile. */
@Composable
fun ToolRail(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    Column(
        modifier = modifier
            .width(ClipraDimens.ToolRailWidth)
            .fillMaxHeight()
            .background(colors.background)
            .padding(vertical = 8.dp)
            .selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            ToolButton(
                item = item,
                onClick = { onSelect(index) },
                modifier = Modifier
                    .clip(ClipraTheme.shapes.button)
                    .background(if (selected) colors.accentChipBackground else Color.Transparent)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    ),
                iconTint = if (selected) colors.accent else colors.textPrimary,
                labelColor = if (selected) colors.accent else colors.textSecondaryAlt,
                clickable = false,
            )
        }
    }
}

/**
 * The phone editor's tool strip: 60 dp tools that scroll sideways. With [onClose] set, a close
 * button sits on the left, as when a clip is selected.
 */
@Composable
fun ToolStrip(
    tools: List<NavItem>,
    onTool: (NavItem) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val colors = ClipraTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .background(colors.background),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onClose != null) {
            IconAction(
                icon = ClipraIcons.ChevronLeft,
                contentDescription = stringResource(R.string.designsystem_close_tools),
                onClick = onClose,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            VerticalDivider(
                modifier = Modifier.height(60.dp),
                thickness = 1.dp,
                color = colors.border,
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            tools.forEach { tool ->
                ToolButton(item = tool, onClick = { onTool(tool) })
            }
        }
    }
}

@Composable
private fun ToolButton(
    item: NavItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = ClipraTheme.colors.textPrimary,
    labelColor: Color = ClipraTheme.colors.textSecondaryAlt,
    clickable: Boolean = true,
) {
    val click = if (clickable) {
        Modifier
            .clip(ClipraTheme.shapes.button)
            .selectable(selected = false, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .size(ClipraDimens.ToolSize)
            .then(click),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        ClipraIcon(icon = item.icon, contentDescription = null, tint = iconTint)
        Text(
            text = item.label,
            style = ClipraTheme.typography.toolLabel,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(widthDp = 390, backgroundColor = 0xFF0D0D0F, showBackground = true)
@Composable
private fun NavigationPreview() {
    ClipraTheme {
        NavigationSample()
    }
}

@Composable
internal fun NavigationSample() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(title = "New project", onNavigate = {})
        ScreenHeader(
            title = "Lisbon weekend",
            subtitle = "9:16 · FHD · 30 fps · proxies",
            navigation = HeaderNavigation.Close,
            onNavigate = {},
            actions = {
                PrimaryButton(
                    text = "Export",
                    onClick = {},
                    leadingIcon = ClipraIcons.Export,
                    compact = true,
                )
            },
        )
        ToolStrip(
            tools = listOf(
                NavItem("Split", ClipraIcons.Split),
                NavItem("Speed", ClipraIcons.Speed),
                NavItem("Audio", ClipraIcons.Audio),
                NavItem("Filters", ClipraIcons.Filters),
                NavItem("Mask", ClipraIcons.Mask),
                NavItem("Layer", ClipraIcons.Layers),
            ),
            onTool = {},
            onClose = {},
        )
        Row {
            ToolRail(
                items = listOf(
                    NavItem("Media", ClipraIcons.Image),
                    NavItem("Text", ClipraIcons.Text),
                    NavItem("Audio", ClipraIcons.Music),
                ),
                selectedIndex = 0,
                onSelect = {},
                modifier = Modifier.heightIn(max = 220.dp),
            )
        }
        BottomNav(
            items = listOf(
                NavItem("Projects", ClipraIcons.Projects),
                NavItem("Models", ClipraIcons.OnDevice),
                NavItem("Settings", ClipraIcons.Settings),
            ),
            selectedIndex = 0,
            onSelect = {},
        )
    }
}
