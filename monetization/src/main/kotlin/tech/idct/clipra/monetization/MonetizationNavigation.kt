package tech.idct.clipra.monetization

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
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.placeholder.Board

@Serializable
data object ProRoute

@Serializable
data object AdStartRoute

@Serializable
data object AdFinishRoute

/**
 * The boards this module builds, from design/README.md ("Export with two ads" and "Audio, Clipra
 * Pro and AI models").
 *
 * The two ad slots are not our screens: from M9 the ads SDK shows its own fullscreen ad at these
 * points, following docs/product/monetization.md.
 */
object MonetizationBoards {
    val pro = Board(id = "Pro", title = R.string.monetization_title_pro, route = ProRoute)
    val adStart = Board(
        id = "AdStart",
        title = R.string.monetization_title_ad_start,
        route = AdStartRoute,
    )
    val adFinish = Board(
        id = "AdFinish",
        title = R.string.monetization_title_ad_finish,
        route = AdFinishRoute,
    )

    val all = listOf(pro, adStart, adFinish)
}

/** Where the monetization screens lead; the app connects it. */
class MonetizationNavigationActions(val onClose: () -> Unit, val onBackToProjects: () -> Unit)

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.monetizationScreens(
    screen: @Composable (Board) -> Unit,
    actions: MonetizationNavigationActions,
) {
    composable<ProRoute> { ProDestination(actions) }
    composable<AdStartRoute> { screen(MonetizationBoards.adStart) }
    composable<AdFinishRoute> { screen(MonetizationBoards.adFinish) }
}

/**
 * Clipra Pro as a screen outside this module offers it (the tablet home's Go Pro, #71): owned,
 * and the price from Google Play.
 */
@Composable
fun proState(viewModel: ProViewModel = hiltViewModel()): ProUiState {
    val state by viewModel.state.collectAsStateWithLifecycle()
    return state
}

@Composable
private fun ProDestination(
    actions: MonetizationNavigationActions,
    viewModel: ProViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ProScreen(
        state = state,
        actions = ProActions(
            onClose = actions.onClose,
            onBuy = { context.findActivity()?.let(viewModel::buy) },
            onRestore = viewModel::restore,
            onBackToProjects = actions.onBackToProjects,
            onAdPrivacy = { context.findActivity()?.let(viewModel::adPrivacy) },
        ),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
