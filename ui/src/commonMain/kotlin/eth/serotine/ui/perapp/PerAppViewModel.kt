package eth.serotine.ui.perapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppHostResult
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.MemoryStep
import eth.serotine.domain.usecase.ObservePerAppAddresses
import eth.serotine.domain.usecase.PerAppMaking
import eth.serotine.domain.usecase.PerAppEntry
import eth.serotine.domain.usecase.PerAppOverview
import eth.serotine.domain.usecase.PerAppRestoring
import eth.serotine.domain.usecase.PhraseSource
import eth.serotine.domain.usecase.RestorePerAppAddress
import eth.serotine.domain.usecase.restoreNetworks
import eth.serotine.ui.components.Eip55
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Screen D9's ViewModel (issue #252; docs/walletconnect.md, *Every per-app address is listed*): the per-app addresses
 * held here ([ObservePerAppAddresses]), of every phrase, or of the one named by [phrase] when the screen is opened from
 * that phrase's account (its Per-app tab); and "Restore a per-app address" ([RestorePerAppAddress]), which takes a
 * host or a site, a phrase and the wallet password. The password is held while the form is open and dropped when the
 * screen is left or the restore starts.
 */
class PerAppViewModel(
    private val phrase: Address?,
    observePerAppAddresses: ObservePerAppAddresses,
    private val restorePerAppAddress: RestorePerAppAddress,
) : ViewModel() {

    private data class Local(
        val form: RestoreForm? = null,
        val notice: String? = null,
        val explaining: String? = null,
    )

    private val local = MutableStateFlow(Local())

    /** The addresses and phrases as last read; null until first read. Restore reads them when tapped. */
    private val overview: StateFlow<PerAppOverview?> =
        observePerAppAddresses().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uiState: StateFlow<PerAppUiState> = combine(overview.filterNotNull(), local) { overview, local ->
        state(overview, local)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PerAppUiState())

    private fun state(overview: PerAppOverview, local: Local): PerAppUiState {
        val sources = sourcesOf(overview)
        val entries = overview.entries.filter { phrase == null || it.phrase == phrase }
        val title = phrase?.let { p -> sources.firstOrNull { it.phrase == p }?.from?.label }
            ?.let { "Per-app addresses of $it's phrase" } ?: TITLE
        return PerAppUiState(
            loading = false,
            title = title,
            rows = perAppRows(entries, overview.sources),
            empty = if (entries.isEmpty()) EMPTY else null,
            // The phrases are offered only where there is a choice: opened from one, or with one, there is none.
            restore = local.form?.copy(phrases = if (sources.size > 1) labels(sources) else emptyList()),
            restoreBlocked = blocked(overview, sources),
            notice = local.notice,
            explaining = local.explaining,
        )
    }

    /** The phrases a restore may come from: every phrase held here, or the one named by [phrase]. */
    private fun sourcesOf(overview: PerAppOverview): List<PhraseSource> =
        overview.sources.filter { phrase == null || it.phrase == phrase }

    private fun blocked(overview: PerAppOverview, sources: List<PhraseSource>): String? = when {
        sources.isEmpty() -> NO_PHRASE
        restoreNetworks(overview.enabled).isEmpty() -> NO_NETWORK
        else -> null
    }

    /** Restore a per-app address was tapped: the form opens, or, greyed out, it says why. */
    fun openRestore() {
        val overview = overview.value ?: return
        val reason = blocked(overview, sourcesOf(overview))
        if (reason != null) {
            local.update { it.copy(explaining = reason) }
        } else {
            local.update { it.copy(form = it.form ?: RestoreForm(), notice = null) }
        }
    }

    /** The form was closed: what was typed goes. */
    fun closeRestore() {
        if (local.value.form?.busy != true) local.update { it.copy(form = null) }
    }

    /** The explanation of a greyed-out Restore was dismissed. */
    fun dismissExplanation() = local.update { it.copy(explaining = null) }

    /** The host field changed. */
    fun onHostChange(text: String) = editForm { it.copy(host = text) }

    /** Another phrase was chosen, by its index in the form's list. */
    fun onPhraseChange(index: Int) = editForm { it.copy(phrase = index) }

    /** The password field changed. */
    fun onPasswordChange(text: String) = editForm { it.copy(password = WalletPassword(text)) }

    /** The screen is no longer shown: the password typed goes. */
    fun onLeave() = editForm { it.copy(password = WalletPassword.EMPTY) }

    private fun editForm(change: (RestoreForm) -> RestoreForm) = local.update { now ->
        val form = now.form
        if (form == null || form.busy) now else now.copy(form = change(form), notice = null)
    }

    /** Restore was tapped in the form: the address of the site typed is derived again under the password. */
    fun restore() {
        val overview = overview.value ?: return
        val form = local.value.form ?: return
        if (!form.canRestore) return
        val sources = sourcesOf(overview)
        val source = sources.getOrNull(form.phrase) ?: sources.firstOrNull() ?: return
        val password = form.password
        // The password leaves the state as the work starts: it is held by this call alone.
        local.update { it.copy(form = form.copy(busy = true, password = WalletPassword.EMPTY), notice = null) }
        viewModelScope.launch {
            val restoring = restorePerAppAddress(source.from.id, form.host, password)
            val done = restoring is PerAppRestoring.Done && restoring.making is PerAppMaking.Made
            local.update {
                it.copy(
                    form = if (done) null else form.copy(busy = false, password = WalletPassword.EMPTY),
                    notice = restoringLine(restoring),
                )
            }
        }
    }

    companion object {
        /** The screen's title while it lists every phrase's addresses. */
        const val TITLE = "Per-app addresses"

        /** What the list says while it has none. */
        const val EMPTY = "No per-app address yet. A dApp that connects is offered one of its own, and " +
            "Restore makes one again for a site you name."

        /** Why Restore is greyed out without a phrase held here. */
        const val NO_PHRASE = "A per-app address is made from a recovery phrase held on this device. Create an " +
            "account or import a recovery phrase first."

        /** Why Restore is greyed out with no network on. */
        const val NO_NETWORK = "A restored address is added on the networks that are on, and none is. Turn one " +
            "on in Settings → Networks."

        /** The phrases as the form names them: each by an account of it. */
        internal fun labels(sources: List<PhraseSource>): List<String> = sources.map { "Phrase of ${it.from.label}" }

        /** The phrase [phrase] as a row names it: by an account of it in the wallet, else by its address. */
        internal fun phraseName(sources: List<PhraseSource>, phrase: Address): String =
            sources.firstOrNull { it.phrase == phrase }?.from?.label?.let { "Phrase of $it" }
                ?: "Phrase of ${Eip55.format(phrase).take(6)}…${Eip55.format(phrase).takeLast(4)}"

        /** How a restore went, as the screen says it. */
        internal fun restoringLine(restoring: PerAppRestoring): String = when (restoring) {
            is PerAppRestoring.NoHost -> when (restoring.reason) {
                AppHostResult.NotAscii -> "That name has characters outside ASCII, which Punicus does not map. " +
                    "Type it in its xn-- form."
                AppHostResult.NotAName -> "That is not a DNS name, so there is no site to make an address for."
                AppHostResult.NotAWebAddress, is AppHostResult.Host ->
                    "Type a host or a site, such as app.uniswap.org or uniswap.org."
            }
            is PerAppRestoring.Done -> when (val made = restoring.making) {
                is PerAppMaking.Made -> "The address of ${restoring.site.name} is back in your accounts." +
                    if (made.steppedDown) " $STEPPED_DOWN" else ""
                PerAppMaking.NoNetwork -> NO_NETWORK
                PerAppMaking.NoKey -> "The phrase's account has no key on this device any more. Nothing changed."
                PerAppMaking.WrongPassword -> "That is not the wallet password. Nothing changed."
                PerAppMaking.Unavailable -> "The key store cannot open that recovery phrase on this device. " +
                    "Nothing changed."
                PerAppMaking.KeyNotSaved -> "The address could not be stored. Nothing changed."
                is PerAppMaking.NotEnoughMemory -> when (made.step) {
                    MemoryStep.CHECK_PASSWORD -> "There is not enough free memory to check the password right " +
                        "now. Close other apps and try again."
                    MemoryStep.PROTECT -> "There is not enough free memory to protect the address's key right " +
                        "now. Close other apps and try again."
                }
                is PerAppMaking.Collision -> "${restoring.site.name}'s address would be the one made for " +
                    "${made.other.name}, so it is already used for another app. Nothing changed."
                PerAppMaking.ListUnreadable -> "Your account list cannot be read. Nothing changed."
                PerAppMaking.ListNotSaved -> "The address is stored, but your account list could not be saved. " +
                    "Restore it again to add it."
            }
        }

        /** What a restore whose key was sealed at a lighter cost adds. */
        const val STEPPED_DOWN = "Its key was protected at a lighter cost than this device's own, as memory ran " +
            "short."
    }
}

/** [entries] as their rows show them (screen D9 and an account's Per-app tab), each phrase named among [sources]. */
internal fun perAppRows(entries: List<PerAppEntry>, sources: List<PhraseSource>): List<PerAppRow> =
    entries.map { entry ->
        val first = entry.accounts.first()
        PerAppRow(
            site = entry.site.name,
            address = Eip55.format(entry.address),
            networks = entry.accounts.joinToString(", ") { it.network.displayName },
            phrase = PerAppViewModel.phraseName(sources, entry.phrase),
            path = entry.path.toString(),
            chainId = first.network.chainId,
            hex = entry.address.hex,
        )
    }
