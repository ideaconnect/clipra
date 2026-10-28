package tech.idct.clipra.core.designsystem.placeholder

import androidx.annotation.StringRes

/**
 * A board of the design (design/README.md) and the destination that shows it.
 *
 * @property id the board's name in the design, for example `NewProject`
 * @property title the screen title
 * @property route the type-safe navigation route of the destination
 */
data class Board(
    val id: String,
    @param:StringRes val title: Int,
    val route: Any,
)
