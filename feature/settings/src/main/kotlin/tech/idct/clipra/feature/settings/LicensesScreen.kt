package tech.idct.clipra.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.ScreenHeader

/**
 * A component Clipra ships and its license (docs/third-party.md). [file] is the license text in
 * the app's assets; Google's SDKs come under Google's own terms and carry none.
 */
data class OpenSourceComponent(val name: String, val license: String, val file: String?)

/** What the app ships, as docs/third-party.md lists it. */
val OpenSourceComponents = listOf(
    OpenSourceComponent(
        "AndroidX, Jetpack Compose, Material 3, Media3",
        "Apache-2.0",
        "licenses/Apache-2.0.txt",
    ),
    OpenSourceComponent(
        "Kotlin, kotlinx.coroutines, kotlinx.serialization",
        "Apache-2.0",
        "licenses/Apache-2.0.txt",
    ),
    OpenSourceComponent("Hilt and Dagger", "Apache-2.0", "licenses/Apache-2.0.txt"),
    OpenSourceComponent("whisper.cpp", "MIT", "licenses/MIT-whisper.cpp.txt"),
    OpenSourceComponent("ONNX Runtime", "MIT", "licenses/MIT-ONNXRuntime.txt"),
    OpenSourceComponent("LiteRT", "Apache-2.0", "licenses/Apache-2.0.txt"),
    OpenSourceComponent("MediaPipe selfie segmenter", "Apache-2.0", "licenses/Apache-2.0.txt"),
    OpenSourceComponent("DeepFilterNet", "MIT", "licenses/MIT-DeepFilterNet.txt"),
    OpenSourceComponent("RNNoise", "BSD-3-Clause", "licenses/BSD-3-Clause-RNNoise.txt"),
    OpenSourceComponent("Bricolage Grotesque", "OFL-1.1", "licenses/OFL-BricolageGrotesque.txt"),
    OpenSourceComponent("Geist", "OFL-1.1", "licenses/OFL-Geist.txt"),
    OpenSourceComponent("Geist Mono", "OFL-1.1", "licenses/OFL-GeistMono.txt"),
    OpenSourceComponent(
        "Gson, Guava, OkHttp and Tink (with the ads SDK)",
        "Apache-2.0",
        "licenses/Apache-2.0.txt",
    ),
    OpenSourceComponent(
        "Google Mobile Ads, User Messaging Platform, Play Billing",
        "Google terms",
        null,
    ),
)

/** Open-source licenses: every component with its license; a tap shows the license text. */
@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    onLicense: (OpenSourceComponent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Licenses"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.settings_licenses),
            navigation = HeaderNavigation.Back,
            onNavigate = onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OpenSourceComponents.forEach { component ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(ClipraTheme.shapes.card)
                        .background(colors.surface)
                        .then(
                            if (component.file != null) {
                                Modifier.clickable(role = Role.Button) { onLicense(component) }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(text = component.name, style = type.bodyStrong, color = colors.textPrimary)
                    Text(
                        text = component.license,
                        style = type.meta.copy(fontSize = type.caption.fontSize),
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

/** One license text, from the app's assets. */
@Composable
fun LicenseTextScreen(title: String, file: String, onBack: () -> Unit) {
    val colors = ClipraTheme.colors
    val context = LocalContext.current
    val text by produceState("", file) {
        value = withContext(Dispatchers.IO) {
            reflow(context.assets.open(file).bufferedReader().use { it.readText() })
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:License"),
    ) {
        ScreenHeader(title = title, navigation = HeaderNavigation.Back, onNavigate = onBack)
        Text(
            text = text,
            style = ClipraTheme.typography.label,
            color = colors.textSecondary,
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        )
    }
}

/**
 * License files wrap at about 80 columns; on a phone the paragraphs read better joined and left
 * to wrap at the screen's width.
 */
internal fun reflow(text: String): String = text.trim()
    .split(Regex("\\n\\s*\\n"))
    .joinToString("\n\n") { paragraph ->
        paragraph.lines().joinToString(" ") { it.trim() }
    }
