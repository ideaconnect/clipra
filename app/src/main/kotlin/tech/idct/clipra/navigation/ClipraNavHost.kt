package tech.idct.clipra.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import tech.idct.clipra.BuildConfig
import tech.idct.clipra.core.designsystem.gallery.DesignSystemGallery
import tech.idct.clipra.core.designsystem.placeholder.Board
import tech.idct.clipra.core.designsystem.placeholder.BoardPlaceholder
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.feature.audio.AudioActionsNavigation
import tech.idct.clipra.feature.audio.AudioRoute
import tech.idct.clipra.feature.audio.audioScreens
import tech.idct.clipra.feature.captions.CaptionsEditRoute
import tech.idct.clipra.feature.captions.CaptionsNavigationActions
import tech.idct.clipra.feature.captions.CaptionsSetupRoute
import tech.idct.clipra.feature.captions.TranscriptRoute
import tech.idct.clipra.feature.captions.captionsScreens
import tech.idct.clipra.feature.editor.EditorActions
import tech.idct.clipra.feature.editor.EditorRoute
import tech.idct.clipra.feature.editor.EditorTool
import tech.idct.clipra.feature.editor.LayersRoute
import tech.idct.clipra.feature.editor.ToolTarget
import tech.idct.clipra.feature.editor.editorScreens
import tech.idct.clipra.feature.effects.ColorRoute
import tech.idct.clipra.feature.effects.CutoutRoute
import tech.idct.clipra.feature.effects.EffectsActions
import tech.idct.clipra.feature.effects.FiltersRoute
import tech.idct.clipra.feature.effects.MasksRoute
import tech.idct.clipra.feature.effects.ReframeRoute
import tech.idct.clipra.feature.effects.TransitionsRoute
import tech.idct.clipra.feature.effects.effectsScreens
import tech.idct.clipra.feature.export.ExportActions
import tech.idct.clipra.feature.export.ExportDoneRoute
import tech.idct.clipra.feature.export.ExportProgressRoute
import tech.idct.clipra.feature.export.ExportRoute
import tech.idct.clipra.feature.export.exportScreens
import tech.idct.clipra.feature.home.HomeActions
import tech.idct.clipra.feature.home.NewProjectActions
import tech.idct.clipra.feature.home.NewProjectRoute
import tech.idct.clipra.feature.home.homeScreens
import tech.idct.clipra.feature.models.ModelsBoards
import tech.idct.clipra.feature.models.ModelsNavigationActions
import tech.idct.clipra.feature.models.ModelsRoute
import tech.idct.clipra.feature.models.OnboardingRoute
import tech.idct.clipra.feature.models.modelsScreens
import tech.idct.clipra.feature.text.TextNavigationActions
import tech.idct.clipra.feature.text.TextRoute
import tech.idct.clipra.feature.text.textScreens
import tech.idct.clipra.monetization.MonetizationBoards
import tech.idct.clipra.monetization.ProRoute
import tech.idct.clipra.monetization.monetizationScreens

/**
 * Connects the destinations of every feature module; feature modules don't know each other.
 * The first run starts at onboarding, as in design/screens/flows/first-run.png.
 */
@Composable
fun ClipraNavHost(
    navController: NavHostController = rememberNavController(),
    /** Changes each time the export notification is tapped. */
    openExport: Int = 0,
) {
    LaunchedEffect(openExport) {
        if (openExport > 0) navController.navigate(ExportProgressRoute())
    }
    val screen: @Composable (Board) -> Unit = { board ->
        BoardPlaceholder(
            board = board,
            links = placeholderLinks(board, debug = BuildConfig.DEBUG),
            onOpen = { target -> navController.open(from = board, to = target) },
            onBack = if (navController.previousBackStackEntry != null) {
                { navController.popBackStack() }
            } else {
                null
            },
        )
    }
    NavHost(navController = navController, startDestination = OnboardingRoute) {
        homeScreens(
            HomeActions(
                onNewProject = { navController.navigate(NewProjectRoute()) },
                onOpenProject = { navController.navigate(EditorRoute(it)) },
                onAutoCaptions = { navController.navigate(CaptionsSetupRoute()) },
                onTextCard = { navController.navigate(NewProjectRoute(textCard = true)) },
                onMosaic = {
                    navController.navigate(NewProjectRoute(layout = Layout.Grid2x2.name))
                },
                onModels = { navController.navigate(ModelsRoute) },
                onSettings = { navController.navigate(SettingsRoute) },
                onPro = { navController.navigate(ProRoute) },
                onScreenIndex = if (BuildConfig.DEBUG) {
                    { navController.navigate(BoardIndexRoute) }
                } else {
                    null
                },
            ),
            NewProjectActions(
                onBack = { navController.popBackStack() },
                // Back from the editor returns to Home, not to New project.
                onCreated = { id, textClipId ->
                    navController.navigate(EditorRoute(id)) {
                        popUpTo<NewProjectRoute> { inclusive = true }
                    }
                    if (textClipId != null) navController.navigate(TextRoute(id, textClipId))
                },
            ),
        )
        editorScreens(
            screen,
            EditorActions(
                onClose = { navController.popBackStack() },
                onExport = { navController.navigate(ExportRoute(it)) },
                onTool = { tool, target -> navController.navigate(toolRoute(tool, target)) },
            ),
        )
        effectsScreens(screen, EffectsActions(onDone = { navController.popBackStack() }))
        textScreens(screen, TextNavigationActions(onDone = { navController.popBackStack() }))
        captionsScreens(
            screen,
            CaptionsNavigationActions(
                onBack = { navController.popBackStack() },
                // Back from the captions returns to the editor, not to the setup.
                onTranscribed = { id ->
                    navController.navigate(CaptionsEditRoute(id)) {
                        popUpTo<CaptionsSetupRoute> { inclusive = true }
                    }
                },
                onEditByText = { navController.navigate(TranscriptRoute) },
            ),
        )
        audioScreens(screen, AudioActionsNavigation(onDone = { navController.popBackStack() }))
        exportScreens(
            screen,
            ExportActions(
                onBack = { navController.popBackStack() },
                onStarted = { id ->
                    navController.navigate(ExportProgressRoute(id)) {
                        popUpTo<ExportRoute> { inclusive = true }
                    }
                },
                onKeepEditing = {
                    if (!navController.popBackStack<EditorRoute>(
                            inclusive = false,
                        )
                    ) {
                        navController.popBackStack()
                    }
                },
                onDone = {
                    navController.navigate(ExportDoneRoute) {
                        popUpTo<ExportProgressRoute> { inclusive = true }
                    }
                },
                onPro = { navController.navigate(ProRoute) },
            ),
        )
        modelsScreens(screen, ModelsNavigationActions(onBack = { navController.popBackStack() }))
        monetizationScreens(screen)
        composable<SettingsRoute> { screen(AppBoards.settings) }
        composable<BoardIndexRoute> {
            BoardIndex(
                boards = AllBoards.phone + AppBoards.designSystem,
                onOpen = { navController.navigate(it.route) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<DesignSystemRoute> {
            DesignSystemGallery(onBack = { navController.popBackStack() })
        }
    }
}

/** Onboarding and the two ad slots are passed through: Back never returns to them. */
private val passThroughBoards = setOf(
    ModelsBoards.onboarding,
    MonetizationBoards.adStart,
    MonetizationBoards.adFinish,
)

private fun NavController.open(from: Board, to: Board) {
    navigate(to.route) {
        if (from in passThroughBoards) popUpTo(from.route) { inclusive = true }
    }
}

/** The screen each editor tool opens; most are placeholders until their issues land. */
private fun toolRoute(tool: EditorTool, target: ToolTarget): Any = when (tool) {
    EditorTool.Audio -> AudioRoute(target.projectId, target.clipId, target.playheadUs)
    EditorTool.Text -> TextRoute(target.projectId, target.clipId, target.playheadUs)
    EditorTool.Filters -> FiltersRoute(target.projectId, target.clipId, target.playheadUs)
    EditorTool.Transitions -> TransitionsRoute(target.projectId, target.clipId, target.playheadUs)
    EditorTool.Captions -> CaptionsSetupRoute(target.projectId)
    EditorTool.CaptionsEdit -> CaptionsEditRoute(target.projectId)
    EditorTool.Transcript -> TranscriptRoute
    EditorTool.Color -> ColorRoute
    EditorTool.Cutout -> CutoutRoute
    EditorTool.Reframe -> ReframeRoute
    EditorTool.Masks -> MasksRoute(target.projectId, target.clipId, target.playheadUs)
    EditorTool.Layer -> LayersRoute
}
