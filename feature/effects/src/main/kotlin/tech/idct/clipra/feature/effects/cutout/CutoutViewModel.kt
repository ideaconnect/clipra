package tech.idct.clipra.feature.effects.cutout

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.data.di.ApplicationScope
import tech.idct.clipra.core.data.project.OpenProjects
import tech.idct.clipra.core.data.project.ProjectSession
import tech.idct.clipra.core.engine.media.Cutouts
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.Thumbnails
import tech.idct.clipra.core.model.ChromaKey
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Cutout
import tech.idct.clipra.core.model.CutoutMath
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.edit.SetCutout
import tech.idct.clipra.core.model.mediaTimeAt
import javax.inject.Inject

/** Where finding the people in a clip stands (#50). */
sealed interface PersonSearch {
    /** The masks are being made: how far along, 0 to 1. */
    data class Finding(val progress: Float) : PersonSearch

    data object Found : PersonSearch

    data object NotFound : PersonSearch
}

/**
 * What the Cutout board shows (#50, #51): the clip, its cutout, the search for people, and the
 * clip's own picture at the playhead (for the chroma key's Original and eyedropper).
 */
data class CutoutUiState(
    val project: Project? = null,
    val clip: Clip? = null,
    val loaded: Boolean = false,
    val search: PersonSearch = PersonSearch.Finding(0f),
    val original: Bitmap? = null,
) {
    val cutout: Cutout get() = clip?.cutout ?: Cutout()
}

@HiltViewModel
class CutoutViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val projects: OpenProjects,
    private val cutouts: Cutouts,
    val mediaCache: MediaCache,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState["projectId"]
    private val clipId: String? = savedState["clipId"]

    /** Where the editor's playhead was; the preview shows this moment. */
    val playheadUs: Long = savedState["playheadUs"] ?: 0L

    private var session: ProjectSession? = null
    private val mutableState = MutableStateFlow(CutoutUiState())
    private val original = MutableStateFlow<Bitmap?>(null)

    val state: StateFlow<CutoutUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val opened = projectId?.let { projects.acquire(it) }
            session = opened
            val clip = clipId?.let { opened?.state?.value?.project?.findClip(it)?.second }
            if (opened == null || clip == null) {
                mutableState.value = CutoutUiState(loaded = true)
                return@launch
            }
            // Opening the board cuts the people out; the masks are made in the background.
            if (clip.cutout == null) opened.edit(SetCutout(clip.id, Cutout()))
            val asset = assetOf(opened.state.value.project, clip)
            if (clip.cutout?.key == null) asset?.let(cutouts::request)
            if (asset != null) {
                launch { original.value = withContext(Dispatchers.IO) { frame(asset, clip) } }
            }
            combine(
                opened.state,
                cutouts.progress,
                mediaCache.voiceVersion,
                original,
            ) { session, progress, _, picture ->
                val project = session.project
                val current = clipId?.let { project.findClip(it)?.second }
                val asset = current?.let { assetOf(project, it) }
                val search = when {
                    asset == null -> PersonSearch.NotFound

                    else -> progress[asset.id]?.let { PersonSearch.Finding(it) }
                        ?: withContext(Dispatchers.IO) { cutouts.masks(asset) }?.let { masks ->
                            if (masks.found) PersonSearch.Found else PersonSearch.NotFound
                        }
                        ?: PersonSearch.Finding(0f)
                }
                CutoutUiState(project, current, loaded = true, search = search, original = picture)
            }.collect { mutableState.value = it }
        }
    }

    /** Commits [cutout]: a slider let go, or what goes behind picked. */
    fun set(cutout: Cutout) {
        val clip = state.value.clip ?: return
        if (cutout != clip.cutout) session?.edit(SetCutout(clip.id, cutout))
    }

    /**
     * Switches between the person cutout and a chroma key (#51); the key starts from the
     * strongest colour round the picture's edges, or a studio green.
     */
    fun setChroma(on: Boolean) {
        val clip = state.value.clip ?: return
        val cutout = state.value.cutout
        if (on == (cutout.key != null)) return
        if (on) {
            val guess = state.value.original?.let { picture ->
                val pixels = IntArray(picture.width * picture.height)
                picture.getPixels(pixels, 0, picture.width, 0, 0, picture.width, picture.height)
                CutoutMath.guessKey(pixels, picture.width, picture.height)
            }
            set(cutout.copy(key = ChromaKey(color = guess ?: ChromaKey.DEFAULT_COLOR)))
        } else {
            set(cutout.copy(key = null))
            state.value.project?.let { project -> assetOf(project, clip) }?.let(cutouts::request)
        }
    }

    /**
     * The key colour at ([x], [y]) of the clip's own picture (0 to 1 across and down), averaged
     * over a few pixels so noise doesn't pick it.
     */
    fun pickAt(x: Float, y: Float): Long? {
        val picture = state.value.original ?: return null
        val cx = (x * picture.width).toInt().coerceIn(0, picture.width - 1)
        val cy = (y * picture.height).toInt().coerceIn(0, picture.height - 1)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (py in cy - PICK_RADIUS..cy + PICK_RADIUS) {
            for (px in cx - PICK_RADIUS..cx + PICK_RADIUS) {
                if (px !in 0 until picture.width || py !in 0 until picture.height) continue
                val p = picture.getPixel(px, py)
                r += p shr 16 and 0xFF
                g += p shr 8 and 0xFF
                b += p and 0xFF
                n++
            }
        }
        return 0xFF000000 or (r / n shl 16) or (g / n shl 8) or (b / n)
    }

    /** The clip's picture at the playhead, from the proxy when there is one. */
    private fun frame(asset: MediaAsset, clip: Clip): Bitmap? {
        if (asset.kind == MediaKind.Photo) {
            return Thumbnails.decodePhoto(context, Uri.parse(asset.uri), FRAME_SIDE)
        }
        val at = playheadUs.coerceIn(clip.startUs, clip.endUs - 1) - clip.startUs
        val mediaUs = clip.mediaTimeAt(at) ?: 0
        val proxy = mediaCache.proxy(asset.id)
        val retriever = MediaMetadataRetriever()
        return try {
            if (proxy.exists()) {
                retriever.setDataSource(proxy.path)
            } else {
                retriever.setDataSource(context, Uri.parse(asset.uri))
            }
            val shown = asset.displaySize
            val scale = FRAME_SIDE.toFloat() / maxOf(shown.width, shown.height, 1)
            retriever.getScaledFrameAtTime(
                mediaUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                maxOf(1, (shown.width * scale).toInt()),
                maxOf(1, (shown.height * scale).toInt()),
            )
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /** Takes the cutout away, leaving the whole picture. */
    fun remove() {
        val clip = state.value.clip ?: return
        if (clip.cutout != null) session?.edit(SetCutout(clip.id, null))
    }

    override fun onCleared() {
        if (session != null && projectId != null) appScope.launch { projects.release(projectId) }
    }

    private fun assetOf(project: Project, clip: Clip): MediaAsset? =
        clip.source?.let { project.asset(it.mediaId) }

    private companion object {
        /** The clip's picture is read no larger than this. */
        const val FRAME_SIDE = 720

        /** The eyedropper averages a square this many pixels out from the tap. */
        const val PICK_RADIUS = 3
    }
}
