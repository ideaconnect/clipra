package tech.idct.clipra.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import tech.idct.clipra.R
import tech.idct.clipra.core.designsystem.placeholder.BackButton
import tech.idct.clipra.core.designsystem.placeholder.Board

/** Debug builds only: every phone board in one list, to open any screen directly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoardIndex(
    boards: List<Board>,
    onOpen: (Board) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_title_board_index)) },
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("index"),
        ) {
            items(boards, key = { it.id }) { board ->
                ListItem(
                    headlineContent = { Text(stringResource(board.title)) },
                    supportingContent = {
                        Text(text = board.id, fontFamily = FontFamily.Monospace)
                    },
                    modifier = Modifier
                        .clickable { onOpen(board) }
                        .testTag("index:${board.id}"),
                )
            }
        }
    }
}
