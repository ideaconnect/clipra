package tech.idct.clipra.feature.home

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.data.project.ProjectSummary
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.placeholder.Board

@Serializable
data object MainRoute

/** New project, optionally with a layout preselected ("Mosaic layout" on Home). */
@Serializable
data class NewProjectRoute(val layout: String? = null)

/**
 * The boards this module builds, from design/README.md ("Start and edit").
 */
object HomeBoards {
    val main = Board(id = "Main", title = R.string.home_title_main, route = MainRoute)
    val newProject = Board(
        id = "NewProject",
        title = R.string.home_title_new_project,
        route = NewProjectRoute(),
    )

    val all = listOf(main, newProject)
}

/** Where New project leads: back, or into the editor with the created project. */
class NewProjectActions(val onBack: () -> Unit, val onCreated: (projectId: String) -> Unit)

/** Registers this module's destinations: Home and New project. */
fun NavGraphBuilder.homeScreens(actions: HomeActions, newProject: NewProjectActions) {
    composable<MainRoute> { HomeRoute(actions) }
    composable<NewProjectRoute> { NewProjectRoute(newProject) }
}

@Composable
private fun NewProjectRoute(
    actions: NewProjectActions,
    viewModel: NewProjectViewModel = hiltViewModel(),
) {
    val format by viewModel.format.collectAsStateWithLifecycle()
    val name = stringResource(R.string.home_new_default_name)
    NewProjectScreen(
        format = format,
        onFormatChange = viewModel::change,
        onCreate = { viewModel.create(name, actions.onCreated) },
        onBack = actions.onBack,
        fourKSupported = viewModel.fourKSupported,
    )
}

@Composable
private fun HomeRoute(actions: HomeActions, viewModel: HomeViewModel = hiltViewModel()) {
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }
    val copyName = stringResource(R.string.home_copy_name)

    HomeScreen(
        recent = recent,
        nowMillis = now,
        aiStatus = null,
        actions = actions,
        projectActions = ProjectActions(
            onRename = { renaming = it },
            onDuplicate = { viewModel.duplicate(it.id, copyName.format(it.name)) },
            onDelete = { deleting = it },
        ),
    )

    renaming?.let { project ->
        RenameDialog(
            current = project.name,
            onRename = {
                viewModel.rename(project.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { project ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.home_delete_title, project.name)) },
            text = { Text(stringResource(R.string.home_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(project.id)
                    deleting = null
                }) {
                    Text(
                        stringResource(R.string.home_menu_delete),
                        color = ClipraTheme.colors.danger,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.home_cancel))
                }
            },
        )
    }
}

@Composable
private fun RenameDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.home_rename_label)) },
                singleLine = true,
                modifier = Modifier.testTag("rename-field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.home_menu_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel)) }
        },
    )
}
