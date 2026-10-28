package tech.idct.clipra.feature.models

import androidx.compose.runtime.Composable
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

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.modelsScreens(screen: @Composable (Board) -> Unit) {
    composable<OnboardingRoute> { screen(ModelsBoards.onboarding) }
    composable<ModelsRoute> { screen(ModelsBoards.models) }
}
