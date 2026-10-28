package tech.idct.clipra.monetization

import androidx.compose.runtime.Composable
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

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.monetizationScreens(screen: @Composable (Board) -> Unit) {
    composable<ProRoute> { screen(MonetizationBoards.pro) }
    composable<AdStartRoute> { screen(MonetizationBoards.adStart) }
    composable<AdFinishRoute> { screen(MonetizationBoards.adFinish) }
}
