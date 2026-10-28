package tech.idct.clipra.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens

/** An icon from [ClipraIcons], tinted with the content colour unless [tint] says otherwise. */
@Composable
fun ClipraIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = ClipraDimens.IconSize,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}

/**
 * The main action of a screen: 56 dp, accent fill, 16/700 text, optional icons. [compact] is the
 * 40 dp version for headers, such as Export in the editor; it keeps a 44 dp touch area.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes leadingIcon: Int? = null,
    @DrawableRes trailingIcon: Int? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val colors = ClipraTheme.colors
    val height = if (compact) CompactButtonHeight else ClipraDimens.PrimaryButtonHeight
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = height)
            .semantics { role = Role.Button },
        enabled = enabled,
        shape = ClipraTheme.shapes.buttonLarge,
        color = if (enabled) colors.accent else colors.surfaceRaised,
        contentColor = if (enabled) colors.onAccent else colors.textDisabled,
    ) {
        ButtonContent(
            text = text,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            minHeight = height,
            primary = !compact,
        )
    }
}

/** A second action: 48 dp on a raised surface, or outlined. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes leadingIcon: Int? = null,
    outlined: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = ClipraTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = ClipraDimens.SecondaryButtonHeight)
            .semantics { role = Role.Button },
        enabled = enabled,
        shape = ClipraTheme.shapes.button,
        color = if (outlined) Color.Transparent else colors.surfaceRaised,
        contentColor = if (enabled) colors.textPrimary else colors.textDisabled,
        border = if (outlined) {
            BorderStroke(
                ClipraDimens.BorderWidth,
                colors.borderStrong,
            )
        } else {
            null
        },
    ) {
        ButtonContent(
            text = text,
            leadingIcon = leadingIcon,
            trailingIcon = null,
            minHeight = ClipraDimens.SecondaryButtonHeight,
            primary = false,
        )
    }
}

/**
 * A text action, such as "Models" on the on-device card, with a 44 dp touch area. Accent by
 * default; [color] gives, for example, the danger colour of "Cancel export".
 */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = ClipraTheme.colors.accent,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = ClipraDimens.TouchTarget)
            .widthIn(min = ClipraDimens.TouchTarget)
            .semantics { role = Role.Button },
        shape = ClipraTheme.shapes.button,
        color = Color.Transparent,
        contentColor = color,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = ClipraDimens.TouchTarget)
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, style = ClipraTheme.typography.button)
        }
    }
}

/** A 44 dp icon-only button, as in headers and the player bar. */
@Composable
fun IconAction(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = ClipraTheme.colors.textPrimary,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(ClipraDimens.TouchTarget)
            .semantics { role = Role.Button },
        enabled = enabled,
        shape = ClipraTheme.shapes.button,
        color = Color.Transparent,
        contentColor = if (enabled) tint else ClipraTheme.colors.textDisabled,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClipraIcon(icon = icon, contentDescription = contentDescription)
        }
    }
}

private val CompactButtonHeight = 40.dp

@Composable
private fun ButtonContent(
    text: String,
    @DrawableRes leadingIcon: Int?,
    @DrawableRes trailingIcon: Int?,
    minHeight: Dp,
    primary: Boolean,
) {
    val type = ClipraTheme.typography
    Row(
        modifier = Modifier
            .heightIn(min = minHeight)
            .padding(horizontal = if (primary) 20.dp else 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            ClipraIcon(icon = leadingIcon, contentDescription = null, size = 20.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text = text, style = if (primary) type.primaryButton else type.button)
        if (trailingIcon != null) {
            Spacer(Modifier.width(10.dp))
            ClipraIcon(icon = trailingIcon, contentDescription = null, size = 20.dp)
        }
    }
}

@Preview(widthDp = 390, backgroundColor = 0xFF0D0D0F, showBackground = true)
@Composable
private fun ButtonsPreview() {
    ClipraTheme {
        ButtonsSample()
    }
}

@Composable
internal fun ButtonsSample() {
    Column(
        modifier = Modifier.padding(ClipraDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PrimaryButton(
            text = "Create project",
            onClick = {},
            trailingIcon = ClipraIcons.Forward,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(
            text = "Export to Gallery",
            onClick = {},
            leadingIcon = ClipraIcons.Export,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(text = "Disabled", onClick = {
        }, enabled = false, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(text = "Share", onClick = {}, leadingIcon = ClipraIcons.Share)
            SecondaryButton(text = "Open Gallery", onClick = {}, outlined = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextAction(text = "Models", onClick = {})
            IconAction(icon = ClipraIcons.Undo, contentDescription = "Undo", onClick = {})
            IconAction(icon = ClipraIcons.Redo, contentDescription = "Redo", onClick = {
            }, enabled = false)
        }
    }
}
