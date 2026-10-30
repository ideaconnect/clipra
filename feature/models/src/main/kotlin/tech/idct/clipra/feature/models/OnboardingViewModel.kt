package tech.idct.clipra.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.idct.clipra.core.data.settings.SettingsRepository
import tech.idct.clipra.core.models.AiModel
import tech.idct.clipra.core.models.ModelCatalog
import tech.idct.clipra.core.models.ModelManager
import tech.idct.clipra.core.models.ModelStatus
import javax.inject.Inject

/** One of the models onboarding offers, as in the design: what it is for and its real size. */
data class DownloadChoice(
    val model: AiModel,
    val selected: Boolean,
    /** Already on this phone (a reinstall keeps nothing, but a second onboarding might). */
    val installed: Boolean = false,
)

data class OnboardingUiState(
    val choices: List<DownloadChoice> = OnboardingChoices.defaults,
    val wifiOnly: Boolean = true,
) {
    /** What Start editing downloads, in bytes: the selected models not on the phone yet. */
    val downloadBytes: Long
        get() = choices.filter { it.selected && !it.installed }
            .sumOf { it.model.download?.bytes ?: 0 }
}

/** The models onboarding offers (Onboarding board): Base and DeepFilterNet 3 preselected. */
object OnboardingChoices {
    val models =
        listOf(ModelCatalog.whisperBase, ModelCatalog.deepFilterNet, ModelCatalog.whisperSmall)

    val defaults = models.map { DownloadChoice(it, selected = it != ModelCatalog.whisperSmall) }
}

/**
 * The first run (#73): the promise, three highlights and the models to download. Start editing
 * queues the chosen downloads (they wait for Wi-Fi or a connection, so the first run also
 * completes offline); Skip downloads for now queues nothing. Either way onboarding is done.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val manager: ModelManager,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val selected = MutableStateFlow(
        OnboardingChoices.defaults.filter { it.selected }.map { it.model }.toSet(),
    )

    val state: StateFlow<OnboardingUiState> = combine(
        selected,
        manager.models,
        settings.wifiOnlyDownloads,
    ) { selected, models, wifiOnly ->
        val installed = models.filter { it.status is ModelStatus.Installed }.map { it.model }
        OnboardingUiState(
            choices = OnboardingChoices.models.map {
                DownloadChoice(it, selected = it in selected, installed = it in installed)
            },
            wifiOnly = wifiOnly,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingUiState())

    fun toggle(model: AiModel) {
        selected.value = if (model in selected.value) {
            selected.value - model
        } else {
            selected.value + model
        }
    }

    /** Queues the chosen downloads, then [onDone]. */
    fun start(onDone: () -> Unit) {
        val chosen = state.value.choices.filter { it.selected && !it.installed }.map { it.model }
        viewModelScope.launch {
            chosen.forEach { manager.download(it) }
            // Auto captions starts with the most accurate model the user chose.
            val captions = listOf(ModelCatalog.whisperSmall, ModelCatalog.whisperBase)
                .firstOrNull { it in selected.value }
            captions?.let { settings.setCaptionModel(it.id) }
            settings.setOnboarded()
            onDone()
        }
    }

    fun skip(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.setOnboarded()
            onDone()
        }
    }
}
