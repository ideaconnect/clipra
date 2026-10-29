package tech.idct.clipra.feature.models

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.placeholder.Board

@Serializable
data object OnboardingRoute

@Serializable
data object ModelsRoute

/**
 * The boards this module builds, from design/README.md ("Start and edit" and "Audio, Clipra Pro and
 * AI models").
 */
object ModelsBoards {
    val onboarding = Board(
        id = "Onboarding",
        title = R.string.models_title_onboarding,
        route = OnboardingRoute,
    )
    val models = Board(id = "Models", title = R.string.models_title_models, route = ModelsRoute)

    val all = listOf(onboarding, models)
}

/** Where the models screens lead; the app connects it. */
class ModelsNavigationActions(val onBack: () -> Unit)

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.modelsScreens(
    screen: @Composable (Board) -> Unit,
    actions: ModelsNavigationActions,
) {
    composable<OnboardingRoute> { screen(ModelsBoards.onboarding) }
    composable<ModelsRoute> { ModelsDestination(actions.onBack) }
}

@Composable
private fun ModelsDestination(onBack: () -> Unit, viewModel: ModelsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ModelsScreen(
        state = state,
        actions = ModelsActions(
            onBack = onBack,
            onDownload = viewModel::download,
            onCancel = viewModel::cancel,
            onRemove = viewModel::remove,
            onWifiOnly = viewModel::setWifiOnly,
            onUseAccelerator = viewModel::setUseAccelerator,
        ),
    )
}
