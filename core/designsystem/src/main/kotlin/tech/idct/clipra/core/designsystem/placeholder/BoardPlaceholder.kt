package tech.idct.clipra.core.designsystem.placeholder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.R
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.theme.ClipraDimens

/**
 * Stands in for a board whose screen is not built yet: the screen title, the board's name in the
 * design, and buttons to the screens it leads to. Each one is replaced by its real screen as the
 * plan is built. Built only from theme components.
 */
@Composable
fun BoardPlaceholder(
    board: Board,
    links: List<Board>,
    onOpen: (Board) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding(),
    ) {
        ScreenHeader(title = stringResource(board.title), onNavigate = onBack)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("placeholder:${board.id}"),
            contentPadding = PaddingValues(
                horizontal = ClipraDimens.ScreenPadding,
                vertical = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(text = board.id, style = type.meta, color = colors.textSecondary)
            }
            item {
                Text(
                    text = stringResource(R.string.designsystem_placeholder_note),
                    style = type.body,
                    color = colors.textSecondary,
                )
            }
            items(links, key = { it.id }) { target ->
                SecondaryButton(
                    text = stringResource(target.title),
                    onClick = { onOpen(target) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("link:${target.id}"),
                )
            }
        }
    }
}
