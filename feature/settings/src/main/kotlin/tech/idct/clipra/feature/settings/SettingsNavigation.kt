package tech.idct.clipra.feature.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.component.ReadableWidth

@Serializable
data object SettingsRoute

@Serializable
data object LicensesRoute

/** One license text: [file] in the app's assets, under [title]. */
@Serializable
data class LicenseRoute(val title: String, val file: String)

/** Where the settings screens lead; the app connects them. */
class SettingsNavigationActions(
    val onBack: () -> Unit,
    val onPro: () -> Unit,
    val onModels: () -> Unit,
    val onLicenses: () -> Unit,
    val onLicense: (OpenSourceComponent) -> Unit,
)

/** Registers Settings and the open-source licenses. */
fun NavGraphBuilder.settingsScreens(actions: SettingsNavigationActions) {
    composable<SettingsRoute> { SettingsDestination(actions) }
    composable<LicensesRoute> {
        ReadableWidth {
            LicensesScreen(onBack = actions.onBack, onLicense = actions.onLicense)
        }
    }
    composable<LicenseRoute> { entry ->
        val route = entry.toRoute<LicenseRoute>()
        LicenseTextScreen(title = route.title, file = route.file, onBack = actions.onBack)
    }
}

@Composable
private fun SettingsDestination(
    actions: SettingsNavigationActions,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val policy = stringResource(R.string.settings_privacy_policy_url)
    ReadableWidth {
        SettingsScreen(
            state = state,
            actions = SettingsActions(
                onBack = actions.onBack,
                onPro = actions.onPro,
                onRestore = viewModel::restore,
                onExportDefaults = viewModel::setExportDefaults,
                onClearCache = viewModel::clearCache,
                onModels = actions.onModels,
                onAdPrivacy = { context.findActivity()?.let(viewModel::adPrivacy) },
                onLicenses = actions.onLicenses,
                onPrivacyPolicy = { context.openPage(policy) },
            ),
        )
    }
}

/** Opens [url] in the browser; nothing happens on a phone without one. */
private fun Context.openPage(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // No browser on this phone.
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
