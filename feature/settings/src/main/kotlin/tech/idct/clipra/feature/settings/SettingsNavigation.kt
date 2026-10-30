package tech.idct.clipra.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

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
        LicensesScreen(onBack = actions.onBack, onLicense = actions.onLicense)
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
        ),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
