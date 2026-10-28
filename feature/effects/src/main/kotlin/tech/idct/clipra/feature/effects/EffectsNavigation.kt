package tech.idct.clipra.feature.effects

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.placeholder.Board

@Serializable
data object FiltersRoute

@Serializable
data object TransitionsRoute

@Serializable
data object MasksRoute

@Serializable
data object ColorRoute

@Serializable
data object CutoutRoute

@Serializable
data object ReframeRoute

/**
 * The boards this module builds, from design/README.md ("Effects and look" and "On-device AI").
 */
object EffectsBoards {
    val filters = Board(
        id = "Filters",
        title = R.string.effects_title_filters,
        route = FiltersRoute,
    )
    val transitions = Board(
        id = "Transitions",
        title = R.string.effects_title_transitions,
        route = TransitionsRoute,
    )
    val masks = Board(id = "Masks", title = R.string.effects_title_masks, route = MasksRoute)
    val color = Board(id = "Color", title = R.string.effects_title_color, route = ColorRoute)
    val cutout = Board(id = "Cutout", title = R.string.effects_title_cutout, route = CutoutRoute)
    val reframe = Board(
        id = "Reframe",
        title = R.string.effects_title_reframe,
        route = ReframeRoute,
    )

    val all = listOf(filters, transitions, masks, color, cutout, reframe)
}

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.effectsScreens(screen: @Composable (Board) -> Unit) {
    composable<FiltersRoute> { screen(EffectsBoards.filters) }
    composable<TransitionsRoute> { screen(EffectsBoards.transitions) }
    composable<MasksRoute> { screen(EffectsBoards.masks) }
    composable<ColorRoute> { screen(EffectsBoards.color) }
    composable<CutoutRoute> { screen(EffectsBoards.cutout) }
    composable<ReframeRoute> { screen(EffectsBoards.reframe) }
}
