package tech.idct.clipra.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens

/** A plain card: surface fill, radius 16, 14 dp padding. Clickable when [onClick] is set. */
@Composable
fun ClipraCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    CardSurface(
        modifier = modifier,
        onClick = onClick,
        color = ClipraTheme.colors.surface,
        border = null,
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

/**
 * The "runs on this device" card: green icon tile, title and detail, an optional action on the
 * right. Green means on-device and nothing else.
 */
@Composable
fun OnDeviceCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = ClipraTheme.colors
    CardSurface(
        modifier = modifier,
        onClick = onClick,
        color = colors.onDeviceCardBackground,
        border = BorderStroke(1.dp, colors.onDeviceCardBorder),
    ) {
        IconTitleRow(
            iconTile = {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.onDeviceBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    ClipraIcon(
                        icon = ClipraIcons.OnDevice,
                        contentDescription = null,
                        tint = colors.onDeviceText,
                        size = 20.dp,
                    )
                }
            },
            title = title,
            subtitle = subtitle,
            subtitleColor = colors.onDeviceCardText,
            action = action,
        )
    }
}

/** The Clipra Pro card: crown on an accent circle, warm fill and border. */
@Composable
fun ProCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = ClipraTheme.colors
    CardSurface(
        modifier = modifier,
        onClick = onClick,
        color = colors.proCardBackground,
        border = BorderStroke(1.dp, colors.proCardBorder),
    ) {
        IconTitleRow(
            iconTile = {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    ClipraIcon(
                        icon = ClipraIcons.Crown,
                        contentDescription = null,
                        tint = colors.onAccent,
                        size = 18.dp,
                    )
                }
            },
            title = title,
            subtitle = subtitle,
            subtitleColor = colors.proCardText,
            action = action,
        )
    }
}

@Composable
private fun CardSurface(
    modifier: Modifier,
    onClick: (() -> Unit)?,
    color: Color,
    border: BorderStroke?,
    content: @Composable () -> Unit,
) {
    val shape = ClipraTheme.shapes.card
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.semantics { role = Role.Button },
            shape = shape,
            color = color,
            contentColor = ClipraTheme.colors.textPrimary,
            border = border,
            content = content,
        )
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            contentColor = ClipraTheme.colors.textPrimary,
            border = border,
            content = content,
        )
    }
}

@Composable
private fun IconTitleRow(
    iconTile: @Composable () -> Unit,
    title: String,
    subtitle: String,
    subtitleColor: Color,
    action: (@Composable () -> Unit)?,
) {
    val type = ClipraTheme.typography
    Row(
        modifier = Modifier
            .heightIn(min = 60.dp)
            .padding(start = 12.dp, end = if (action != null) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        iconTile()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = type.body.copy(fontWeight = type.chip.fontWeight))
            Text(
                text = subtitle,
                style = type.caption,
                color = subtitleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        action?.invoke()
    }
}

@Preview(widthDp = 390, backgroundColor = 0xFF0D0D0F, showBackground = true)
@Composable
private fun CardsPreview() {
    ClipraTheme {
        CardsSample()
    }
}

@Composable
internal fun CardsSample() {
    Column(
        modifier = Modifier.padding(ClipraDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ClipraCard(modifier = Modifier.fillMaxWidth()) {
            Text("Card", style = ClipraTheme.typography.bodyStrong)
            Text(
                "Surface fill, radius 16, 14 dp padding",
                style = ClipraTheme.typography.caption,
                color = ClipraTheme.colors.textSecondary,
            )
        }
        OnDeviceCard(
            title = "On-device AI ready",
            subtitle = "Whisper Base · DeepFilterNet 3",
            modifier = Modifier.fillMaxWidth(),
            action = { TextAction(text = "Models", onClick = {}) },
        )
        ProCard(
            title = "Clipra Pro",
            subtitle = "No ads when you export. One payment.",
            modifier = Modifier.fillMaxWidth(),
            onClick = {},
        )
    }
}
