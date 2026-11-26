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
 * Whether the first start's notice is shown ([shown]; owner's request, 2026-11-26): until the user taps OK, once
 * for good. OK hides it at once, for this run even when the dismissal cannot be saved; it is then shown again at the
 * next start, which is better than never having it. False until the stored answer is read, so it never flashes up.
 */
class FirstRunViewModel(
    observe: ObserveFirstRunNotice,
    private val dismiss: DismissFirstRunNotice,
) : ViewModel() {

    private val dismissedNow = MutableStateFlow(false)

    /** Whether the notice is shown now. */
    val shown: StateFlow<Boolean> = combine(observe(), dismissedNow) { toShow, dismissed -> toShow && !dismissed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** The user tapped OK. */
    fun ok() {
        dismissedNow.value = true
        viewModelScope.launch { dismiss() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
