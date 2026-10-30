package tech.idct.clipra.monetization

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.idct.clipra.monetization.billing.LaunchResult
import tech.idct.clipra.monetization.billing.ProStore
import tech.idct.clipra.monetization.billing.PurchaseEvent
import tech.idct.clipra.monetization.billing.RestoreResult
import javax.inject.Inject

@HiltViewModel
class ProViewModel @Inject constructor(private val store: ProStore) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<ProMessage?>(null)

    val state: StateFlow<ProUiState> =
        combine(store.isPro, store.price, store.pending, busy, message) {
                owned,
                price,
                pending,
                busy,
                message,
            ->
            ProUiState(
                owned = owned,
                price = price,
                pending = pending,
                busy = busy,
                message = message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProUiState())

    init {
        viewModelScope.launch {
            store.events.collect { event ->
                message.value = if (event == PurchaseEvent.Failed) ProMessage.Failed else null
            }
        }
        // A payment that completed while Clipra was closed shows up here too.
        viewModelScope.launch {
            store.loadPrice()
            store.refresh()
        }
    }

    fun buy(activity: Activity) {
        if (busy.value) return
        message.value = null
        viewModelScope.launch {
            busy.value = true
            message.value = when (store.buy(activity)) {
                LaunchResult.Unavailable -> ProMessage.Unavailable
                LaunchResult.Failed -> ProMessage.Failed
                LaunchResult.AlreadyOwned -> ProMessage.Restored
                LaunchResult.Started, LaunchResult.Cancelled -> null
            }
            busy.value = false
        }
    }

    fun restore() {
        if (busy.value) return
        message.value = null
        viewModelScope.launch {
            busy.value = true
            message.value = when (store.refresh()) {
                RestoreResult.Owned -> ProMessage.Restored
                RestoreResult.NotOwned -> ProMessage.NothingToRestore
                RestoreResult.Unavailable -> ProMessage.Unavailable
            }
            store.loadPrice()
            busy.value = false
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
