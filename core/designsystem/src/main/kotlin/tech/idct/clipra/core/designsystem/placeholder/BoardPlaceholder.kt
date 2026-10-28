package tech.idct.clipra.core.designsystem.placeholder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.R

/**
 * Stands in for a board whose screen is not built yet: the screen title, the board's name in the
 * design, and buttons to the screens it leads to. Each one is replaced by its real screen as the
 * plan is built.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardPlaceholder(
    board: Board,
    links: List<Board>,
    onOpen: (Board) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(board.title)) },
                navigationIcon = { if (onBack != null) BackButton(onClick = onBack) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("placeholder:${board.id}"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = board.id,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.designsystem_placeholder_note),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(links, key = { it.id }) { target ->
                OutlinedButton(
                    onClick = { onOpen(target) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("link:${target.id}"),
                ) {
                    Text(stringResource(target.title))
                }
            }
        }
    }
}

/** The back arrow of a top bar. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.testTag("back")) {
        Icon(
            painter = painterResource(R.drawable.designsystem_ic_back),
            contentDescription = stringResource(R.string.designsystem_back),
        )
    }
}
