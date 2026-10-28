package tech.idct.clipra.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import tech.idct.clipra.BuildConfig
import tech.idct.clipra.core.designsystem.placeholder.Board
import tech.idct.clipra.core.designsystem.placeholder.BoardPlaceholder
import tech.idct.clipra.feature.audio.audioScreens
import tech.idct.clipra.feature.captions.captionsScreens
import tech.idct.clipra.feature.editor.editorScreens
import tech.idct.clipra.feature.effects.effectsScreens
import tech.idct.clipra.feature.export.exportScreens
import tech.idct.clipra.feature.home.homeScreens
import tech.idct.clipra.feature.models.ModelsBoards
import tech.idct.clipra.feature.models.OnboardingRoute
import tech.idct.clipra.feature.models.modelsScreens
import tech.idct.clipra.feature.text.textScreens
import tech.idct.clipra.monetization.MonetizationBoards
import tech.idct.clipra.monetization.monetizationScreens

/**
 * Connects the destinations of every feature module; feature modules don't know each other.
 * The first run starts at onboarding, as in design/screens/flows/first-run.png.
 */
@Composable
fun ClipraNavHost(navController: NavHostController = rememberNavController()) {
    val screen: @Composable (Board) -> Unit = { board ->
        BoardPlaceholder(
            board = board,
            links = placeholderLinks(board, debug = BuildConfig.DEBUG),
            onOpen = { target -> navController.open(from = board, to = target) },
            onBack = if (navController.previousBackStackEntry != null) {
                { navController.popBackStack() }
            } else {
                null
            },
        )
    }
    NavHost(navController = navController, startDestination = OnboardingRoute) {
        homeScreens(screen)
        editorScreens(screen)
        effectsScreens(screen)
        textScreens(screen)
        captionsScreens(screen)
        audioScreens(screen)
        exportScreens(screen)
        modelsScreens(screen)
        monetizationScreens(screen)
        composable<SettingsRoute> { screen(AppBoards.settings) }
        composable<BoardIndexRoute> {
            BoardIndex(
                boards = AllBoards.phone,
                onOpen = { navController.navigate(it.route) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** Onboarding and the two ad slots are passed through: Back never returns to them. */
private val passThroughBoards = setOf(
    ModelsBoards.onboarding,
    MonetizationBoards.adStart,
    MonetizationBoards.adFinish,
)

private fun NavController.open(from: Board, to: Board) {
    navigate(to.route) {
        if (from in passThroughBoards) popUpTo(from.route) { inclusive = true }
    }
}
