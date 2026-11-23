package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.data.swarm.PinnedSwarm
import eth.serotine.data.swarm.SwarmDeployments
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Chequebook
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PostageBatch
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BatchFinding
import eth.serotine.domain.repository.ChequebookFinding
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SwarmContracts
import eth.serotine.domain.repository.SwarmReading
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [SwarmContracts] over a verified light client (docs/swarm.md, *Recognition*), against Bee's
 * contracts pinned for [served] ([pins], [SwarmDeployments]).
 *
 * - **A chequebook**: its code, proven by [VerifiedChain.proveCode], must hash to the pinned
 *   chequebook proxy, and the pinned factory's and master copy's code to their pins. Then, at one block
 *   ([OneBlockCalls]): the factory's `deployedContracts(chequebook)` must be true and the
 *   chequebook's `token()` xBZZ, or it is [ChequebookFinding.NotAChequebook]; its `issuer()`
 *   must be the node asked about, or it is [ChequebookFinding.OthersIssuer]; then its
 *   `balance()`, `liquidBalance()`, `totalPaidOut()` and `bounced()`.
 * - **A batch**: the PostageStamp's code must hash to its pin. Then, at one block: `batches(id)`;
 *   a zero owner is [BatchFinding.Gone], another owner [BatchFinding.OthersOwner]; then
 *   `remainingBalance(id)` and `lastPrice()`.
 *
 * Code that matched its pin is not proven again in this process: none of these contracts is a
 * proxy that can change its code, and a contract's code cannot change after deployment. A set of
 * calls that straddles two blocks, or in which a call gave no answer, is made again, after a pause
 * for the latter, up to [READ_TRIES] reads in all, and is otherwise unproven for that poll. A
 * getter that reverts or answers what the pinned contract's never does is unproven; a refusal is
 * [ReadUnavailable.REFUSED]. Polled, cached and refused as [ProvenReads] describes, keyed by the
 * node and the chequebook or batch: the node's own for [OWN_KEPT_FOR_HEADS] heads, another's for
 * [OTHER_KEPT_FOR_HEADS], and none yet for [CHANGING_KEPT_FOR_HEADS].
 */
class VerifiedSwarmContracts internal constructor(
    private val served: Network,
    chain: VerifiedChain?,
    dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
    private val pins: PinnedSwarm? = SwarmDeployments.of(served),
) : SwarmContracts {

    /**
     * Each address whose code was proven to match a pin in this process, with that pin: a match of
     * one pin never stands in for another's (a pinned contract asked about as a chequebook).
     */
    private val matched = mutableSetOf<Pair<Address, String>>()
    private val matchedLock = Mutex()

    private val chequebooks = ProvenReads<Pair<Address, Address>, ChequebookFinding>(
        chain,
        dispatcher,
        pollInterval,
        keptForHeads = {
            when (it) {
                is ChequebookFinding.Node -> OWN_KEPT_FOR_HEADS
                // No chequebook yet may be one as soon as its deployment is mined.
                ChequebookFinding.NotAChequebook -> CHANGING_KEPT_FOR_HEADS
                is ChequebookFinding.OthersIssuer -> OTHER_KEPT_FOR_HEADS
            }
        },
    ) { verified, (node, chequebook) -> readChequebook(verified, checkNotNull(pins), node, chequebook) }

    private val batches = ProvenReads<Pair<Address, BatchId>, BatchFinding>(
        chain,
        dispatcher,
        pollInterval,
        keptForHeads = {
            when (it) {
                is BatchFinding.Node -> OWN_KEPT_FOR_HEADS
                // No batch yet is one as soon as its `createBatch` is mined: a batch bought here.
                BatchFinding.Gone -> CHANGING_KEPT_FOR_HEADS
                is BatchFinding.OthersOwner -> OTHER_KEPT_FOR_HEADS
            }
        },
    ) { verified, (node, id) -> readBatch(verified, checkNotNull(pins), node, id) }

    override fun observeChequebook(
        network: Network,
        node: Address,
        chequebook: Address,
    ): Flow<SwarmReading<ChequebookFinding>> {
        if (network != served) return flowOf(SwarmReading.Unavailable(ReadUnavailable.NO_ENGINE))
        if (pins == null) return flowOf(SwarmReading.NotPinned)
        return chequebooks.observe(node to chequebook).map { it.toSwarmReading() }
    }

    override fun observeBatch(network: Network, node: Address, id: BatchId): Flow<SwarmReading<BatchFinding>> {
        if (network != served) return flowOf(SwarmReading.Unavailable(ReadUnavailable.NO_ENGINE))
        if (pins == null) return flowOf(SwarmReading.NotPinned)
        return batches.observe(node to id).map { it.toSwarmReading() }
    }

    /**
     * Whether the code at [address], proven, hashes to [pin]; null when it could not be proven. A
     * match is remembered; a mismatch or a missing proof is asked again next time.
     */
    private suspend fun codeCheck(chain: VerifiedChain, address: Address, pin: String): CodeCheck? {
        if (matchedLock.withLock { (address to pin) in matched }) return CodeCheck.Matches
        val code = chain.proveCode(address) ?: return null
        if (codeHash(code.codeHex) != pin) return CodeCheck.Differs(code.blockNumber)
        matchedLock.withLock { matched += address to pin }
        return CodeCheck.Matches
    }

    /** What [codeCheck] found. */
    private sealed interface CodeCheck {
        data object Matches : CodeCheck

        /** The code proven at [blockNumber] is not the pinned one. */
        data class Differs(val blockNumber: Long) : CodeCheck
    }

    private suspend fun readChequebook(
        chain: VerifiedChain,
        pins: PinnedSwarm,
        node: Address,
        chequebook: Address,
    ): EngineReading<ChequebookFinding>? {
        when (val check = codeCheck(chain, chequebook, pins.chequebookCodeHash)) {
            null -> return null
            is CodeCheck.Differs -> return EngineReading.Proven(ChequebookFinding.NotAChequebook, check.blockNumber)
            CodeCheck.Matches -> Unit
        }
        // A factory or a master copy that is not the pinned one proves nothing: unavailable, never wrong.
        // The chequebook's getters run the master copy's code, which its proxy delegates to.
        val factory = codeCheck(chain, pins.chequebookFactory, pins.chequebookFactoryCodeHash)
        if (factory != CodeCheck.Matches) return null
        val master = codeCheck(chain, pins.chequebookMaster, pins.chequebookMasterCodeHash)
        if (master != CodeCheck.Matches) return null
        return readAtOneBlock(chain) { calls -> chequebookAt(calls, pins, node, chequebook) }
    }

    /** The chequebook's getters at one block; null once [calls] ended. */
    private fun chequebookAt(
        calls: OneBlockCalls,
        pins: PinnedSwarm,
        node: Address,
        chequebook: Address,
    ): ChequebookFinding? {
        val deployed = calls.bool(pins.chequebookFactory, deployedContracts(chequebook)) ?: return null
        val token = calls.address(chequebook, TOKEN) ?: return null
        if (!deployed || token != pins.bzz) return ChequebookFinding.NotAChequebook
        val issuer = calls.address(chequebook, ISSUER) ?: return null
        if (issuer != node) return ChequebookFinding.OthersIssuer(issuer)
        val balance = calls.uint(chequebook, BALANCE) ?: return null
        val liquid = calls.uint(chequebook, LIQUID_BALANCE) ?: return null
        val paidOut = calls.uint(chequebook, TOTAL_PAID_OUT) ?: return null
        val bounced = calls.bool(chequebook, BOUNCED) ?: return null
        // The contract keeps its liquid balance within its balance.
        if (liquid > balance) return calls.end(OneBlockCalls.Ended.NOT_THE_CONTRACTS_ANSWER)
        return ChequebookFinding.Node(Chequebook(chequebook, issuer, balance, liquid, paidOut, bounced))
    }

    private suspend fun readBatch(
        chain: VerifiedChain,
        pins: PinnedSwarm,
        node: Address,
        id: BatchId,
    ): EngineReading<BatchFinding>? {
        if (codeCheck(chain, pins.postageStamp, pins.postageStampCodeHash) != CodeCheck.Matches) return null
        return readAtOneBlock(chain) { calls -> batchAt(calls, pins.postageStamp, node, id) }
    }

    /** The batch's getters at one block; null once [calls] ended. */
    private fun batchAt(calls: OneBlockCalls, stamp: Address, node: Address, id: BatchId): BatchFinding? {
        val returned = calls.call(stamp, batches(id)) ?: return null
        val words = words(returned) ?: return calls.end(OneBlockCalls.Ended.NOT_THE_CONTRACTS_ANSWER)
        if (words.size != BATCH_WORDS) return calls.end(OneBlockCalls.Ended.NOT_THE_CONTRACTS_ANSWER)
        val owner = addressOf(words[0]) ?: return calls.end(OneBlockCalls.Ended.NOT_THE_CONTRACTS_ANSWER)
        if (owner == ZERO) return BatchFinding.Gone
        if (owner != node) return BatchFinding.OthersOwner(owner)
        val depth = smallOf(words[1])
        val bucketDepth = smallOf(words[2])
        val immutable = boolOf(words[3])
        if (depth == null || bucketDepth == null || immutable == null || bucketDepth > depth) {
            return calls.end(OneBlockCalls.Ended.NOT_THE_CONTRACTS_ANSWER)
        }
        val remaining = calls.uint(stamp, remainingBalance(id)) ?: return null
        val price = calls.uint(stamp, LAST_PRICE) ?: return null
        return BatchFinding.Node(PostageBatch(id, owner, depth, bucketDepth, immutable, remaining, price))
    }

    /**
     * [read] made at one block, again at once when its calls straddle two, after [RETRY_PAUSE]
     * when one gave no answer, [READ_TRIES] reads in all; null when unproven.
     */
    private suspend fun <T> readAtOneBlock(chain: VerifiedChain, read: (OneBlockCalls) -> T?): EngineReading<T>? {
        for (attempt in 1..READ_TRIES) {
            currentCoroutineContext().ensureActive()
            val calls = OneBlockCalls(chain)
            val value = read(calls)
            if (value != null) return EngineReading.Proven(value, checkNotNull(calls.block), calls.hash)
            when (calls.ended) {
                OneBlockCalls.Ended.STRADDLED -> Unit
                OneBlockCalls.Ended.NO_ANSWER -> if (attempt < READ_TRIES) delay(RETRY_PAUSE)
                OneBlockCalls.Ended.NOT_THE_CONTRACTS_ANSWER -> return null
                OneBlockCalls.Ended.REFUSED -> return EngineReading.Unavailable(ReadUnavailable.REFUSED)
            }
        }
        return null
    }

    internal companion object {
        // The chequebook's getters (swap-swear-and-swindle v0.6.9, ERC20SimpleSwap).
        val ISSUER: ByteArray = "1d143848".fromHex()
        val TOKEN: ByteArray = "fc0c546a".fromHex()
        val BALANCE: ByteArray = "b69ef8a8".fromHex()
        val LIQUID_BALANCE: ByteArray = "b7ec1a33".fromHex()
        val TOTAL_PAID_OUT: ByteArray = "1357e1dc".fromHex()
        val BOUNCED: ByteArray = "b648b417".fromHex()

        // The PostageStamp's (storage-incentives, PostageStamp).
        val LAST_PRICE: ByteArray = "053f14da".fromHex()

        /** The factory's `deployedContracts(address)`: whether it deployed [contract]. */
        fun deployedContracts(contract: Address): ByteArray =
            ("c70242ad" + "0".repeat(24) + contract.hex.removePrefix("0x").lowercase()).fromHex()

        /**
         * The PostageStamp's `batches(bytes32)`: owner, depth, bucket depth, immutable, normalised
         * balance and last update.
         */
        fun batches(id: BatchId): ByteArray = ("c81e25ab" + id.hex).fromHex()

        /** The PostageStamp's `remainingBalance(bytes32)`: the batch's balance left per chunk. */
        fun remainingBalance(id: BatchId): ByteArray = ("d71ba7c4" + id.hex).fromHex()

        /** Words `batches(id)` returns. */
        const val BATCH_WORDS = 6

        /** Reads of a set of calls made while they straddle two blocks or one gives no answer, the first included. */
        const val READ_TRIES = 5

        /** The pause before the calls are made again after one gave no answer: no tight loop on a lost peer. */
        val RETRY_PAUSE = 1.seconds

        /**
         * Heads a node's own chequebook or batch stands, about a minute of Gnosis Chain's: a batch's
         * balance falls with every block, and the screen counts its time to live in days.
         */
        const val OWN_KEPT_FOR_HEADS = 12L

        /**
         * Heads another's chequebook or batch stands, about ten minutes: an issuer changes never, a
         * batch's owner rarely if ever.
         */
        const val OTHER_KEPT_FOR_HEADS = 120L

        /**
         * Heads no chequebook or no batch stands: one, since a mined transaction makes it the node's
         * (a batch bought here, a chequebook deployed), and a screen that waits for it should see it
         * at the next head.
         */
        const val CHANGING_KEPT_FOR_HEADS = 1L

        private val ZERO = Address.of("0x" + "0".repeat(40))
    }
}

private fun <T> EngineReading<T>.toSwarmReading(): SwarmReading<T> = when (this) {
    EngineReading.Syncing, is EngineReading.LastKnown -> SwarmReading.Syncing
    is EngineReading.Proven -> SwarmReading.Verified(value, blockNumber)
    is EngineReading.Unavailable -> SwarmReading.Unavailable(reason)
}

/** [returnedHex] as 32-byte words, or null when it is not a whole number of them. */
private fun words(returnedHex: String): List<String>? {
    if (returnedHex.length % 64 != 0 || !returnedHex.all { it in '0'..'9' || it in 'a'..'f' }) return null
    return returnedHex.chunked(64)
}

private fun addressOf(word: String): Address? =
    if (word.take(24).all { it == '0' }) Address.parse("0x" + word.drop(24)) else null

private fun boolOf(word: String): Boolean? = when (word) {
    "0".repeat(64) -> false
    "0".repeat(63) + "1" -> true
    else -> null
}

/** A uint8 word's value, or null when it is not one. */
private fun smallOf(word: String): Int? =
    if (word.take(62).all { it == '0' }) word.drop(62).toInt(16) else null

/** The one word [data] returned from [to], as an address; null once the read ended. */
private fun OneBlockCalls.address(to: Address, data: ByteArray): Address? {
    val hex = call(to, data) ?: return null
    return words(hex)?.singleOrNull()?.let(::addressOf) ?: end(OneBlockCalls.Ended.NOT_THE_CONTRACTS_ANSWER)
}

/** The one word [data] returned from [to], as a bool; null once the read ended. */
private fun OneBlockCalls.bool(to: Address, data: ByteArray): Boolean? {
    val hex = call(to, data) ?: return null
    return words(hex)?.singleOrNull()?.let(::boolOf) ?: end(OneBlockCalls.Ended.NOT_THE_CONTRACTS_ANSWER)
}

/** The one word [data] returned from [to], as a uint256; null once the read ended. */
private fun OneBlockCalls.uint(to: Address, data: ByteArray): UInt256? {
    val hex = call(to, data) ?: return null
    val word = words(hex)?.singleOrNull() ?: return end(OneBlockCalls.Ended.NOT_THE_CONTRACTS_ANSWER)
    return UInt256.fromBigEndian(word.fromHex())
}
