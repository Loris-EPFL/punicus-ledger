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
 * host or a site, a phrase and the wallet password, its form open from the start when [restoring] (the Per-app tab's
 * Restore). The phrase chosen is held by its account's address, so a list that changes meanwhile never swaps it for
 * another. The password is held while the form is open and dropped when the screen is left or the restore starts.
 */
class PerAppViewModel(
    private val phrase: Address?,
    observePerAppAddresses: ObservePerAppAddresses,
    private val restorePerAppAddress: RestorePerAppAddress,
    restoring: Boolean = false,
) : ViewModel() {

    private data class Local(
        val form: RestoreForm? = null,
        /** The account whose phrase the form restores from, once one was chosen; else the first. */
        val from: Address? = null,
        val notice: String? = null,
        val explaining: String? = null,
    )

    private val local = MutableStateFlow(Local(form = if (restoring) RestoreForm() else null))

    /** The addresses and phrases as last read; null until first read. Restore reads them when tapped. */
    private val overview: StateFlow<PerAppOverview?> =
        observePerAppAddresses().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uiState: StateFlow<PerAppUiState> = combine(overview.filterNotNull(), local) { overview, local ->
        state(overview, local)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PerAppUiState())

    private fun state(overview: PerAppOverview, local: Local): PerAppUiState {
        val sources = sourcesOf(overview)
        val entries = overview.entries.filter { phrase == null || it.phrase == phrase }
        val title = when {
            phrase == null -> TITLE
            else -> sources.firstOrNull()?.let { "Per-app addresses of ${overview.nameOf(it)}'s phrase" } ?: OF_A_PHRASE
        }
        val blocked = blocked(overview, sources)
        return PerAppUiState(
            loading = false,
            title = title,
            rows = perAppRows(entries, overview),
            empty = when {
                overview.unreadable -> UNREADABLE
                entries.isEmpty() -> EMPTY
                else -> null
            },
            // The phrases are offered only where there is a choice: opened from one, or with one, there is none.
            restore = local.form.takeIf { blocked == null }?.copy(
                phrases = if (sources.size > 1) sources.map { "Phrase of ${overview.nameOf(it)}" } else emptyList(),
                phrase = sources.indexOfFirst { it.from.address == local.from }.coerceAtLeast(0),
            ),
            restoreBlocked = blocked,
            notice = local.notice,
            // The form asked for (from a Per-app tab) where Restore cannot be done: why, at once.
            explaining = local.explaining ?: blocked?.takeIf { local.form != null },
        )
    }

    /** The phrases a restore may come from: every phrase held here, or the one named by [phrase]. */
    private fun sourcesOf(overview: PerAppOverview): List<PhraseSource> =
        overview.sources.filter { phrase == null || it.phrase == phrase }

    private fun blocked(overview: PerAppOverview, sources: List<PhraseSource>): String? = when {
        overview.unreadable -> UNREADABLE
        sources.isEmpty() && phrase != null -> PHRASE_GONE
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

    /** The explanation of a greyed-out Restore was dismissed: a form asked for while it could not be done goes too. */
    fun dismissExplanation() = local.update { now ->
        val asked = now.form?.takeIf { now.explaining == null && !it.busy }
        now.copy(explaining = null, form = if (asked != null) null else now.form)
    }

    /** The host field changed. */
    fun onHostChange(text: String) = editForm { it.copy(host = text) }

    /** Another phrase was chosen, by its index in the form's list: held by its account's address. */
    fun onPhraseChange(index: Int) {
        val chosen = overview.value?.let(::sourcesOf)?.getOrNull(index) ?: return
        if (local.value.form?.busy == false) local.update { it.copy(from = chosen.from.address, notice = null) }
    }

    /** The password field changed. */
    fun onPasswordChange(text: String) = editForm { it.copy(password = WalletPassword(text)) }

    /** The screen is no longer shown: the password typed goes, and nothing else; what the screen said stays. */
    fun onLeave() = local.update { now ->
        val form = now.form
        if (form == null || form.busy) now else now.copy(form = form.copy(password = WalletPassword.EMPTY))
    }

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
        val chosen = local.value.from
        // The phrase chosen, or the first while none was; one gone meanwhile is said, never swapped for another.
        val source = if (chosen == null) sources.firstOrNull() else sources.firstOrNull { it.from.address == chosen }
        if (source == null) {
            local.update {
                it.copy(form = form.copy(password = WalletPassword.EMPTY), from = null, notice = CHOSEN_GONE)
            }
            return
        }
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

        /** The title while the screen is for a phrase none of whose accounts is here any more. */
        const val OF_A_PHRASE = "Per-app addresses of a phrase"

        /** Why Restore is greyed out, or refused, when the phrase it was opened for has no account here any more. */
        const val PHRASE_GONE = "That recovery phrase has no account on this device any more. Its addresses stay " +
            "listed under Per-app addresses, in Connected dApps."

        /** What a restore says when the phrase chosen left the device meanwhile: nothing is made from another. */
        const val CHOSEN_GONE = "The recovery phrase you chose has no account on this device any more. Nothing " +
            "changed: choose another."

        /** Why Restore is greyed out with no network on. */
        const val NO_NETWORK = "A restored address is added on the networks that are on, and none is. Turn one " +
            "on in Settings → Networks."

        /** Why nothing is listed and Restore is greyed out while the account list cannot be read. */
        const val UNREADABLE = "Your account list cannot be read right now, so the per-app addresses cannot be " +
            "listed or restored. Nothing was changed."

        /**
         * The phrase [phrase] as a row names it: as the account it is known by is named, else an account of it, else
         * by its address ([PerAppOverview.nameOf]).
         */
        internal fun phraseName(overview: PerAppOverview, phrase: Address): String =
            overview.sources.firstOrNull { it.phrase == phrase }?.let { "Phrase of ${overview.nameOf(it)}" }
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

/** [entries] as their rows show them (screen D9 and an account's Per-app tab), each phrase named by [overview]. */
internal fun perAppRows(entries: List<PerAppEntry>, overview: PerAppOverview): List<PerAppRow> =
    entries.map { entry ->
        val first = entry.accounts.first()
        PerAppRow(
            site = entry.site.name,
            address = Eip55.format(entry.address),
            networks = entry.accounts.joinToString(", ") { it.network.displayName },
            phrase = PerAppViewModel.phraseName(overview, entry.phrase),
            path = entry.path.toString(),
            chainId = first.network.chainId,
            hex = entry.address.hex,
        )
    }
