package tech.idct.clipra.navigation

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
import tech.idct.clipra.R
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraCard
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.placeholder.Board
import tech.idct.clipra.core.designsystem.theme.ClipraDimens

/** Debug builds only: every phone board in one list, to open any screen directly. */
@Composable
internal fun BoardIndex(boards: List<Board>, onOpen: (Board) -> Unit, onBack: () -> Unit) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding(),
    ) {
        ScreenHeader(title = stringResource(R.string.app_title_board_index), onNavigate = onBack)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("index"),
            contentPadding = PaddingValues(
                horizontal = ClipraDimens.ScreenPadding,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(boards, key = { it.id }) { board ->
                ClipraCard(
                    onClick = { onOpen(board) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("index:${board.id}"),
                ) {
                    Text(text = stringResource(board.title), style = type.bodyStrong)
                    Text(text = board.id, style = type.meta, color = colors.textSecondary)
                }
            }
        }
    }
}
