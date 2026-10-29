package tech.idct.clipra.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.idct.clipra.core.data.project.ProjectRepository
import tech.idct.clipra.core.engine.encoder.VideoEncoders
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.core.model.Resolution
import tech.idct.clipra.core.model.TextColors
import tech.idct.clipra.core.model.TextFonts
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.edit.randomId
import javax.inject.Inject

@HiltViewModel
class NewProjectViewModel @Inject constructor(
    private val projects: ProjectRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val preset = savedState.get<String>("layout")?.let { name ->
        Layout.entries.firstOrNull { it.name == name }
    }

    private val textCard = savedState.get<Boolean>("textCard") ?: false

    private val mutableFormat = MutableStateFlow(Format(layout = preset ?: Layout.Single))

    /** The format being chosen; New project starts at 9:16, FHD, 30 fps, Single, Black. */
    val format: StateFlow<Format> = mutableFormat.asStateFlow()

    /** Whether any encoder takes 2160p frames; checked once (#11, "4K note"). */
    val fourKSupported: Boolean by lazy {
        val size = Format(resolution = Resolution.UHD).outputSize
        VideoEncoders.supportsSize(size.width, size.height)
    }

    fun change(format: Format) {
        mutableFormat.value = format
    }

    /**
     * Creates the project and hands its id to [onCreated] to open the editor; from "Text card",
     * it starts with a black card saying [title], whose clip id comes along.
     */
    fun create(name: String, title: String, onCreated: (String, String?) -> Unit) {
        viewModelScope.launch {
            val project = projects.create(name, mutableFormat.value)
            if (!textCard) {
                onCreated(project.id, null)
                return@launch
            }
            val card = Clip(
                id = randomId(),
                type = ClipType.TextCard,
                startUs = 0,
                durationUs = CARD_US,
                text = TextSpec(
                    text = title,
                    font = TextFonts.BRICOLAGE,
                    weight = 800,
                    size = 0.08f,
                    color = TextColors.WHITE,
                    background = TextColors.BLACK,
                ),
            )
            val main = project.layers.first()
            projects.save(
                project.copy(
                    layers =
                    listOf(main.copy(clips = listOf(card))) + project.layers.drop(1),
                ),
            )
            onCreated(project.id, card.id)
        }
    }

    private companion object {
        const val CARD_US = 3_000_000L
    }
}
