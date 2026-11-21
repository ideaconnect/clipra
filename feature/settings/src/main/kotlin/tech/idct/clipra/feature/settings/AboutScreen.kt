package tech.idct.clipra.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraCard
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens

/** What About Clipra can do. Each action opens another app: the browser or the email app. */
class AboutActions(
    val onBack: () -> Unit,
    /** idct.tech in the browser. */
    val onWebsite: () -> Unit,
    /** Buy Me a Coffee in the browser. */
    val onCoffee: () -> Unit,
    /** GitHub Sponsors in the browser. */
    val onSponsor: () -> Unit,
    /** A new email to clipra@idct.tech, for a feature request or a bug report. */
    val onReport: () -> Unit,
)

/**
 * About Clipra (#284; no board in the design, built from the design system): who makes Clipra,
 * IDCT, with its logo, its details and a link to idct.tech; the support buttons ReelsMagician
 * has (Buy Me a Coffee and GitHub Sponsors); and an email for ideas and bug reports.
 */
@Composable
fun AboutScreen(actions: AboutActions, modifier: Modifier = Modifier) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:About"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.settings_about_clipra),
            navigation = HeaderNavigation.Back,
            onNavigate = actions.onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Company(onWebsite = actions.onWebsite)

            SectionLabel(R.string.about_support)
            Paragraph(R.string.about_support_text)
            SupportButton(
                text = stringResource(R.string.about_coffee),
                icon = ClipraIcons.Coffee,
                container = colors.support.coffee,
                content = colors.support.onCoffee,
                iconTint = colors.support.onCoffee,
                border = null,
                onClick = actions.onCoffee,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("about-coffee"),
            )
            SupportButton(
                text = stringResource(R.string.about_sponsor),
                icon = ClipraIcons.Heart,
                container = colors.support.sponsor,
                content = colors.support.onSponsor,
                iconTint = colors.support.sponsorHeart,
                border = BorderStroke(1.dp, colors.support.sponsorBorder),
                onClick = actions.onSponsor,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("about-sponsor"),
            )

            SectionLabel(R.string.about_feedback)
            Paragraph(R.string.about_feedback_text)
            SecondaryButton(
                text = stringResource(R.string.about_report),
                onClick = actions.onReport,
                leadingIcon = ClipraIcons.Mail,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("about-report"),
            )
            // The address, for a phone without an email app: it can be selected and copied.
            SelectionContainer(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.about_report_address),
                    style = type.meta.copy(fontSize = type.label.fontSize),
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("about-report-address"),
                )
            }
        }
    }
}

/** The company card: the IDCT logo, its name, what it is, its address and its website. */
@Composable
private fun Company(onWebsite: () -> Unit) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    ClipraCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("about-company"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // One colour, like the logo on idct.tech: drawn in the text colour on the dark theme.
            Image(
                painter = painterResource(R.drawable.settings_idct_logo),
                contentDescription = stringResource(R.string.about_logo),
                colorFilter = ColorFilter.tint(colors.textPrimary),
                modifier = Modifier
                    .height(LogoHeight)
                    .aspectRatio(LogoAspectRatio)
                    .testTag("about-logo"),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.about_made_by),
                style = type.sectionTitle,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.about_company_name),
                style = type.bodyStrong,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.about_company_description),
                style = type.caption,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.about_company_address),
                style = type.caption,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                text = stringResource(R.string.about_website),
                onClick = onWebsite,
                leadingIcon = ClipraIcons.Globe,
                outlined = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("about-website"),
            )
        }
    }
}

/**
 * A button in another service's own colours ([ClipraColors.support]), 48 dp like
 * [SecondaryButton]: Buy Me a Coffee's yellow, or GitHub's dark button with the pink heart.
 */
@Composable
private fun SupportButton(
    text: String,
    @DrawableRes icon: Int,
    container: Color,
    content: Color,
    iconTint: Color,
    border: BorderStroke?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = ClipraDimens.SecondaryButtonHeight)
            .semantics { role = Role.Button },
        shape = ClipraTheme.shapes.button,
        color = container,
        contentColor = content,
        border = border,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = ClipraDimens.SecondaryButtonHeight)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClipraIcon(icon = icon, contentDescription = null, tint = iconTint, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Text(text = text, style = ClipraTheme.typography.button)
        }
    }
}

@Composable
private fun Paragraph(text: Int) {
    Text(
        text = stringResource(text),
        style = ClipraTheme.typography.body,
        color = ClipraTheme.colors.textSecondary,
    )
}

/**
 * A new email to [address] with [subject], for the phone's email app. The address goes in the
 * mailto: link only (some apps would list it twice if it were in EXTRA_EMAIL too); the subject
 * goes in both, as apps read one or the other.
 */
internal fun emailIntent(address: String, subject: String): Intent = Intent(
    Intent.ACTION_SENDTO,
    "mailto:$address?subject=${Uri.encode(subject)}".toUri(),
).putExtra(Intent.EXTRA_SUBJECT, subject)

/** The logo's own proportions (its viewport, 2048 × 1922). */
private val LogoAspectRatio = 2048f / 1922f
private val LogoHeight = 112.dp
