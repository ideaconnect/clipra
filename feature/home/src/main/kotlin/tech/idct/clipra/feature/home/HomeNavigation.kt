package tech.idct.clipra.feature.home

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.data.project.ProjectSummary
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.adaptive.LocalWindowLayout
import tech.idct.clipra.core.designsystem.adaptive.WindowLayout
import tech.idct.clipra.core.designsystem.placeholder.Board
import tech.idct.clipra.core.model.Layout

@Serializable
data object MainRoute

/**
 * New project, optionally with a layout preselected ("Mosaic layout" on Home), or starting with a
 * text card ("Text card" on Home).
 */
@Serializable
data class NewProjectRoute(val layout: String? = null, val textCard: Boolean = false)

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

/**
 * Where New project leads: back, or into the editor with the created project and, when it
 * starts with a text card, that card's clip.
 */
class NewProjectActions(
    val onBack: () -> Unit,
    val onCreated: (projectId: String, textClipId: String?) -> Unit,
    /** The tablet's "Import from Gallery" (#71): the new project opens on the photo picker. */
    val onCreatedToImport: (projectId: String) -> Unit = { onCreated(it, null) },
)

/** Registers this module's destinations: Home and New project. */
fun NavGraphBuilder.homeScreens(actions: HomeActions, newProject: NewProjectActions) {
    composable<MainRoute> { HomeRoute(actions, newProject) }
    composable<NewProjectRoute> { NewProjectRoute(newProject) }
}

@Composable
private fun NewProjectRoute(
    actions: NewProjectActions,
    viewModel: NewProjectViewModel = hiltViewModel(),
) {
    val format by viewModel.format.collectAsStateWithLifecycle()
    val name = stringResource(R.string.home_new_default_name)
    val title = stringResource(R.string.home_new_card_title)
    NewProjectScreen(
        format = format,
        onFormatChange = viewModel::change,
        onCreate = { viewModel.create(name, title, actions.onCreated) },
        onBack = actions.onBack,
        fourKSupported = viewModel.fourKSupported,
    )
}

@Composable
private fun HomeRoute(
    actions: HomeActions,
    newProject: NewProjectActions,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val installed by viewModel.installedModels.collectAsStateWithLifecycle()
    val whisper = stringResource(R.string.home_ai_whisper)
    val locale = LocalConfiguration.current.locales[0]
    val aiStatus = installed?.let { aiStatus(it, whisper, locale) }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }
    val copyName = stringResource(R.string.home_copy_name)
    val projectActions = ProjectActions(
        onRename = { renaming = it },
        onDuplicate = { viewModel.duplicate(it.id, copyName.format(it.name)) },
        onDelete = { deleting = it },
    )

    CompositionLocalProvider(LocalProjectPictures provides viewModel.pictures) {
        if (LocalWindowLayout.current == WindowLayout.Tablet) {
            // New project sits beside the projects (#71), with its own state.
            val creating: NewProjectViewModel = hiltViewModel()
            val format by creating.format.collectAsStateWithLifecycle()
            val name = stringResource(R.string.home_new_default_name)
            val title = stringResource(R.string.home_new_card_title)
            TabletHomeScreen(
                recent = recent,
                nowMillis = now,
                aiStatus = aiStatus,
                pro = actions.proOffer(),
                actions = HomeActions(
                    onNewProject = actions.onNewProject,
                    onOpenProject = actions.onOpenProject,
                    onAutoCaptions = actions.onAutoCaptions,
                    onTextCard = actions.onTextCard,
                    onMosaic = { creating.preset(Layout.Grid2x2) },
                    onModels = actions.onModels,
                    onSettings = actions.onSettings,
                    onPro = actions.onPro,
                ),
                projectActions = projectActions,
                panel = NewProjectPanel(
                    format = format,
                    onFormatChange = creating::change,
                    onCreate = { creating.create(name, title, newProject.onCreated) },
                    fourKSupported = creating.fourKSupported,
                    onTextCard = {
                        creating.create(name, title, newProject.onCreated, withTextCard = true)
                    },
                    onImport = {
                        creating.create(
                            name,
                            title,
                            { id, _ -> newProject.onCreatedToImport(id) },
                            withTextCard = false,
                        )
                    },
                ),
            )
        } else {
            HomeScreen(
                recent = recent,
                nowMillis = now,
                aiStatus = aiStatus,
                actions = actions,
                projectActions = projectActions,
            )
        }
    }

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
