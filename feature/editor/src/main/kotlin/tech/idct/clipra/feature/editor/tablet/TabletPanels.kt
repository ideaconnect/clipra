package tech.idct.clipra.feature.editor.tablet

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.feature.editor.EditorTool

/**
 * What a feature's panel in the tablet editor (#70) works on: the selected clip ([clipId], null
 * when nothing is selected) at the editor's [playheadUs]. [onSelect] selects a clip the panel
 * made, so the editor and its inspector move to it; [onSeek] moves the editor's playhead;
 * [onOpen] opens a tool, in place when it has a library here, else on its own screen; and
 * [onMarks] shows times on the timeline, such as what Edit by text will cut.
 */
class PanelScope(
    val clipId: String?,
    val playheadUs: Long,
    val onSelect: (String) -> Unit,
    val onSeek: (Long) -> Unit = {},
    val onOpen: (EditorTool) -> Unit = {},
    val onMarks: (List<LongRange>) -> Unit = {},
)

/** A feature's panel, drawn in the tablet editor's pane given by its modifier. */
typealias TabletPanel = @Composable (scope: PanelScope, modifier: Modifier) -> Unit

/**
 * The feature panels the app puts in the tablet editor (#70), so the feature modules stay apart
 * and the editor's preview and timeline stay on screen (TabletText, TabletEffects and the other
 * boards): a [library] on the left for a rail place, and an [inspector] on the right for a clip
 * a feature edits (or with none selected, for a place such as Captions that edits the whole
 * project), given the rail place open (null for the media bin). Without one the editor
 * keeps its own: the media bin and the clip inspector, and a rail place opens its tool's screen.
 */
class TabletPanels(
    val library: (EditorTool) -> TabletPanel? = { null },
    /**
     * Whether a tool's library takes the width of the library and the inspector together, the
     * preview beside it (TabletTranscript).
     */
    val wide: (EditorTool) -> Boolean = { false },
    val inspector: (place: EditorTool?, clip: Clip?) -> TabletPanel? = { _, _ -> null },
)
