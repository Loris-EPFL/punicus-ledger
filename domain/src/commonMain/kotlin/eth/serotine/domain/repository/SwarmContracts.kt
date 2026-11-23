package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Chequebook
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PostageBatch
import eth.serotine.domain.model.SwarmDeployment
import kotlinx.coroutines.flow.Flow

/**
 * The port for a Swarm node's chequebook and postage batches as the embedded light client proves
 * them (docs/swarm.md; CLAUDE.md, *Trust*). :data implements it over the Myotis engine
 * (`eth.serotine.data.engine`), against Bee's contracts pinned for the network by address and
 * code hash, every value a verified call, all of one reading at one block. Nothing else may
 * answer it. A network that pins no Swarm contracts answers [SwarmReading.NotPinned].
 */
interface SwarmContracts {
    /**
     * What the contract at [chequebook] is to the node at [node] on [network] (docs/swarm.md,
     * *A chequebook is proven*): its code, proven, the pinned chequebook proxy's; the pinned
     * factory's `deployedContracts` true of it; its `token()` BZZ; and its `issuer()`, which
     * must be [node] — the argument is applied, never ignored: another issuer's is
     * [ChequebookFinding.OthersIssuer]. Read again every few verified heads, a reading kept until
     * then served to every subscriber; consecutive equal readings are not repeated. A [network] no
     * adapter serves here is refused ([ReadUnavailable.NO_ENGINE]). Never fails.
     */
    fun observeChequebook(network: Network, node: Address, chequebook: Address): Flow<SwarmReading<ChequebookFinding>>

    /**
     * What the batch [id] is to the node at [node] on [network] (docs/swarm.md, *A batch is
     * proven*), as the pinned PostageStamp's `batches(id)`, `remainingBalance(id)` and
     * `lastPrice()` answer at one block, its code proven first; [node] is applied, never ignored:
     * another owner's batch is [BatchFinding.OthersOwner]. Polled, kept and refused as
     * [observeChequebook]. Never fails.
     */
    fun observeBatch(network: Network, node: Address, id: BatchId): Flow<SwarmReading<BatchFinding>>

    /**
     * Where Bee's contracts pinned on [network] are, to find candidates in the log index with; null
     * where none are pinned, or no adapter serves [network] here. What it names is pinned, never
     * proven by itself: a candidate found with it is still proven by [observeChequebook] or
     * [observeBatch] before it counts.
     */
    fun deployment(network: Network): SwarmDeployment?
}

/** One answer of [SwarmContracts]. */
sealed interface SwarmReading<out T> {

    /** The light client has no verified head yet. */
    data object Syncing : SwarmReading<Nothing>

    /** No Swarm contracts are pinned on the network asked about: there is nothing to read there. */
    data object NotPinned : SwarmReading<Nothing>

    /** [finding], verified at block [blockNumber]. */
    data class Verified<T>(val finding: T, val blockNumber: Long) : SwarmReading<T>

    /** No verified answer can be had right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : SwarmReading<Nothing>
}

/** What a contract is, as far as a node's chequebook goes. */
sealed interface ChequebookFinding {

    /**
     * No chequebook of Bee's pinned factory: its code is not the pinned proxy, the factory did not
     * deploy it, or its token is not BZZ.
     */
    data object NotAChequebook : ChequebookFinding

    /** A chequebook of the pinned factory whose issuer is [issuer], not the node asked about. */
    data class OthersIssuer(val issuer: Address) : ChequebookFinding

    /** The node's own [chequebook]. */
    data class Node(val chequebook: Chequebook) : ChequebookFinding
}

/** What a batch id is, as far as a node's postage batches go. */
sealed interface BatchFinding {

    /** The PostageStamp holds no batch of that id: never created, or deleted once it expired. */
    data object Gone : BatchFinding

    /** A batch [owner] owns, not the node asked about. */
    data class OthersOwner(val owner: Address) : BatchFinding

    /** The node's own [batch]. */
    data class Node(val batch: PostageBatch) : BatchFinding
}
