package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.BatchFinding
import eth.serotine.domain.repository.ChequebookFinding
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SwarmCandidate
import eth.serotine.domain.repository.SwarmCandidateChange
import eth.serotine.domain.repository.SwarmCandidateList
import eth.serotine.domain.repository.SwarmCandidates
import eth.serotine.domain.repository.SwarmContracts
import eth.serotine.domain.repository.SwarmReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** The one network Bee's contracts are pinned on (docs/swarm.md, *The contracts*). */
internal val SWARM_NETWORK = Network.GNOSIS

/** Whether [account] can be a Swarm node: an EOA on [SWARM_NETWORK] (docs/swarm.md, *Recognition*). */
internal fun canBeSwarmNode(account: Account): Boolean =
    account.network == SWARM_NETWORK && account.kind == AccountKind.EOA

/**
 * A Swarm node as the wallet sees it: every candidate kept for it (docs/swarm.md, *Where the
 * candidates come from*), each with what the light client proves it to be (docs/swarm.md,
 * *Recognition*). An account that is no EOA on Gnosis Chain is no Swarm node, and nothing is read.
 */
class ObserveSwarmNode(
    private val candidates: SwarmCandidates,
    private val contracts: SwarmContracts,
) {
    /** [account]'s Swarm reading, re-emitted as its candidates or their proofs change. Never fails. */
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(account: Account): Flow<SwarmNodeReading> {
        if (!canBeSwarmNode(account)) return flowOf(SwarmNodeReading.NotASwarmAccount)
        val node = account.id
        return candidates.observe(node).flatMapLatest { list ->
            when (list) {
                SwarmCandidateList.Unreadable -> flowOf(SwarmNodeReading.Unreadable)
                is SwarmCandidateList.Loaded -> read(node, list)
            }
        }.distinctUntilChanged()
    }

    private fun read(node: AccountId, list: SwarmCandidateList.Loaded): Flow<SwarmNodeReading> {
        val chequebooks = list.chequebooks.map { address ->
            contracts.observeChequebook(node.network, node.address, address)
                .holdingVerifiedSwarm()
                .map { SwarmItem(address, it.value, held = it.held) }
                .onStart { emit(SwarmItem(address, null)) }
        }
        val batches = list.batches.map { id ->
            contracts.observeBatch(node.network, node.address, id)
                .holdingVerifiedSwarm()
                .map { SwarmItem(id, it.value, held = it.held) }
                .onStart { emit(SwarmItem(id, null)) }
        }
        return combine(all(chequebooks), all(batches)) { found, bought ->
            SwarmNodeReading.Read(found, bought, setAside = list.setAside)
        }
    }

    /** Every flow's latest, in their order; an empty list at once when there are none. */
    private inline fun <reified T> all(flows: List<Flow<T>>): Flow<List<T>> =
        if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { it.toList() }
}

/**
 * One candidate and what the light client proves it to be: [reading], or null until its first
 * reading lands. Each candidate starts as not read yet, so the node is told at once and filled in
 * as the reads land, never held back by its slowest candidate. [held] when [reading] is the last
 * verified one, standing in for reads that did not answer since, for an hour at most (owner's
 * decisions, 2026-11-17; docs/swarm.md, *What the Swarm screen shows*): shown as such, never acted on.
 */
data class SwarmItem<K, T>(val key: K, val reading: SwarmReading<T>?, val held: Boolean = false)

/**
 * A Swarm reading, its last [SwarmReading.Verified] answer standing while the readings after it
 * only did not answer, for [HOLD_LIMIT] at most ([holdingLastMarked]).
 */
private fun <T : Any> Flow<SwarmReading<T>>.holdingVerifiedSwarm(): Flow<Held<SwarmReading<T>>> = holdingLastMarked(
    verified = { it is SwarmReading.Verified },
    transient = { it == SwarmReading.Syncing || (it as? SwarmReading.Unavailable)?.reason?.transient == true },
)

/** One answer of [ObserveSwarmNode]. */
sealed interface SwarmNodeReading {

    /** The account is no EOA on Gnosis Chain, where Bee's contracts are pinned: it is no Swarm node. */
    data object NotASwarmAccount : SwarmNodeReading

    /** The candidates kept for the node cannot be read. */
    data object Unreadable : SwarmNodeReading

    /**
     * The node's [chequebooks] and [batches] kept, each with its reading, in the order added;
     * [setAside] once the stored candidates were damaged and set aside.
     */
    data class Read(
        val chequebooks: List<SwarmItem<Address, ChequebookFinding>>,
        val batches: List<SwarmItem<BatchId, BatchFinding>>,
        val setAside: Boolean = false,
    ) : SwarmNodeReading {
        /**
         * Whether the account is a Swarm node: a chequebook or a batch is proven its own
         * (docs/swarm.md, *Recognition*). What is not verified yet never counts.
         */
        val recognised: Boolean
            get() = chequebooks.any { (it.reading as? SwarmReading.Verified)?.finding is ChequebookFinding.Node } ||
                batches.any { (it.reading as? SwarmReading.Verified)?.finding is BatchFinding.Node }
    }
}

/**
 * Adds a chequebook or a batch to a node by hand (docs/swarm.md, *Where the candidates come from*:
 * "Kept once proven; refused, saying why, when not"): it is read first, and kept only once the
 * light client proves it the node's.
 */
class AddSwarmCandidate(
    private val candidates: SwarmCandidates,
    private val contracts: SwarmContracts,
) {
    /**
     * Reads [candidate] for [account] and keeps it when it is the node's. [byHand] is the user's
     * own add; one found (in the log index) is never kept when the user removed it. Never throws.
     */
    suspend operator fun invoke(account: Account, candidate: SwarmCandidate, byHand: Boolean = true): SwarmAddOutcome {
        if (!canBeSwarmNode(account)) return SwarmAddOutcome.NotASwarmAccount
        val node = account.id
        val refusal = when (candidate) {
            is SwarmCandidate.ChequebookAt -> {
                val reading = contracts.observeChequebook(node.network, node.address, candidate.address).first()
                when (reading) {
                    is SwarmReading.Verified -> when (val finding = reading.finding) {
                        ChequebookFinding.NotAChequebook -> SwarmAddOutcome.NotAChequebook
                        is ChequebookFinding.OthersIssuer -> SwarmAddOutcome.OthersChequebook(finding.issuer)
                        is ChequebookFinding.Node -> null
                    }
                    else -> unread(reading)
                }
            }
            is SwarmCandidate.Batch -> {
                val reading = contracts.observeBatch(node.network, node.address, candidate.id).first()
                when (reading) {
                    is SwarmReading.Verified -> when (val finding = reading.finding) {
                        BatchFinding.Gone -> SwarmAddOutcome.NoSuchBatch
                        is BatchFinding.OthersOwner -> SwarmAddOutcome.OthersBatch(finding.owner)
                        is BatchFinding.Node -> null
                    }
                    else -> unread(reading)
                }
            }
        }
        if (refusal != null) return refusal
        return when (candidates.add(node, candidate, byHand)) {
            SwarmCandidateChange.CHANGED -> SwarmAddOutcome.Added
            SwarmCandidateChange.ALREADY_PRESENT -> SwarmAddOutcome.AlreadyAdded
            SwarmCandidateChange.DISMISSED -> SwarmAddOutcome.Dismissed
            SwarmCandidateChange.NOT_FOUND, SwarmCandidateChange.NOT_SAVED -> SwarmAddOutcome.NotSaved
        }
    }

    private fun unread(reading: SwarmReading<*>): SwarmAddOutcome = when (reading) {
        SwarmReading.Syncing -> SwarmAddOutcome.NotVerified(ReadUnavailable.SYNCING)
        SwarmReading.NotPinned -> SwarmAddOutcome.NotASwarmAccount
        is SwarmReading.Unavailable -> SwarmAddOutcome.NotVerified(reading.reason)
        is SwarmReading.Verified -> error("a verified reading is no refusal")
    }
}

/** What [AddSwarmCandidate] made of a candidate. */
sealed interface SwarmAddOutcome {
    /** Proven the node's, and kept. */
    data object Added : SwarmAddOutcome

    /** Proven the node's, and kept already. */
    data object AlreadyAdded : SwarmAddOutcome

    /** Found, not added by hand, and one the user removed: it stays removed. */
    data object Dismissed : SwarmAddOutcome

    /** The account is no EOA on Gnosis Chain, where Bee's contracts are pinned. */
    data object NotASwarmAccount : SwarmAddOutcome

    /** The address is no chequebook of Bee's pinned factory. */
    data object NotAChequebook : SwarmAddOutcome

    /** A chequebook whose issuer is [issuer], not the node. */
    data class OthersChequebook(val issuer: Address) : SwarmAddOutcome

    /** The PostageStamp holds no batch of that id. */
    data object NoSuchBatch : SwarmAddOutcome

    /** A batch [owner] owns, not the node. */
    data class OthersBatch(val owner: Address) : SwarmAddOutcome

    /** It could not be verified right now, for [reason]: nothing is kept on an unverified reading. */
    data class NotVerified(val reason: ReadUnavailable) : SwarmAddOutcome

    /** Proven the node's, but the candidates could not be saved. */
    data object NotSaved : SwarmAddOutcome
}

/** Takes a chequebook or a batch the user added out of a node's. */
class RemoveSwarmCandidate(private val candidates: SwarmCandidates) {
    /** Removes [candidate] from [node]'s; true once it is not there any more. Never throws. */
    suspend operator fun invoke(node: AccountId, candidate: SwarmCandidate): Boolean =
        when (candidates.remove(node, candidate)) {
            SwarmCandidateChange.CHANGED, SwarmCandidateChange.NOT_FOUND -> true
            SwarmCandidateChange.ALREADY_PRESENT,
            SwarmCandidateChange.NOT_SAVED,
            SwarmCandidateChange.DISMISSED,
            -> false
        }
}
