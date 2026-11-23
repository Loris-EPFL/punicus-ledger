package eth.serotine.ui.swarm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.repository.BatchFinding
import eth.serotine.domain.repository.ChequebookFinding
import eth.serotine.domain.repository.SwarmCandidate
import eth.serotine.domain.repository.SwarmReading
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.AddSwarmCandidate
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveSwarmNode
import eth.serotine.domain.usecase.RemoveSwarmCandidate
import eth.serotine.domain.usecase.SwarmAddOutcome
import eth.serotine.domain.usecase.SwarmItem
import eth.serotine.domain.usecase.SwarmNodeReading
import eth.serotine.ui.components.AddressInput
import eth.serotine.ui.components.Eip55
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
) : ViewModel() {

    private val form = MutableStateFlow<AddForm?>(null)

    private val lookup = observeAccount(node)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val reading = lookup
        .map { (it as? AccountLookup.Found)?.account }
        .distinctUntilChanged()
        .flatMapLatest { account -> if (account == null) flowOf(null) else observeSwarmNode(account) }

    /** The screen's state. */
    val uiState: StateFlow<SwarmUiState> = combine(lookup, reading, form) { lookup, reading, form ->
        when (lookup) {
            AccountLookup.ListUnavailable -> SwarmUiState.Unavailable(SwarmTexts.LIST_UNAVAILABLE)
            AccountLookup.NotInList -> SwarmUiState.Gone
            is AccountLookup.Found -> when (reading) {
                null -> SwarmUiState.Loading
                SwarmNodeReading.NotASwarmAccount -> SwarmUiState.Unavailable(SwarmTexts.NOT_SWARM)
                SwarmNodeReading.Unreadable -> SwarmUiState.Unavailable(SwarmTexts.UNREADABLE)
                is SwarmNodeReading.Read -> ready(lookup.account.label, reading, form)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SwarmUiState.Loading)

    /** Opens the form that adds a [kind] by hand. */
    fun startAdd(kind: AddKind) {
        form.value = AddForm(kind)
    }

    /** The text typed in the form. */
    fun onAddText(text: String) {
        form.update { it?.copy(text = text, error = null) }
    }

    /** Closes the form. */
    fun cancelAdd() {
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
        form.value = current.copy(checking = true, error = null)
        viewModelScope.launch {
            val error = SwarmTexts.added(addFor(candidate))
            // Kept: the form closes. Not kept: it stays, saying why.
            form.update { open -> if (error == null) null else open?.copy(checking = false, error = error) }
        }
    }

    /** Takes the chequebook at [address] (EIP-55), or the batch [id], out of the node's: remembered as removed. */
    fun removeChequebook(address: String) {
        val parsed = Address.parse(address) ?: return
        viewModelScope.launch { removeSwarmCandidate(node, SwarmCandidate.ChequebookAt(parsed)) }
    }

    /** Takes the batch [id] out of the node's: remembered as removed. */
    fun removeBatch(id: String) {
        val parsed = BatchId.parse(id) ?: return
        viewModelScope.launch { removeSwarmCandidate(node, SwarmCandidate.Batch(parsed)) }
    }

    private suspend fun addFor(candidate: SwarmCandidate): SwarmAddOutcome {
        val account = when (val found = lookupNow()) {
            is AccountLookup.Found -> found.account
            else -> return SwarmAddOutcome.NotASwarmAccount
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

    private fun ready(label: String, reading: SwarmNodeReading.Read, form: AddForm?): SwarmUiState.Ready {
        val chequebooks = reading.chequebooks.map(::chequebookRow)
        val batches = reading.batches.map(::batchRow)
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

    private fun chequebookRow(item: SwarmItem<Address, ChequebookFinding>): ChequebookRow {
        val address = Eip55.format(item.key)
        return when (val reading = item.reading) {
            null -> ChequebookRow(address, emptyList(), null, SwarmTexts.NOT_READ)
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
                )
                ChequebookFinding.NotAChequebook ->
                    ChequebookRow(address, emptyList(), null, SwarmTexts.NO_LONGER_A_CHEQUEBOOK)
                is ChequebookFinding.OthersIssuer ->
                    ChequebookRow(address, emptyList(), null, SwarmTexts.othersChequebook(Eip55.format(finding.issuer)))
            }
            else -> ChequebookRow(address, emptyList(), null, unread(reading))
        }
    }

    private fun batchRow(item: SwarmItem<BatchId, BatchFinding>): BatchRow {
        val id = "0x" + item.key.hex
        val short = id.take(10) + "…" + id.takeLast(4)
        return when (val reading = item.reading) {
            null -> BatchRow(id, short, null, null, false, SwarmTexts.NOT_READ)
            is SwarmReading.Verified -> when (val finding = reading.finding) {
                is BatchFinding.Node -> BatchRow(
                    id,
                    short,
                    SwarmTexts.capacity(finding.batch.depth),
                    SwarmTexts.timeLeft(finding.batch),
                    finding.batch.immutable,
                    SwarmTexts.verified(reading.blockNumber, item.held),
                )
                BatchFinding.Gone -> BatchRow(id, short, null, null, false, SwarmTexts.GONE)
                is BatchFinding.OthersOwner ->
                    BatchRow(id, short, null, null, false, SwarmTexts.othersBatch(Eip55.format(finding.owner)))
            }
            else -> BatchRow(id, short, null, null, false, unread(reading))
        }
    }

    private fun unread(reading: SwarmReading<*>): String = when (reading) {
        SwarmReading.Syncing -> SwarmTexts.SYNCING
        SwarmReading.NotPinned -> SwarmTexts.NOT_SWARM
        is SwarmReading.Unavailable -> SwarmTexts.unavailable(reading.reason)
        is SwarmReading.Verified -> error("a verified reading is read")
    }
}
