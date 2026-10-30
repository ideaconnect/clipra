package tech.idct.clipra.feature.effects.color

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.data.di.ApplicationScope
import tech.idct.clipra.core.data.project.OpenProjects
import tech.idct.clipra.core.data.project.ProjectSession
import tech.idct.clipra.core.engine.media.LutLibrary
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.Thumbnails
import tech.idct.clipra.core.model.AutoColor
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ColorGrade
import tech.idct.clipra.core.model.ColorStats
import tech.idct.clipra.core.model.CubeLut
import tech.idct.clipra.core.model.Luts
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.edit.PasteColor
import tech.idct.clipra.core.model.edit.SetColor
import tech.idct.clipra.core.model.mediaTimeAt
import javax.inject.Inject

/**
 * A LUT the LUT tab offers: a built-in look (named by its id) or an imported file (by its
 * file name), with the two colours of its swatch.
 */
data class LutChoice(val id: String, val name: String?, val swatch: List<Int>) {
    val builtIn: Boolean get() = id.startsWith(Luts.BUILT_IN)
}

/** What the Color board shows: the clip, its grade and the LUTs to pick from. */
data class ColorUiState(
    val project: Project? = null,
    val clip: Clip? = null,
    val loaded: Boolean = false,
    val playheadUs: Long = 0,
    val luts: List<LutChoice> = emptyList(),
    /** The last `.cube` file picked couldn't be used. */
    val importFailed: Boolean = false,
) {
    val grade: ColorGrade get() = clip?.color ?: ColorGrade()

    /** How many clips Paste would grade. */
    val pasteTargets: Int
        get() = if (project == null || clip == null) 0 else PasteColor.targets(project, clip.id)
}

@HiltViewModel
class ColorViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val projects: OpenProjects,
    val mediaCache: MediaCache,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState["projectId"]
    private val clipId: String? = savedState["clipId"]

    /** Where the editor's playhead was; the preview and Auto look at this moment. */
    val playheadUs: Long = savedState["playheadUs"] ?: 0L

    private val library = LutLibrary.of(context)
    private var session: ProjectSession? = null
    private val mutableState = MutableStateFlow(ColorUiState(playheadUs = playheadUs))

    val state: StateFlow<ColorUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { loadLuts() }
        viewModelScope.launch {
            val opened = projectId?.let { projects.acquire(it) }
            session = opened
            if (opened == null) {
                mutableState.update { it.copy(loaded = true) }
                return@launch
            }
            opened.state.collect { session ->
                mutableState.update {
                    it.copy(
                        project = session.project,
                        clip = clipId?.let { id -> session.project.findClip(id)?.second },
                        loaded = true,
                    )
                }
            }
        }
    }

    /** Commits [grade]: a slider let go, a LUT or a hue picked. */
    fun setGrade(grade: ColorGrade) {
        val clip = state.value.clip ?: return
        if (grade != state.value.grade) session?.edit(SetColor(clip.id, grade))
    }

    fun reset() {
        val clip = state.value.clip ?: return
        if (clip.color != null) session?.edit(SetColor(clip.id, null))
    }

    fun paste() {
        val clip = state.value.clip ?: return
        if (state.value.pasteTargets > 0) session?.edit(PasteColor(clip.id))
    }

    /** Balances the frame at the playhead: exposure, contrast, saturation and casts. */
    fun auto() {
        val project = state.value.project ?: return
        val clip = state.value.clip ?: return
        val asset = clip.source?.let { project.asset(it.mediaId) } ?: return
        viewModelScope.launch {
            val offset = (playheadUs - clip.startUs).coerceIn(0, clip.durationUs - 1)
            val time = clip.mediaTimeAt(offset) ?: 0
            val still = withContext(Dispatchers.IO) {
                Thumbnails.still(context, asset, mediaCache, time, AUTO_SIDE)
            } ?: return@launch
            val pixels = IntArray(still.width * still.height)
            still.getPixels(pixels, 0, still.width, 0, 0, still.width, still.height)
            val auto = withContext(Dispatchers.Default) { AutoColor.grade(ColorStats.of(pixels)) }
            setGrade(
                state.value.grade.copy(
                    exposure = auto.exposure,
                    contrast = auto.contrast,
                    saturation = auto.saturation,
                    temperature = auto.temperature,
                    tint = auto.tint,
                ),
            )
        }
    }

    /** Imports a `.cube` file and grades the clip with it. */
    fun importLut(uri: Uri) {
        viewModelScope.launch {
            val entry = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    var name = "LUT.cube"
                    var size = 0L
                    resolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
                            if (sizeColumn >= 0) size = cursor.getLong(sizeColumn)
                        }
                    }
                    require(size <= LutLibrary.MAX_BYTES)
                    val text = resolver.openInputStream(uri)!!.use { it.reader().readText() }
                    library.import(name, text)
                }.getOrNull()
            }
            mutableState.update { it.copy(importFailed = entry == null) }
            if (entry != null) {
                loadLuts()
                setGrade(state.value.grade.copy(lut = entry.id))
            }
        }
    }

    fun dismissImportError() = mutableState.update { it.copy(importFailed = false) }

    private suspend fun loadLuts() {
        val luts = withContext(Dispatchers.IO) {
            val builtIn = Luts.builtInNames.map { name ->
                val id = Luts.builtInId(name)
                LutChoice(id, null, swatch(Luts.builtIn(id)))
            }
            builtIn + library.list().map { LutChoice(it.id, it.name, swatch(library.lut(it.id))) }
        }
        mutableState.update { it.copy(luts = luts) }
    }

    private fun swatch(lut: CubeLut?): List<Int> = lut?.let { table ->
        Luts.swatch(table).map { rgb ->
            val (r, g, b) = rgb.map { (it.coerceIn(0f, 1f) * 255).toInt() }
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }.orEmpty()

    override fun onCleared() {
        if (session != null && projectId != null) appScope.launch { projects.release(projectId) }
    }

    private companion object {
        const val AUTO_SIDE = 256
    }
}
