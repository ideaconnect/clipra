package tech.idct.clipra.monetization

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.IconAction
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.component.TextAction
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens

/** A line the Pro screen shows after Restore or a purchase that did not go through. */
enum class ProMessage { Restored, NothingToRestore, Unavailable, Failed }

data class ProUiState(
    val owned: Boolean = false,
    /** Google Play's formatted price for this user; null until Play answers. */
    val price: String? = null,
    val pending: Boolean = false,
    val busy: Boolean = false,
    val message: ProMessage? = null,
    /** Show "Ad privacy choices": where the law requires a way to change ad consent (#62). */
    val adPrivacyChoices: Boolean = false,
)

/** What the Pro screen can do. */
class ProActions(
    val onClose: () -> Unit,
    val onBuy: () -> Unit,
    val onRestore: () -> Unit,
    val onBackToProjects: () -> Unit,
    val onAdPrivacy: () -> Unit,
)

/**
 * Clipra Pro (#64), the Pro and Pro--owned boards and TabletPro: the benefits, the price from
 * Google Play, Buy, Restore, the payment note and Ad privacy choices. The owned state thanks the
 * user and leads back to the projects.
 */
@Composable
fun ProScreen(state: ProUiState, actions: ProActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(ClipraTheme.colors.background)
            .safeDrawingPadding()
            .testTag("screen:Pro"),
    ) {
        if (maxWidth >= TabletWidth && maxHeight >= TabletDialogHeight) {
            TabletPro(state, actions)
        } else {
            PhonePro(state, actions)
        }
    }
}

@Composable
private fun PhonePro(state: ProUiState, actions: ProActions) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(start = 6.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CloseAction(actions.onClose)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CrownTile(size = 64.dp, radius = 20.dp)
                Title(state.owned)
                Subtitle(state.owned, color = ClipraTheme.colors.textSecondaryAlt)
            }
            if (!state.owned) PriceCard(state.price)
            Benefits(spacing = 14.dp)
        }
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusLine(state)
            if (state.owned) {
                PrimaryButton(
                    text = stringResource(R.string.monetization_pro_back_to_projects),
                    onClick = actions.onBackToProjects,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                BuyButton(state, actions, Modifier.fillMaxWidth())
                TextAction(
                    text = stringResource(R.string.monetization_pro_restore),
                    onClick = actions.onRestore,
                    color = ClipraTheme.colors.textPrimary,
                    modifier = Modifier.testTag("pro-restore"),
                )
            }
            PaymentNote(TextAlign.Center)
            if (state.adPrivacyChoices) AdPrivacyLink(actions.onAdPrivacy)
        }
    }
}

@Composable
private fun TabletPro(state: ProUiState, actions: ProActions) {
    val colors = ClipraTheme.colors
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .width(800.dp)
                .height(TabletDialogHeight)
                .clip(shape)
                .background(colors.surfaceSunken)
                .border(BorderStroke(ClipraDimens.BorderWidth, colors.border), shape),
        ) {
            Column(
                modifier = Modifier
                    .width(340.dp)
                    .fillMaxHeight()
                    .background(colors.proCardBackground)
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CrownTile(size = 72.dp, radius = 22.dp)
                Title(state.owned)
                Subtitle(state.owned, color = colors.proCardText)
                Spacer(Modifier.weight(1f))
                if (!state.owned) {
                    Text(
                        text = state.price.orEmpty(),
                        style = ClipraTheme.typography.numberMedium,
                        color = colors.accent,
                        modifier = Modifier.testTag("pro-price"),
                    )
                    Text(
                        text = stringResource(R.string.monetization_pro_one_time),
                        style = ClipraTheme.typography.label,
                        color = colors.proCardText,
                    )
                }
            }
            Box(
                Modifier
                    .width(ClipraDimens.BorderWidth)
                    .fillMaxHeight()
                    .background(colors.proCardBorder),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    CloseAction(actions.onClose)
                }
                Benefits(spacing = 16.dp)
                Spacer(Modifier.weight(1f))
                StatusLine(state)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.owned) {
                        PrimaryButton(
                            text = stringResource(R.string.monetization_pro_back_to_projects),
                            onClick = actions.onBackToProjects,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        BuyButton(state, actions, Modifier.weight(1f))
                        SecondaryButton(
                            text = stringResource(R.string.monetization_pro_restore),
                            onClick = actions.onRestore,
                            enabled = !state.busy,
                            modifier = Modifier
                                .heightIn(min = ClipraDimens.PrimaryButtonHeight)
                                .testTag("pro-restore"),
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.weight(1f)) { PaymentNote(TextAlign.Start) }
                    if (state.adPrivacyChoices) AdPrivacyLink(actions.onAdPrivacy)
                }
            }
        }
    }
}

@Composable
private fun CloseAction(onClose: () -> Unit, modifier: Modifier = Modifier) {
    IconAction(
        icon = ClipraIcons.Close,
        contentDescription = stringResource(R.string.monetization_close),
        onClick = onClose,
        modifier = modifier,
    )
}

@Composable
private fun CrownTile(size: Dp, radius: Dp) {
    val colors = ClipraTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(colors.accent),
        contentAlignment = Alignment.Center,
    ) {
        ClipraIcon(
            icon = ClipraIcons.Crown,
            contentDescription = null,
            tint = colors.onAccent,
            size = size / 2,
        )
    }
}

@Composable
private fun Title(owned: Boolean) {
    Text(
        text = stringResource(
            if (owned) R.string.monetization_pro_owned_title else R.string.monetization_title_pro,
        ),
        style = ClipraTheme.typography.heroTitle,
        color = ClipraTheme.colors.textPrimary,
    )
}

@Composable
private fun Subtitle(owned: Boolean, color: Color) {
    val text = if (owned) {
        R.string.monetization_pro_owned_subtitle
    } else {
        R.string.monetization_pro_subtitle
    }
    Text(
        text = stringResource(text),
        style = ClipraTheme.typography.bodyStrong.copy(fontWeight = FontWeight.Normal),
        color = color,
    )
}

/** The price on its warm card; while Google Play has not answered only the terms show. */
@Composable
private fun PriceCard(price: String?) {
    val colors = ClipraTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.proCardBackground)
            .border(BorderStroke(ClipraDimens.BorderWidth, colors.proCardBorder), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .heightIn(min = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (price != null) {
            Text(
                text = price,
                style = ClipraTheme.typography.numberMedium,
                color = colors.accent,
                modifier = Modifier
                    .alignByBaseline()
                    .testTag("pro-price"),
            )
        }
        Text(
            text = stringResource(R.string.monetization_pro_one_time),
            style = ClipraTheme.typography.label,
            color = colors.proCardText,
            modifier = if (price != null) {
                Modifier.alignByBaseline()
            } else {
                Modifier.align(Alignment.CenterVertically)
            },
        )
    }
}

@Composable
private fun Benefits(spacing: Dp) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        BENEFITS.forEach { (title, body) -> Benefit(title, body) }
    }
}

@Composable
private fun Benefit(@StringRes title: Int, @StringRes body: Int) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(colors.accentChipBackground),
            contentAlignment = Alignment.Center,
        ) {
            ClipraIcon(
                icon = ClipraIcons.Check,
                contentDescription = null,
                tint = colors.accent,
                size = 14.dp,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = stringResource(title), style = type.bodyStrong, color = colors.textPrimary)
            Text(
                text = stringResource(body),
                style = type.label.copy(fontWeight = FontWeight.Normal),
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun BuyButton(state: ProUiState, actions: ProActions, modifier: Modifier) {
    PrimaryButton(
        text = state.price?.let { stringResource(R.string.monetization_pro_buy_price, it) }
            ?: stringResource(R.string.monetization_pro_buy),
        onClick = actions.onBuy,
        leadingIcon = ClipraIcons.Crown,
        // Buying needs the product from Google Play, and a pending payment must finish first.
        enabled = state.price != null && !state.pending && !state.busy,
        modifier = modifier.testTag("pro-buy"),
    )
}

/** Payment pending, or what Restore and the last purchase found. */
@Composable
private fun StatusLine(state: ProUiState) {
    val colors = ClipraTheme.colors
    val (text, color) = when {
        state.pending && !state.owned -> R.string.monetization_pro_pending to colors.proCardText

        state.message == ProMessage.Restored ->
            R.string.monetization_pro_restored to
                colors.textSecondary

        state.message == ProMessage.NothingToRestore ->
            R.string.monetization_pro_nothing_to_restore to colors.textSecondary

        state.message == ProMessage.Unavailable ->
            R.string.monetization_pro_unavailable to
                colors.danger

        state.message == ProMessage.Failed -> R.string.monetization_pro_failed to colors.danger

        else -> return
    }
    Text(
        text = stringResource(text),
        style = ClipraTheme.typography.label,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .testTag("pro-status"),
    )
}

@Composable
private fun PaymentNote(align: TextAlign) {
    Text(
        text = stringResource(R.string.monetization_pro_note),
        style = ClipraTheme.typography.caption,
        color = ClipraTheme.colors.textTertiary,
        textAlign = align,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "Ad privacy choices": UMP's privacy options form, where the user changes ad consent. */
@Composable
private fun AdPrivacyLink(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = ClipraDimens.TouchTarget)
            .clip(ClipraTheme.shapes.button)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp)
            .testTag("pro-ad-privacy"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.monetization_ad_privacy),
            style = ClipraTheme.typography.navLabel.copy(textDecoration = TextDecoration.Underline),
            color = ClipraTheme.colors.textSecondaryAlt,
        )
    }
}

private val BENEFITS = listOf(
    R.string.monetization_pro_benefit_no_ads to R.string.monetization_pro_benefit_no_ads_body,
    R.string.monetization_pro_benefit_nothing_else to
        R.string.monetization_pro_benefit_nothing_else_body,
    R.string.monetization_pro_benefit_devices to R.string.monetization_pro_benefit_devices_body,
    R.string.monetization_pro_benefit_independent to
        R.string.monetization_pro_benefit_independent_body,
)

private val TabletWidth = 840.dp
private val TabletDialogHeight = 580.dp
