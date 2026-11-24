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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /**
     * [account]'s Swarm reading, re-emitted as its candidates or their proofs change. Never fails.
     * Each candidate's reading, and the hold over it, runs for as long as the candidate is kept: a
     * change of the list (one added, removed or found) starts the new ones and stops the gone ones
     * alone, never the others, so a reading held as last verified stands through it.
     */
    operator fun invoke(account: Account): Flow<SwarmNodeReading> {
        if (!canBeSwarmNode(account)) return flowOf(SwarmNodeReading.NotASwarmAccount)
        val node = account.id
        return channelFlow {
            val lock = Mutex()
            val chequebooks = Tracked<Address, ChequebookFinding>()
            val batches = Tracked<BatchId, BatchFinding>()
            var list: SwarmCandidateList.Loaded? = null

            // Called under the lock: the reading as the list orders it, each item as last read.
            suspend fun publish() {
                val current = list ?: return
                send(
                    SwarmNodeReading.Read(
                        current.chequebooks.map { chequebooks.items.getValue(it) },
                        current.batches.map { batches.items.getValue(it) },
                        setAside = current.setAside,
                    ),
                )
            }

            fun <K, T : Any> follow(tracked: Tracked<K, T>, keys: List<K>, read: (K) -> Flow<SwarmReading<T>>) {
                (tracked.jobs.keys - keys.toSet()).forEach { gone ->
                    tracked.jobs.remove(gone)?.cancel()
                    tracked.items.remove(gone)
                }
                keys.filter { it !in tracked.jobs }.forEach { key ->
                    tracked.items[key] = SwarmItem(key, null)
                    tracked.jobs[key] = launch {
                        read(key).holdingVerifiedSwarm().collect { held ->
                            lock.withLock {
                                tracked.items[key] = SwarmItem(key, held.value, held = held.held)
                                publish()
                            }
                        }
                    }
                }
            }

            candidates.observe(node).collect { observed ->
                lock.withLock {
                    when (observed) {
                        SwarmCandidateList.Unreadable -> {
                            follow(chequebooks, emptyList()) { flowOf() }
                            follow(batches, emptyList()) { flowOf() }
                            list = null
                            send(SwarmNodeReading.Unreadable)
                        }
                        is SwarmCandidateList.Loaded -> {
                            follow(chequebooks, observed.chequebooks) {
                                contracts.observeChequebook(node.network, node.address, it)
                            }
                            follow(batches, observed.batches) { contracts.observeBatch(node.network, node.address, it) }
                            list = observed
                            publish()
                        }
                    }
                }
            }
        }.distinctUntilChanged()
    }

    /** The candidates of one kind being read: each one's reading job and its item as last read. */
    private class Tracked<K, T> {
        val jobs = mutableMapOf<K, Job>()
        val items = mutableMapOf<K, SwarmItem<K, T>>()
    }
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

/**
 * The addresses of the wallet's Swarm nodes on Gnosis Chain: each account with a chequebook or a batch
 * kept for it. What a review warns by (docs/swarm.md, *A key that Bee also uses*): a key that is
 * one of them is Bee's too. Empty while the candidates cannot be read.
 */
class ObserveSwarmNodes(private val candidates: SwarmCandidates) {
    operator fun invoke(): Flow<Set<Address>> = candidates.observeNodes(Network.GNOSIS).map { nodes ->
        nodes.orEmpty().filterValues { it.keepsAny }.keys.mapTo(mutableSetOf()) { it.address }
    }.distinctUntilChanged()
}
