package tech.idct.clipra.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.idct.clipra.core.data.project.ProjectRepository
import tech.idct.clipra.core.data.project.ProjectSummary
import tech.idct.clipra.core.data.project.WallClock
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val projects: ProjectRepository,
    private val clock: WallClock,
) : ViewModel() {
    /** Recent projects, last edited first; null until the index has loaded. */
    val recent: StateFlow<List<ProjectSummary>?> = projects.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The time "Edited … ago" is measured from, refreshed every minute. */
    val now: StateFlow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(60_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), clock.nowMillis())

    fun rename(id: String, name: String) {
        viewModelScope.launch { projects.rename(id, name.trim()) }
    }

    fun duplicate(id: String, name: String) {
        viewModelScope.launch { projects.duplicate(id, name) }
    }

    fun delete(id: String) {
        viewModelScope.launch { projects.delete(id) }
    }
}
