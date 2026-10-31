package tech.idct.clipra.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import tech.idct.clipra.BuildConfig
import tech.idct.clipra.core.designsystem.gallery.DesignSystemGallery
import tech.idct.clipra.core.designsystem.placeholder.Board
import tech.idct.clipra.core.designsystem.placeholder.BoardPlaceholder
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.core.model.edit.takesFilters
import tech.idct.clipra.feature.audio.AudioActionsNavigation
import tech.idct.clipra.feature.audio.AudioInspectorPanel
import tech.idct.clipra.feature.audio.AudioRoute
import tech.idct.clipra.feature.audio.audioScreens
import tech.idct.clipra.feature.captions.CaptionsEditRoute
import tech.idct.clipra.feature.captions.CaptionsLookPanel
import tech.idct.clipra.feature.captions.CaptionsNavigationActions
import tech.idct.clipra.feature.captions.CaptionsSetupRoute
import tech.idct.clipra.feature.captions.CaptionsTranscriptPanel
import tech.idct.clipra.feature.captions.TranscriptRoute
import tech.idct.clipra.feature.captions.captionsScreens
import tech.idct.clipra.feature.editor.EditorActions
import tech.idct.clipra.feature.editor.EditorRoute
import tech.idct.clipra.feature.editor.EditorTool
import tech.idct.clipra.feature.editor.LayersRoute
import tech.idct.clipra.feature.editor.ToolTarget
import tech.idct.clipra.feature.editor.editorScreens
import tech.idct.clipra.feature.editor.tablet.TabletPanel
import tech.idct.clipra.feature.editor.tablet.TabletPanels
import tech.idct.clipra.feature.effects.ColorRoute
import tech.idct.clipra.feature.effects.CutoutRoute
import tech.idct.clipra.feature.effects.EffectStackPanel
import tech.idct.clipra.feature.effects.EffectStackRoute
import tech.idct.clipra.feature.effects.EffectsActions
import tech.idct.clipra.feature.effects.FiltersLibraryPanel
import tech.idct.clipra.feature.effects.FiltersRoute
import tech.idct.clipra.feature.effects.MasksRoute
import tech.idct.clipra.feature.effects.ReframeRoute
import tech.idct.clipra.feature.effects.TransitionsRoute
import tech.idct.clipra.feature.effects.effectsScreens
import tech.idct.clipra.feature.effects.filters.filterName
import tech.idct.clipra.feature.export.ExportActions
import tech.idct.clipra.feature.export.ExportDoneRoute
import tech.idct.clipra.feature.export.ExportProgressRoute
import tech.idct.clipra.feature.export.ExportRoute
import tech.idct.clipra.feature.export.exportScreens
import tech.idct.clipra.feature.home.HomeActions
import tech.idct.clipra.feature.home.MainRoute
import tech.idct.clipra.feature.home.NewProjectActions
import tech.idct.clipra.feature.home.NewProjectRoute
import tech.idct.clipra.feature.home.ProOffer
import tech.idct.clipra.feature.home.homeScreens
import tech.idct.clipra.feature.models.ModelsBoards
import tech.idct.clipra.feature.models.ModelsNavigationActions
import tech.idct.clipra.feature.models.ModelsRoute
import tech.idct.clipra.feature.models.OnboardingRoute
import tech.idct.clipra.feature.models.modelsScreens
import tech.idct.clipra.feature.settings.LicenseRoute
import tech.idct.clipra.feature.settings.LicensesRoute
import tech.idct.clipra.feature.settings.SettingsNavigationActions
import tech.idct.clipra.feature.settings.SettingsRoute
import tech.idct.clipra.feature.settings.settingsScreens
import tech.idct.clipra.feature.text.TextInspectorPanel
import tech.idct.clipra.feature.text.TextLibraryPanel
import tech.idct.clipra.feature.text.TextNavigationActions
import tech.idct.clipra.feature.text.TextRoute
import tech.idct.clipra.feature.text.textScreens
import tech.idct.clipra.monetization.MonetizationBoards
import tech.idct.clipra.monetization.MonetizationNavigationActions
import tech.idct.clipra.monetization.ProRoute
import tech.idct.clipra.monetization.monetizationScreens
import tech.idct.clipra.monetization.proState

/**
 * Connects the destinations of every feature module; feature modules don't know each other.
 * The first run starts at onboarding, as in design/screens/flows/first-run.png.
 */
@Composable
fun ClipraNavHost(
    navController: NavHostController = rememberNavController(),
    /** Changes each time the export notification is tapped. */
    openExport: Int = 0,
    /** Called the first time the projects show in this run, after onboarding. */
    onProjectsShown: () -> Unit = {},
    /** The first run starts at onboarding; later runs at the projects. */
    onboarded: Boolean = false,
) {
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.first { it.destination.hasRoute<MainRoute>() }
        onProjectsShown()
    }
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
    NavHost(
        navController = navController,
        startDestination = if (onboarded) MainRoute else OnboardingRoute,
    ) {
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
                proOffer = { proState().let { ProOffer(owned = it.owned, price = it.price) } },
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
                onCreatedToImport = { id ->
                    navController.navigate(EditorRoute(id, pickMedia = true))
                },
            ),
        )
        editorScreens(
            screen,
            EditorActions(
                onClose = { navController.popBackStack() },
                onExport = { id, playheadUs ->
                    navController.navigate(ExportRoute(id, playheadUs))
                },
                onTool = { tool, target -> navController.navigate(toolRoute(tool, target)) },
                effectName = { filterName(it) },
                tabletPanels = tabletPanels,
            ),
        )
        effectsScreens(
            screen,
            EffectsActions(
                onDone = { navController.popBackStack() },
                // The copy opens in place of the project it came from; Back goes Home.
                onReframed = { id ->
                    navController.navigate(EditorRoute(id)) {
                        popUpTo<EditorRoute> { inclusive = true }
                    }
                },
            ),
        )
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
                onEditByText = { id -> navController.navigate(TranscriptRoute(id)) },
                onAutoCaptions = { id ->
                    navController.navigate(CaptionsSetupRoute(id)) {
                        popUpTo<TranscriptRoute> { inclusive = true }
                    }
                },
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
        modelsScreens(
            screen,
            ModelsNavigationActions(
                onBack = { navController.popBackStack() },
                onOnboarded = {
                    navController.navigate(MainRoute) {
                        popUpTo<OnboardingRoute> { inclusive = true }
                    }
                },
            ),
        )
        monetizationScreens(
            screen,
            MonetizationNavigationActions(
                onClose = { navController.popBackStack() },
                onBackToProjects = {
                    if (!navController.popBackStack<MainRoute>(inclusive = false)) {
                        navController.navigate(MainRoute) {
                            popUpTo<ProRoute> { inclusive = true }
                        }
                    }
                },
            ),
        )
        settingsScreens(
            SettingsNavigationActions(
                onBack = { navController.popBackStack() },
                onPro = { navController.navigate(ProRoute) },
                onModels = { navController.navigate(ModelsRoute) },
                onLicenses = { navController.navigate(LicensesRoute) },
                onLicense = { component ->
                    component.file?.let { navController.navigate(LicenseRoute(component.name, it)) }
                },
            ),
        )
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

/**
 * The feature panels the tablet editor hosts (#70): Text's library on its rail place and its
 * inspector for text clips; the Filters library on Effects, with the effect stack for the clip;
 * on the editor's own Audio place, the Audio inspector for a clip with sound; and on Captions the
 * transcript, with the captions' look beside it.
 */
private val tabletPanels = TabletPanels(
    library = { tool ->
        when (tool) {
            EditorTool.Text -> textLibrary
            EditorTool.Filters -> filtersLibrary
            EditorTool.Captions -> captionsLibrary
            else -> null
        }
    },
    inspector = { place, clip ->
        when {
            place == EditorTool.Captions -> captionsLook

            clip == null -> null

            clip.type == ClipType.Text || clip.type == ClipType.TextCard -> textInspector

            place == EditorTool.Filters && clip.takesFilters() -> effectStack

            place == EditorTool.Audio &&
                (clip.type == ClipType.Audio || clip.type == ClipType.Video) -> audioInspector

            else -> null
        }
    },
)

private val textLibrary: TabletPanel = { scope, modifier ->
    TextLibraryPanel(scope.clipId, scope.playheadUs, scope.onSelect, modifier)
}

private val textInspector: TabletPanel = { scope, modifier ->
    scope.clipId?.let { TextInspectorPanel(it, modifier) }
}

private val filtersLibrary: TabletPanel = { scope, modifier ->
    FiltersLibraryPanel(scope.clipId, scope.playheadUs, modifier)
}

private val effectStack: TabletPanel = { scope, modifier ->
    scope.clipId?.let { EffectStackPanel(it, modifier) }
}

private val audioInspector: TabletPanel = { scope, modifier ->
    scope.clipId?.let { AudioInspectorPanel(it, modifier) }
}

private val captionsLibrary: TabletPanel = { scope, modifier ->
    CaptionsTranscriptPanel(
        playheadUs = scope.playheadUs,
        onSeek = scope.onSeek,
        onEditByText = { scope.onOpen(EditorTool.Transcript) },
        onAutoCaptions = { scope.onOpen(EditorTool.Captions) },
        modifier = modifier,
    )
}

private val captionsLook: TabletPanel = { _, modifier -> CaptionsLookPanel(modifier) }

/** The screen each editor tool opens; most are placeholders until their issues land. */
private fun toolRoute(tool: EditorTool, target: ToolTarget): Any = when (tool) {
    EditorTool.Audio -> AudioRoute(target.projectId, target.clipId, target.playheadUs)
    EditorTool.Text -> TextRoute(target.projectId, target.clipId, target.playheadUs)
    EditorTool.Filters -> FiltersRoute(target.projectId, target.clipId, target.playheadUs)
    EditorTool.Transitions -> TransitionsRoute(target.projectId, target.clipId, target.playheadUs)
    EditorTool.Captions -> CaptionsSetupRoute(target.projectId)
    EditorTool.CaptionsEdit -> CaptionsEditRoute(target.projectId)
    EditorTool.Transcript -> TranscriptRoute(target.projectId)
    EditorTool.Color -> ColorRoute(target.projectId, target.clipId, target.playheadUs)
    EditorTool.Cutout -> CutoutRoute(target.projectId, target.clipId, target.playheadUs)
    EditorTool.Reframe -> ReframeRoute(target.projectId, target.playheadUs)
    EditorTool.Masks -> MasksRoute(target.projectId, target.clipId, target.playheadUs)
    EditorTool.Effects -> EffectStackRoute(target.projectId, target.clipId!!, target.playheadUs)
    EditorTool.Layer, EditorTool.Speed, EditorTool.Animate, EditorTool.Stabilize -> LayersRoute
}
