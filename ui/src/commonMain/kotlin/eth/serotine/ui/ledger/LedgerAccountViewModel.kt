package eth.serotine.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.LedgerProblem
import eth.serotine.domain.repository.LedgerResult
import eth.serotine.domain.usecase.ConfirmedLedgerAccount
import eth.serotine.domain.usecase.EnrollLedgerAccount
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.ui.components.Eip55
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val LEDGER_PATH_ERROR = "Enter an Ethereum derivation path, for example m/44'/60'/0'/0/0."

/** Confirms the address on a Ledger, then stores the account only on an explicit Add. */
class LedgerAccountViewModel(
    private val enroll: EnrollLedgerAccount,
    observeEnabledNetworks: ObserveEnabledNetworks,
    private val setNetworkEnabled: SetNetworkEnabled,
) : ViewModel() {
    private val state = MutableStateFlow(LedgerAccountUiState())
    val uiState = state.asStateFlow()

    private var confirmation: ConfirmedLedgerAccount? = null
    private var operation: Job? = null

    init {
        viewModelScope.launch {
            observeEnabledNetworks().collect { enabled -> state.update { it.copy(enabledNetworks = enabled) } }
        }
    }

    internal fun browseAccounts() {
        val current = state.value
        if (current.busy || current.customPath || current.accountPage != null) return
        confirmation = null
        readPage((current.selectedAccount?.index ?: 0) / LEDGER_ACCOUNTS_PER_PAGE * LEDGER_ACCOUNTS_PER_PAGE)
    }

    internal fun browsePage(start: Int) {
        val page = state.value.accountPage ?: return
        if (page.loading || (start != page.start && start != page.previous && start != page.next)) return
        readPage(start)
    }

    private fun readPage(start: Int) {
        val last = (start.toLong() + LEDGER_ACCOUNTS_PER_PAGE - 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val paths = (start..last).map { LedgerPath.ledgerLiveAccount(it) }
        val page = LedgerAccountPage(start)
        state.update { it.copy(accountPage = page, step = LedgerEnrollmentStep.Editing, message = null) }
        operation = viewModelScope.launch {
            val result = enroll.browse(paths)
            ensureActive()
            state.update {
                when (result) {
                    is LedgerResult.Ok -> it.copy(
                        accountPage = page.copy(accounts = result.value.mapIndexed { offset, address ->
                            LedgerAddressChoice(start + offset, address)
                        }),
                    )
                    is LedgerResult.Failed -> it.copy(
                        accountPage = page.copy(accounts = emptyList()), message = ledgerProblemText(result.problem),
                    )
                }
            }
        }
    }

    internal fun chooseAccount(index: Int) {
        val chosen = state.value.accountPage?.accounts?.firstOrNull { it.index == index } ?: return
        state.update {
            it.copy(
                selectedAccount = chosen,
                accountPage = null,
                path = LedgerPath.ledgerLiveAccount(chosen.index).value,
                message = null,
            )
        }
    }

    internal fun useCustomPath(custom: Boolean) {
        val current = state.value
        if (current.busy || current.accountPage != null || custom == current.customPath) return
        confirmation = null
        state.update {
            it.copy(
                customPath = custom,
                selectedAccount = null,
                path = if (custom) it.path else LedgerPath.DEFAULT.value,
                pathError = null,
                message = null,
                step = LedgerEnrollmentStep.Editing,
            )
        }
    }

    internal fun editPath(path: String) {
        val current = state.value
        if (current.busy || !current.customPath || path == current.path) return
        confirmation = null
        state.update {
            it.copy(
                path = path,
                pathError = LEDGER_PATH_ERROR.takeIf { LedgerPath.parse(path) == null },
                message = null,
                step = LedgerEnrollmentStep.Editing,
            )
        }
    }

    internal fun chooseNetwork(chainId: Long) {
        val network = ledgerNetworks.firstOrNull { it.chainId == chainId } ?: return
        val current = state.value
        if (current.busy || current.accountPage != null || network == current.network ||
            current.enabledNetworks?.contains(network) != true
        ) return
        confirmation = null
        state.update { it.copy(network = network, message = null, step = LedgerEnrollmentStep.Editing) }
    }

    internal fun turnOnNetwork(chainId: Long) {
        val network = ledgerNetworks.firstOrNull { it.chainId == chainId } ?: return
        val current = state.value
        if (current.busy || current.accountPage != null || current.enabledNetworks?.contains(network) != false) return
        state.update { it.copy(step = LedgerEnrollmentStep.Enabling, message = null) }
        operation = viewModelScope.launch {
            val enabled = setNetworkEnabled(network, on = true)
            ensureActive()
            if (enabled) {
                if (network != current.network) confirmation = null
                state.update {
                    it.copy(
                        network = network,
                        step = if (network == current.network) current.step else LedgerEnrollmentStep.Editing,
                    )
                }
            } else {
                state.update { it.copy(step = current.step, message = "Could not turn on ${network.displayName}.") }
            }
        }
    }

    internal fun confirm() {
        val current = state.value
        if (current.step != LedgerEnrollmentStep.Editing || current.accountPage != null) return
        val path = LedgerPath.parse(current.path) ?: return
        state.update { it.copy(path = path.value, message = null, step = LedgerEnrollmentStep.Confirming) }
        operation = viewModelScope.launch {
            val result = enroll.confirm(path, current.selectedAccount?.address)
            ensureActive()
            when (result) {
                is LedgerResult.Ok -> {
                    confirmation = result.value
                    val shown = Eip55.format(result.value.address)
                    state.update { it.copy(step = LedgerEnrollmentStep.Confirmed(shown)) }
                }
                is LedgerResult.Failed -> state.update {
                    it.copy(step = LedgerEnrollmentStep.Editing, message = ledgerProblemText(result.problem))
                }
            }
        }
    }

    internal fun add() {
        val current = state.value
        val step = current.step as? LedgerEnrollmentStep.Confirmed ?: return
        val confirmed = confirmation ?: return
        if (current.addReason != null) return
        confirmation = null
        state.update { it.copy(message = null, step = LedgerEnrollmentStep.Saving(step.address)) }
        operation = viewModelScope.launch {
            val outcome = enroll.add(current.network, confirmed)
            ensureActive()
            when (outcome) {
                AccountAdd.Added -> state.update { it.copy(step = LedgerEnrollmentStep.Added) }
                AccountAdd.AlreadyPresent -> state.update {
                    it.copy(step = LedgerEnrollmentStep.Editing, message = "This address is already on this network.")
                }
                AccountAdd.NotSaved -> state.update {
                    it.copy(step = LedgerEnrollmentStep.Editing, message = "Could not save the account.")
                }
            }
        }
    }

    internal fun cancel() {
        val current = state.value
        if (current.accountPage == null && current.step != LedgerEnrollmentStep.Confirming) return
        operation?.cancel()
        operation = null
        state.update { it.copy(accountPage = null, step = LedgerEnrollmentStep.Editing, message = null) }
    }

    internal fun close() {
        operation?.cancel()
        operation = null
        confirmation = null
        state.update { LedgerAccountUiState(enabledNetworks = it.enabledNetworks) }
    }
}

internal fun ledgerProblemText(problem: LedgerProblem): String = when (problem) {
    LedgerProblem.NOT_CONNECTED -> "No Ledger is connected. Connect it by USB, unlock it, and open the Ethereum app."
    LedgerProblem.MULTIPLE_DEVICES -> "More than one Ledger is connected. Disconnect all but the one you want to use."
    LedgerProblem.ACCESS_DENIED -> "Punicus cannot access the Ledger over USB. Check your system’s USB permissions."
    LedgerProblem.UNAVAILABLE -> "Ledger USB support is unavailable on this computer. Check that HIDAPI is installed."
    LedgerProblem.REJECTED -> "You declined the request on the Ledger."
    LedgerProblem.OPEN_ETHEREUM -> "Unlock your Ledger and open the Ethereum app, then try again."
    LedgerProblem.TIMED_OUT -> "The Ledger did not respond in time. Reconnect it and try again."
    LedgerProblem.INVALID_RESPONSE -> "The Ledger returned an invalid response. Reconnect it and try again."
    LedgerProblem.WRONG_ACCOUNT -> "The Ledger did not return the expected account. Connect the Ledger for this address."
    LedgerProblem.UNSUPPORTED_TRANSACTION ->
        "The Ledger Ethereum app could not sign this request. Check that the app is up to date."
    LedgerProblem.BLIND_SIGNING_DISABLED ->
        "The Ledger requires blind signing for this request. If you accept that the device cannot show " +
            "the full details, enable Blind signing in the Ethereum app on the Ledger, then review again."
}
