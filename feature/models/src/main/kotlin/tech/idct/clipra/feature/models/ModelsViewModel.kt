package tech.idct.clipra.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.idct.clipra.core.data.settings.SettingsRepository
import tech.idct.clipra.core.models.AiModel
import tech.idct.clipra.core.models.ModelManager
import tech.idct.clipra.core.models.ModelState
import javax.inject.Inject

/** What the AI models screen shows: every model with its state, and the two settings. */
data class ModelsUiState(
    val models: List<ModelState> = emptyList(),
    val wifiOnly: Boolean = true,
    val useAccelerator: Boolean = true,
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val manager: ModelManager,
    private val settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<ModelsUiState> = combine(
        manager.models,
        settings.wifiOnlyDownloads,
        settings.useAccelerator,
        ::ModelsUiState,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), ModelsUiState())

    fun download(model: AiModel) {
        viewModelScope.launch { manager.download(model) }
    }

    fun cancel(model: AiModel) {
        viewModelScope.launch { manager.cancel(model) }
    }

    fun remove(model: AiModel) {
        viewModelScope.launch { manager.remove(model) }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { manager.setWifiOnly(enabled) }
    }

    fun setUseAccelerator(enabled: Boolean) {
        viewModelScope.launch { settings.setUseAccelerator(enabled) }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
