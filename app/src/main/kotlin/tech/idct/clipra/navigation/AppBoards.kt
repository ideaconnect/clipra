package tech.idct.clipra.navigation

import kotlinx.serialization.Serializable
import tech.idct.clipra.R
import tech.idct.clipra.core.designsystem.placeholder.Board
import tech.idct.clipra.feature.audio.AudioBoards
import tech.idct.clipra.feature.captions.CaptionsBoards
import tech.idct.clipra.feature.editor.EditorBoards
import tech.idct.clipra.feature.effects.EffectsBoards
import tech.idct.clipra.feature.export.ExportBoards
import tech.idct.clipra.feature.home.HomeBoards
import tech.idct.clipra.feature.models.ModelsBoards
import tech.idct.clipra.feature.text.TextBoards
import tech.idct.clipra.monetization.MonetizationBoards

@Serializable
data object SettingsRoute

@Serializable
data object BoardIndexRoute

/** Destinations of the app module itself. */
internal object AppBoards {
    /** Settings has no board in the design yet (M11-settings). */
    val settings = Board(
        id = "Settings",
        title = R.string.app_title_settings,
        route = SettingsRoute,
    )

    /** Debug builds only: every phone board in one list, to open any screen directly. */
    val boardIndex = Board(
        id = "BoardIndex",
        title = R.string.app_title_board_index,
        route = BoardIndexRoute,
    )
}

internal object AllBoards {
    /** Every phone board in design/README.md, in the order it lists them. */
    val phone: List<Board> = listOf(
        ModelsBoards.onboarding,
        HomeBoards.main,
        HomeBoards.newProject,
        EditorBoards.editor,
        EditorBoards.layers,
        EffectsBoards.filters,
        EffectsBoards.transitions,
        TextBoards.text,
        EffectsBoards.masks,
        EffectsBoards.color,
        CaptionsBoards.captionsSetup,
        CaptionsBoards.captionsEdit,
        CaptionsBoards.transcript,
        EffectsBoards.cutout,
        EffectsBoards.reframe,
        ExportBoards.export,
        MonetizationBoards.adStart,
        ExportBoards.exportProgress,
        MonetizationBoards.adFinish,
        ExportBoards.exportDone,
        AudioBoards.audio,
        MonetizationBoards.pro,
        ModelsBoards.models,
    )
}
