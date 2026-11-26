package eth.serotine.ui.firstrun

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.usecase.DismissFirstRunNotice
import eth.serotine.domain.usecase.ObserveFirstRunNotice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The first start's notice (owner's request, 2026-11-26): [FirstRunUiState.Shown] when this run is a first start,
 * until the user taps OK. OK hides it at once, for this run even when the dismissal cannot be saved; it is then shown
 * again at the next start, which is better than never having it. Hidden until the start is decided, so it never
 * flashes up.
 */
class FirstRunViewModel(
    observe: ObserveFirstRunNotice,
    private val dismiss: DismissFirstRunNotice,
) : ViewModel() {

    private val dismissedNow = MutableStateFlow(false)

    /** The notice now. */
    val uiState: StateFlow<FirstRunUiState> = combine(observe(), dismissedNow) { firstStart, dismissed ->
        if (firstStart && !dismissed) FirstRunUiState.Shown() else FirstRunUiState.Hidden
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FirstRunUiState.Hidden)

    /** The user tapped OK. */
    fun ok() {
        dismissedNow.value = true
        viewModelScope.launch { dismiss() }
    }
}
