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
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.core.model.Resolution
import javax.inject.Inject

@HiltViewModel
class NewProjectViewModel @Inject constructor(
    private val projects: ProjectRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val preset = savedState.get<String>("layout")?.let { name ->
        Layout.entries.firstOrNull { it.name == name }
    }

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

    /** Creates the project and hands its id to [onCreated] to open the editor. */
    fun create(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val project = projects.create(name, mutableFormat.value)
            onCreated(project.id)
        }
    }
}
