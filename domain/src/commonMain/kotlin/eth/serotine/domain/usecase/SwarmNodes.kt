package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
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

/** The one network Bee's contracts are pinned on (docs/swarm.md, *The contracts*). */
internal val SWARM_NETWORK = Network.GNOSIS

/**
 * A Swarm node as the wallet sees it: every candidate kept for it (docs/swarm.md, *Where the
 * candidates come from*), each with what the light client proves it to be (docs/swarm.md,
 * *Recognition*). Off Gnosis Chain an account is no Swarm node, and nothing is read.
 */
class ObserveSwarmNode(
    private val candidates: SwarmCandidates,
    private val contracts: SwarmContracts,
) {
    /** [node]'s Swarm reading, re-emitted as its candidates or their proofs change. Never fails. */
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(node: AccountId): Flow<SwarmNodeReading> {
        if (node.network != SWARM_NETWORK) return flowOf(SwarmNodeReading.NotOnThisNetwork)
        return candidates.observe(node).flatMapLatest { list ->
            when (list) {
                SwarmCandidateList.Unreadable -> flowOf(SwarmNodeReading.Unreadable)
                is SwarmCandidateList.Loaded -> read(node, list)
            }
        }.distinctUntilChanged()
    }

    private fun read(node: AccountId, list: SwarmCandidateList.Loaded): Flow<SwarmNodeReading> {
        val chequebooks = list.chequebooks.map { address ->
            contracts.observeChequebook(node.network, node.address, address).map { SwarmItem(address, it) }
        }
        val batches = list.batches.map { id ->
            contracts.observeBatch(node.network, node.address, id).map { SwarmItem(id, it) }
        }
        return combine(all(chequebooks), all(batches)) { found, bought ->
            SwarmNodeReading.Read(found, bought, setAside = list.setAside)
        }
    }

    /** Every flow's latest, in their order; an empty list at once when there are none. */
    private inline fun <reified T> all(flows: List<Flow<T>>): Flow<List<T>> =
        if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { it.toList() }
}

/** One candidate and what the light client proves it to be. */
data class SwarmItem<K, T>(val key: K, val reading: SwarmReading<T>)

/** One answer of [ObserveSwarmNode]. */
sealed interface SwarmNodeReading {

    /** The account is on a network where Bee's contracts are not pinned: no Swarm node there. */
    data object NotOnThisNetwork : SwarmNodeReading

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
    /** Reads [candidate] for [node] and keeps it when it is the node's. Never throws. */
    suspend operator fun invoke(node: AccountId, candidate: SwarmCandidate): SwarmAddOutcome {
        if (node.network != SWARM_NETWORK) return SwarmAddOutcome.NotOnThisNetwork
        val refusal = when (candidate) {
            is SwarmCandidate.ChequebookAt ->
                when (val reading = contracts.observeChequebook(node.network, node.address, candidate.address).first()) {
                    is SwarmReading.Verified -> when (val finding = reading.finding) {
                        ChequebookFinding.NotAChequebook -> SwarmAddOutcome.NotAChequebook
                        is ChequebookFinding.OthersIssuer -> SwarmAddOutcome.OthersChequebook(finding.issuer)
                        is ChequebookFinding.Node -> null
                    }
                    else -> unread(reading)
                }
            is SwarmCandidate.Batch ->
                when (val reading = contracts.observeBatch(node.network, node.address, candidate.id).first()) {
                    is SwarmReading.Verified -> when (val finding = reading.finding) {
                        BatchFinding.Gone -> SwarmAddOutcome.NoSuchBatch
                        is BatchFinding.OthersOwner -> SwarmAddOutcome.OthersBatch(finding.owner)
                        is BatchFinding.Node -> null
                    }
                    else -> unread(reading)
                }
        }
        if (refusal != null) return refusal
        return when (candidates.add(node, candidate)) {
            SwarmCandidateChange.CHANGED -> SwarmAddOutcome.Added
            SwarmCandidateChange.ALREADY_PRESENT -> SwarmAddOutcome.AlreadyAdded
            SwarmCandidateChange.NOT_FOUND, SwarmCandidateChange.NOT_SAVED -> SwarmAddOutcome.NotSaved
        }
    }

    private fun unread(reading: SwarmReading<*>): SwarmAddOutcome = when (reading) {
        SwarmReading.Syncing -> SwarmAddOutcome.NotVerified(ReadUnavailable.SYNCING)
        SwarmReading.NotPinned -> SwarmAddOutcome.NotOnThisNetwork
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

    /** The account is on a network where Bee's contracts are not pinned. */
    data object NotOnThisNetwork : SwarmAddOutcome

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
            SwarmCandidateChange.ALREADY_PRESENT, SwarmCandidateChange.NOT_SAVED -> false
        }
}
