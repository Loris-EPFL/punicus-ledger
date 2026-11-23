package eth.serotine.domain.repository

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Network
import kotlinx.coroutines.flow.Flow

/**
 * The port for what may be a Swarm node's chequebook or postage batch, per node (docs/swarm.md,
 * *Where the candidates come from*): added by hand, bought here, or found in the log index. A
 * candidate is public data, never chain data: it is shown only once [SwarmContracts] proves it the
 * node's. :data keeps them in an app-private file, so they survive a restart.
 */
interface SwarmCandidates {
    /** [node]'s candidates, re-emitted on each change, or why they cannot be read. Never throws. */
    fun observe(node: AccountId): Flow<SwarmCandidateList>

    /**
     * Every node on [network] that has a candidate kept, with them, re-emitted on each change; null
     * while the candidates cannot be read. Never throws.
     */
    fun observeNodes(network: Network): Flow<Map<AccountId, SwarmCandidateList.Loaded>?>

    /** Adds [candidate] to [node]'s; [SwarmCandidateChange.ALREADY_PRESENT] when it is there already. Never throws. */
    suspend fun add(node: AccountId, candidate: SwarmCandidate): SwarmCandidateChange

    /** Takes [candidate] out of [node]'s; [SwarmCandidateChange.NOT_FOUND] when it is not there. Never throws. */
    suspend fun remove(node: AccountId, candidate: SwarmCandidate): SwarmCandidateChange
}

/** What may be one of a node's: a chequebook, or a postage batch. */
sealed interface SwarmCandidate {
    /** The contract at [address], as the node's chequebook. */
    data class ChequebookAt(val address: Address) : SwarmCandidate

    /** The batch [id], as one of the node's. */
    data class Batch(val id: BatchId) : SwarmCandidate
}

/** What [SwarmCandidates.observe] emits. */
sealed interface SwarmCandidateList {
    /**
     * The node's [chequebooks] and [batches], each in the order added; [setAside] once the stored
     * candidates were damaged and set aside, so these started over.
     */
    data class Loaded(
        val chequebooks: List<Address>,
        val batches: List<BatchId>,
        val setAside: Boolean = false,
    ) : SwarmCandidateList

    /** The stored candidates exist but cannot be read; nothing can be changed until they can. */
    data object Unreadable : SwarmCandidateList
}

/** The outcome of a [SwarmCandidates] change. */
enum class SwarmCandidateChange {
    /** Done. */
    CHANGED,

    /** It already was so; nothing changed. */
    ALREADY_PRESENT,

    /** Nothing is there to change. */
    NOT_FOUND,

    /** The candidates could not be saved, or cannot be read; nothing changed. */
    NOT_SAVED,
}
