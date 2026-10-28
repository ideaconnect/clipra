package tech.idct.clipra.navigation

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

/**
 * Where each board leads, following the design's flows (design/screens/flows/). Placeholders
 * show these as buttons; real screens bring their own navigation, and their entries stay here as
 * the design's flow for the reachability test. [debug] adds the screen index to Home.
 */
internal fun placeholderLinks(board: Board, debug: Boolean): List<Board> {
    val links = flows[board].orEmpty()
    return if (debug && board == HomeBoards.main) links + AppBoards.boardIndex else links
}

private val flows: Map<Board, List<Board>> = mapOf(
    ModelsBoards.onboarding to listOf(HomeBoards.main),
    HomeBoards.main to listOf(
        HomeBoards.newProject,
        CaptionsBoards.captionsSetup,
        TextBoards.text,
        EditorBoards.editor,
        ModelsBoards.models,
        AppBoards.settings,
        MonetizationBoards.pro,
    ),
    HomeBoards.newProject to listOf(EditorBoards.editor),
    // The editor's main tools, in the order of docs/product/spec.md section 3, then Export.
    EditorBoards.editor to listOf(
        AudioBoards.audio,
        TextBoards.text,
        EditorBoards.layers,
        EffectsBoards.filters,
        EffectsBoards.transitions,
        CaptionsBoards.captionsSetup,
        CaptionsBoards.transcript,
        EffectsBoards.color,
        EffectsBoards.cutout,
        EffectsBoards.reframe,
        EffectsBoards.masks,
        ExportBoards.export,
    ),
    CaptionsBoards.captionsSetup to listOf(CaptionsBoards.captionsEdit),
    CaptionsBoards.captionsEdit to listOf(CaptionsBoards.transcript),
    ExportBoards.export to listOf(MonetizationBoards.adStart),
    MonetizationBoards.adStart to listOf(ExportBoards.exportProgress),
    ExportBoards.exportProgress to listOf(MonetizationBoards.adFinish),
    MonetizationBoards.adFinish to listOf(ExportBoards.exportDone),
    ExportBoards.exportDone to listOf(EditorBoards.editor, MonetizationBoards.pro),
)
