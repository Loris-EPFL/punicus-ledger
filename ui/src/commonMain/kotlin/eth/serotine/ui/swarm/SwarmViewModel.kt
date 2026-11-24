package eth.serotine.ui.swarm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.BatchFinding
import eth.serotine.domain.repository.ChequebookFinding
import eth.serotine.domain.repository.SwarmCandidate
import eth.serotine.domain.repository.SwarmReading
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.AddSwarmCandidate
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.ObserveSwarmNode
import eth.serotine.domain.usecase.RemoveSwarmCandidate
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.SwarmAddOutcome
import eth.serotine.domain.usecase.SwarmItem
import eth.serotine.domain.usecase.SwarmNodeReading
import eth.serotine.ui.components.AddressInput
import eth.serotine.ui.components.Eip55
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Swarm node screen's ViewModel (docs/wallet-ux.md, *Swarm nodes*): the node's chequebooks and
 * batches as [observeSwarmNode] proves them, each held as last verified for an hour at most and
 * said to be; adding one by hand ([addSwarmCandidate], kept only once proven) and removing one
 * ([removeSwarmCandidate], remembered as removed). Signs nothing.
 */
class SwarmViewModel(
    private val node: AccountId,
    observeAccount: ObserveAccount,
    private val observeSwarmNode: ObserveSwarmNode,
    private val addSwarmCandidate: AddSwarmCandidate,
    private val removeSwarmCandidate: RemoveSwarmCandidate,
    observeEnabledNetworks: ObserveEnabledNetworks,
    private val setNetworkEnabled: SetNetworkEnabled,
    observeAccounts: ObserveAccounts,
) : ViewModel() {

    private val form = MutableStateFlow<AddForm?>(null)

    /** What the last action said, when it could not be done; cleared by the next one. */
    private val message = MutableStateFlow<String?>(null)

    /** The check the open form started, cancelled with the form: a check taken back keeps nothing. */
    private var check: Job? = null

    private val networkOn = observeEnabledNetworks().map { node.network in it }.distinctUntilChanged()

    private val lookup = observeAccount(node)

    /** Whether any account may pay for a batch here: an EOA on Gnosis Chain whose key is on this device. */
    private val canPay = observeAccounts().map { list ->
        (list as? AccountList.Loaded)?.accounts?.any(::signsHere) == true
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val reading = lookup
        .map { (it as? AccountLookup.Found)?.account }
        // Only what decides the reading: a rename never restarts it, nor drops a reading held.
        .distinctUntilChangedBy { it?.id to it?.kind }
        .flatMapLatest { account -> if (account == null) flowOf(null) else observeSwarmNode(account) }

    /** The screen's state. */
    val uiState: StateFlow<SwarmUiState> = combine(
        combine(lookup, reading, ::Pair),
        form,
        networkOn,
        message,
        canPay,
    ) { (lookup, reading), form, on, said, pays ->
        when (lookup) {
            AccountLookup.ListUnavailable -> SwarmUiState.Unavailable(SwarmTexts.LIST_UNAVAILABLE)
            AccountLookup.NotInList -> SwarmUiState.Gone
            is AccountLookup.Found -> when (reading) {
                null -> SwarmUiState.Loading
                SwarmNodeReading.NotASwarmAccount -> SwarmUiState.Unavailable(SwarmTexts.NOT_SWARM)
                SwarmNodeReading.Unreadable -> SwarmUiState.Unavailable(SwarmTexts.UNREADABLE)
                is SwarmNodeReading.Read -> {
                    val signs = Signs(on, pays, signsHere(lookup.account))
                    ready(lookup.account.label, reading, form, signs).copy(
                        // Nothing can be proven on a network that is off: Add is greyed out, with Turn on.
                        addReason = SwarmTexts.NETWORK_OFF.takeUnless { on },
                        buy = signs.pay(),
                        message = said,
                    )
                }
            }
        }
    }.stateIn(
        viewModelScope,
        // A screen back after the timeout must not show an old reading as current: no replay then.
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        SwarmUiState.Loading,
    )

    /** Opens the form that adds a [kind] by hand. */
    fun startAdd(kind: AddKind) {
        check?.cancel()
        message.value = null
        form.value = AddForm(kind, attempt = ++attempts)
    }

    /** Turns Gnosis Chain on, from Add's greyed-out notice; says so when it could not be saved. */
    fun turnOnNetwork() {
        viewModelScope.launch {
            message.value = SwarmTexts.NETWORK_NOT_TURNED_ON.takeUnless { setNetworkEnabled(node.network, on = true) }
        }
    }

    /** Counts each form opened and each check made, so that a check's answer reaches only its own form. */
    private var attempts = 0L

    /** The text typed in the form. */
    fun onAddText(text: String) {
        form.update { it?.copy(text = text, error = null) }
    }

    /** Closes the form, and stops the check it started: what was taken back is not kept. */
    fun cancelAdd() {
        check?.cancel()
        check = null
        form.value = null
    }

    /** Reads what the form names on chain and keeps it when it is the node's; says why when not. */
    fun confirmAdd() {
        val current = form.value ?: return
        if (current.checking) return
        val candidate = when (val parsed = parse(current)) {
            is Parsed.Candidate -> parsed.candidate
            is Parsed.Error -> {
                form.value = current.copy(error = parsed.message)
                return
            }
        }
        val checking = current.copy(checking = true, error = null, attempt = ++attempts)
        form.value = checking
        check?.cancel()
        check = viewModelScope.launch {
            val outcome = addFor(candidate)
            val error = if (outcome == null) SwarmTexts.LIST_UNAVAILABLE else SwarmTexts.added(outcome)
            // Kept: the form closes. Not kept: it stays, saying why. Only the form that asked: one
            // cancelled meanwhile, or opened anew, is left as it is.
            form.update { open ->
                when {
                    open != checking -> open
                    error == null -> null
                    else -> open.copy(checking = false, error = error)
                }
            }
        }
    }

    /** Takes the chequebook at [address] (EIP-55) out of the node's: remembered as removed. */
    fun removeChequebook(address: String) {
        val parsed = Address.parse(address) ?: return
        remove(SwarmCandidate.ChequebookAt(parsed))
    }

    /** Takes the batch [id] out of the node's: remembered as removed. */
    fun removeBatch(id: String) {
        val parsed = BatchId.parse(id) ?: return
        remove(SwarmCandidate.Batch(parsed))
    }

    /** Removes [candidate], saying so when that could not be saved. */
    private fun remove(candidate: SwarmCandidate) {
        message.value = null
        viewModelScope.launch {
            if (!removeSwarmCandidate(node, candidate)) message.value = SwarmTexts.NOT_REMOVED
        }
    }

    /** What adding [candidate] made of it; null when the account list cannot be read to add it with. */
    private suspend fun addFor(candidate: SwarmCandidate): SwarmAddOutcome? {
        val account = when (val found = lookupNow()) {
            is AccountLookup.Found -> found.account
            AccountLookup.ListUnavailable -> return null
            AccountLookup.NotInList -> return SwarmAddOutcome.NotASwarmAccount
        }
        return addSwarmCandidate(account, candidate, byHand = true)
    }

    private suspend fun lookupNow(): AccountLookup = lookup.first()

    private fun parse(form: AddForm): Parsed = when (form.kind) {
        AddKind.CHEQUEBOOK -> when (val input = Eip55.parse(form.text)) {
            is AddressInput.Valid -> Parsed.Candidate(SwarmCandidate.ChequebookAt(input.address))
            AddressInput.NotAnAddress -> Parsed.Error(SwarmTexts.NOT_AN_ADDRESS)
            AddressInput.ChecksumMismatch -> Parsed.Error(SwarmTexts.CHECKSUM)
        }
        AddKind.BATCH -> BatchId.parse(form.text)?.let { Parsed.Candidate(SwarmCandidate.Batch(it)) }
            ?: Parsed.Error(SwarmTexts.NOT_A_BATCH_ID)
    }

    private sealed interface Parsed {
        data class Candidate(val candidate: SwarmCandidate) : Parsed
        data class Error(val message: String) : Parsed
    }

    /**
     * What the batch actions need here: Gnosis Chain [on], an account that may pay ([pays]), and the
     * node's own key on this device ([nodeSigns]), which a dilution needs.
     */
    private data class Signs(val on: Boolean, val pays: Boolean, val nodeSigns: Boolean) {
        fun pay(): SwarmAction = when {
            !on -> SwarmAction(SwarmTexts.NETWORK_OFF_ACTION, turnOn = true)
            !pays -> SwarmAction(SwarmTexts.NO_PAYER)
            else -> SwarmAction()
        }

        /** An action on a chequebook or a batch kept but not verified now: what it waits for. */
        fun unverified(): SwarmAction =
            if (!on) SwarmAction(SwarmTexts.NETWORK_OFF_ACTION, turnOn = true) else SwarmAction(SwarmTexts.NOT_VERIFIED)

        /** An action only the node's own key may sign: [notHeld] says so where that key is not here. */
        fun sign(notHeld: String = SwarmTexts.NODE_KEY_NOT_HELD): SwarmAction = when {
            !on -> SwarmAction(SwarmTexts.NETWORK_OFF_ACTION, turnOn = true)
            !nodeSigns -> SwarmAction(notHeld)
            else -> SwarmAction()
        }
    }

    private fun ready(label: String, reading: SwarmNodeReading.Read, form: AddForm?, signs: Signs): SwarmUiState.Ready {
        val chequebooks = reading.chequebooks.map { chequebookRow(it, signs) }
        val batches = reading.batches.map { batchRow(it, signs) }
        return SwarmUiState.Ready(
            label = label,
            address = Eip55.format(node.address),
            chequebooks = chequebooks,
            chequebookNote = SwarmTexts.NO_CHEQUEBOOK.takeIf { chequebooks.isEmpty() },
            batches = batches,
            batchNote = SwarmTexts.NO_BATCH.takeIf { batches.isEmpty() },
            note = SwarmTexts.SET_ASIDE.takeIf { reading.setAside },
            adding = form,
        )
    }

    private fun chequebookRow(item: SwarmItem<Address, ChequebookFinding>, signs: Signs): ChequebookRow {
        val address = Eip55.format(item.key)
        return when (val reading = item.reading) {
            null -> ChequebookRow(address, emptyList(), null, SwarmTexts.NOT_READ, withdraw = signs.unverified())
            is SwarmReading.Verified -> when (val finding = reading.finding) {
                is ChequebookFinding.Node -> ChequebookRow(
                    address,
                    listOf(
                        "Balance ${SwarmTexts.bzz(finding.chequebook.balance)}",
                        "Free to withdraw ${SwarmTexts.bzz(finding.chequebook.liquidBalance)}",
                        "Paid out ${SwarmTexts.bzz(finding.chequebook.totalPaidOut)}",
                    ),
                    SwarmTexts.BOUNCED.takeIf { finding.chequebook.bounced },
                    SwarmTexts.verified(reading.blockNumber, item.held),
                    withdraw = signs.sign(SwarmTexts.NODE_KEY_NOT_WITHDRAWING).let { action ->
                        if (action.reason == null && finding.chequebook.liquidBalance.isZero) {
                            SwarmAction(SwarmTexts.NOTHING_FREE)
                        } else {
                            action
                        }
                    },
                )
                ChequebookFinding.NotAChequebook -> ChequebookRow(
                    address,
                    emptyList(),
                    null,
                    stated(SwarmTexts.NO_LONGER_A_CHEQUEBOOK, reading, item),
                )
                is ChequebookFinding.OthersIssuer -> ChequebookRow(
                    address,
                    emptyList(),
                    null,
                    stated(SwarmTexts.othersChequebook(Eip55.format(finding.issuer)), reading, item),
                )
            }
            else -> ChequebookRow(address, emptyList(), null, unread(reading), withdraw = signs.unverified())
        }
    }

    private fun batchRow(item: SwarmItem<BatchId, BatchFinding>, signs: Signs): BatchRow {
        val id = "0x" + item.key.hex
        val short = id.take(10) + "…" + id.takeLast(4)
        return when (val reading = item.reading) {
            null -> BatchRow(
                id,
                short,
                null,
                null,
                false,
                SwarmTexts.NOT_READ,
                topUp = signs.unverified(),
                dilute = signs.unverified(),
            )
            is SwarmReading.Verified -> when (val finding = reading.finding) {
                is BatchFinding.Node -> {
                    // An expired batch takes neither: a new one is the way on.
                    val expired = SwarmAction(SwarmTexts.EXPIRED_ACTION).takeIf { finding.batch.expired }
                    BatchRow(
                        id,
                        short,
                        SwarmTexts.capacity(finding.batch.depth),
                        SwarmTexts.timeLeft(finding.batch),
                        finding.batch.immutable,
                        SwarmTexts.verified(reading.blockNumber, item.held),
                        topUp = expired ?: signs.pay(),
                        dilute = expired ?: signs.sign(),
                    )
                }
                BatchFinding.Gone ->
                    BatchRow(id, short, null, null, false, stated(SwarmTexts.GONE, reading, item))
                is BatchFinding.OthersOwner -> BatchRow(
                    id,
                    short,
                    null,
                    null,
                    false,
                    stated(SwarmTexts.othersBatch(Eip55.format(finding.owner)), reading, item),
                )
            }
            // Kept, and not read now (Gnosis Chain off, syncing, unproven): greyed out, never hidden.
            else -> BatchRow(
                id,
                short,
                null,
                null,
                false,
                unread(reading),
                topUp = signs.unverified(),
                dilute = signs.unverified(),
            )
        }
    }

    /** [statement], verified as [reading] was, held as [item] is. */
    private fun stated(statement: String, reading: SwarmReading.Verified<*>, item: SwarmItem<*, *>): String =
        SwarmTexts.stated(statement, reading.blockNumber, item.held)

    /** Whether [account] signs here on Gnosis Chain: an EOA there whose key is on this device. */
    private fun signsHere(account: Account): Boolean = account.network == node.network &&
        account.kind == AccountKind.EOA && account.custody == AccountCustody.InKeyStore

    private fun unread(reading: SwarmReading<*>): String = when (reading) {
        SwarmReading.Syncing -> SwarmTexts.SYNCING
        SwarmReading.NotPinned -> SwarmTexts.NOT_SWARM
        is SwarmReading.Unavailable -> SwarmTexts.unavailable(reading.reason)
        is SwarmReading.Verified -> error("a verified reading is read")
    }
}
