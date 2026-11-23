package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PostageBatch
import eth.serotine.domain.model.PostageState
import eth.serotine.domain.model.SwarmCalls
import eth.serotine.domain.model.SwarmDeployment
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BatchFinding
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.CowChain
import eth.serotine.domain.repository.CowOrderList
import eth.serotine.domain.repository.CowOrders
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.PostageReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.SwarmCandidate
import eth.serotine.domain.repository.SwarmCandidates
import eth.serotine.domain.repository.SwarmContracts
import eth.serotine.domain.repository.SwarmReading
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlin.random.Random
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// A Swarm node's postage batches bought, topped up and diluted here (docs/swarm.md, *The four
// actions*; owner's decisions, 2026-11-23). Each is a transaction from an EOA whose key is held
// here on Gnosis Chain, quoted, simulated and signed like any other ([CallQuoter]), to the pinned
// PostageStamp only, after a fresh read proves its code and xBZZ's ([SwarmContracts.postage]). A
// purchase and a top-up pull xBZZ, so the PostageStamp is approved first, for exactly the total,
// never more ([QuoteTokenApproval]). Strict in both modes: none is drafted in lenient mode.

/** What a batch's transaction does: buy one, top one up, or dilute one. */
sealed interface BatchCall {
    /** The PostageStamp the call is sent to. */
    val stamp: Address

    /** What the sender's transaction is recorded as. */
    val action: SentAction

    /**
     * A batch for [owner] (the node): [perChunk] for each of its `2^depth` chunks, [immutable] or
     * not, made with [nonce] by [buyer].
     */
    @ConsistentCopyVisibility
    data class Purchase internal constructor(
        val token: Token,
        override val stamp: Address,
        val buyer: Address,
        val owner: Address,
        val depth: Int,
        val perChunk: UInt256,
        val immutable: Boolean,
        val nonce: ByteArray,
    ) : BatchCall {
        /** The id the PostageStamp gives it: the buyer's and the nonce's hash. */
        val id: BatchId
            get() = BatchId.of(buyer, nonce)

        override val action: SentAction
            get() = SentAction.BatchPurchase(token, stamp, owner, id, depth, perChunk, immutable)

        // A nonce is compared by its bytes: two purchases of one nonce are one batch.
        override fun equals(other: Any?): Boolean =
            other is Purchase && token == other.token && stamp == other.stamp && buyer == other.buyer &&
                owner == other.owner &&
                depth == other.depth && perChunk == other.perChunk && immutable == other.immutable &&
                nonce.contentEquals(other.nonce)

        override fun hashCode(): Int = nonce.contentHashCode()
    }

    /** [perChunk] more of [token] for each chunk of [node]'s batch [id], of [depth]. */
    data class TopUp(
        val token: Token,
        override val stamp: Address,
        val node: Address,
        val id: BatchId,
        val depth: Int,
        val perChunk: UInt256,
    ) : BatchCall {
        override val action: SentAction
            get() = SentAction.BatchTopUp(token, stamp, node, id, depth, perChunk)
    }

    /** The batch [id] diluted from [from] to [depth], its owner's own transaction. */
    data class Dilution(override val stamp: Address, val id: BatchId, val from: Int, val depth: Int) : BatchCall {
        override val action: SentAction
            get() = SentAction.BatchDilution(stamp, id, depth)
    }
}

/**
 * A batch's transaction quoted for review: its [call], the [quote] of the sender's transaction, the
 * PostageStamp's [postage] state at [postageBlock] it was quoted on, the [total] it pulls in xBZZ
 * (PLUR; zero for a dilution), and, for a dilution or a top-up, the [batch] as read at that same
 * block. Only this file makes one: what is signed is its [quote]'s transaction.
 */
@ConsistentCopyVisibility
data class BatchQuote internal constructor(
    val network: Network,
    val call: BatchCall,
    val quote: CallQuote,
    val postage: PostageState,
    val postageBlock: Long,
    val total: UInt256,
    val batch: PostageBatch? = null,
) {
    /**
     * Seconds the call's xBZZ buys each chunk at the quoted `lastPrice()`: a purchase's time to live,
     * or the time a top-up adds; null for a dilution, and where no time can be told (a zero price).
     */
    val secondsBought: Long?
        get() = when (call) {
            is BatchCall.Purchase -> SwarmCalls.secondsFor(call.perChunk, postage.lastPrice)
            is BatchCall.TopUp -> SwarmCalls.secondsFor(call.perChunk, postage.lastPrice)
            is BatchCall.Dilution -> null
        }

    /**
     * Seconds the batch has left once the call is mined, at the quoted price: a purchase's time to
     * live; a top-up's time left plus what it adds; a dilution's time left halved for each step.
     * Null where no time can be told.
     */
    val secondsLeftAfter: Long?
        get() = when (call) {
            is BatchCall.Purchase -> secondsBought
            is BatchCall.TopUp -> batch?.remainingPerChunk?.plusOrNull(call.perChunk)
                ?.let { SwarmCalls.secondsFor(it, postage.lastPrice) }
            is BatchCall.Dilution -> batch?.let {
                val left = SwarmCalls.remainingAfterDilution(it.remainingPerChunk, call.from, call.depth)
                SwarmCalls.secondsFor(left, postage.lastPrice)
            }
        }
}

/**
 * How much a purchase or a top-up puts in for each chunk: [PerChunk], an amount of PLUR, or
 * [Lasting], a time, worked out at the PostageStamp's `lastPrice()` read with the quote, at the
 * same block, rounded up to a whole block ([SwarmCalls.perChunkFor]).
 */
sealed interface ChunkFunding {
    /** [plur] for each chunk. */
    data class PerChunk(val plur: UInt256) : ChunkFunding

    /** Enough for each chunk to last [seconds] at the current price. */
    data class Lasting(val seconds: Long) : ChunkFunding {
        init {
            require(seconds > 0) { "a time to last is more than nothing" }
        }
    }
}

/** [funding] in PLUR per chunk at [lastPrice]; null when it does not fit. */
private fun perChunkOf(funding: ChunkFunding, lastPrice: UInt256): UInt256? = when (funding) {
    is ChunkFunding.PerChunk -> funding.plur
    is ChunkFunding.Lasting -> SwarmCalls.perChunkFor(funding.seconds, lastPrice)
}

/** The refusal of a balance per chunk below [minimum] at [lastPrice]: it names the shortest time allowed. */
private fun belowMinimum(minimum: UInt256, lastPrice: UInt256): BatchQuoting.Refused {
    // Rounded up to a whole block: the time named is one that reaches the minimum.
    val seconds = SwarmCalls.secondsFor(minimum, lastPrice)?.let { down ->
        val reaches = SwarmCalls.perChunkFor(down, lastPrice)?.let { it >= minimum } ?: true
        if (reaches) down else down + PostageBatch.BLOCK_SECONDS
    }
    return BatchQuoting.Refused(BatchRefusal.BelowMinimum(minimum, seconds))
}

/** What a batch quote gives. */
sealed interface BatchQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: BatchQuote) : BatchQuoting

    /**
     * The PostageStamp's allowance over the payer's xBZZ falls short of the total: [approval], for
     * exactly the total, is signed first, and the call is quoted again once it is mined, with the
     * same [perChunk] ([ChunkFunding.PerChunk]): a time worked out again at a price moved since
     * would ask for more than was approved.
     */
    data class NeedsApproval(val approval: TokenApprovalQuote, val total: UInt256, val perChunk: UInt256) :
        BatchQuoting

    /** An approval of the PostageStamp from here ([hash]) is on its way, not mined yet: the call waits for it. */
    data class ApprovalPending(val hash: String) : BatchQuoting

    /**
     * A purchase or a top-up from here ([hash]) is on its way, not mined yet: it pulls xBZZ on the
     * allowance this one would count on, so this one waits for it rather than revert after it.
     */
    data class EarlierPullPending(val hash: String) : BatchQuoting

    /** Not quoted: [refusal]. */
    data class Refused(val refusal: BatchRefusal) : BatchQuoting
}

/** Why a batch's transaction is not quoted. */
sealed interface BatchRefusal {
    /** No Swarm contracts are pinned on the network. */
    data object NotPinned : BatchRefusal

    /** The PostageStamp is paused: every purchase, top-up and dilution reverts while it is. */
    data object Paused : BatchRefusal

    /** xBZZ's proxy or implementation is not the pinned one any more: nothing is approved. */
    data object BzzChanged : BatchRefusal

    /**
     * The depth is not one offered: from [SwarmCalls.MIN_DEPTH] to [SwarmCalls.MAX_DEPTH], deeper
     * than the batch for a dilution.
     */
    data object DepthNotOffered : BatchRefusal

    /**
     * The balance per chunk the batch would keep falls below the contract's minimum, [minimumPerChunk]
     * (a day's), which lasts [minimumSeconds] at the current price (null where no time can be told).
     */
    data class BelowMinimum(val minimumPerChunk: UInt256, val minimumSeconds: Long? = null) : BatchRefusal

    /** A time was asked for, and the PostageStamp's price is zero: no amount can be worked out from it. */
    data object NoPrice : BatchRefusal

    /** The batch is not the node's: [finding] says what it is. */
    data class NotTheNodes(val finding: BatchFinding) : BatchRefusal

    /** The batch has expired: a top-up or a dilution of it reverts. A new batch is the way on. */
    data object Expired : BatchRefusal

    /** The total does not fit 256 bits. */
    data object TooMuch : BatchRefusal

    /** A top-up of nothing per chunk: it would add nothing and cost a fee. */
    data object NothingToAdd : BatchRefusal

    /**
     * The payer holds less xBZZ than the total ([total]) beside what its own transactions not mined
     * yet still move of it and what its live CoW orders selling xBZZ may still take: nothing is
     * approved for a purchase or a top-up it cannot pay.
     */
    data class InsufficientBzz(val total: UInt256) : BatchRefusal

    /** Something it rests on could not be verified right now: [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : BatchRefusal

    /** The approval could not be quoted: [quoting] says why. */
    data class ApprovalNotQuoted(val quoting: ApprovalQuoting) : BatchRefusal

    /** The sender's transaction was not quoted: [refusal]. */
    data class NotQuoted(val refusal: CallRefusal) : BatchRefusal
}

/** Whether this transaction pulls xBZZ through the PostageStamp at [stamp]: a purchase or a top-up there. */
private fun SentTransfer.pullsXbzz(stamp: Address): Boolean = when (val action = action) {
    is SentAction.BatchPurchase -> action.stamp == stamp
    is SentAction.BatchTopUp -> action.stamp == stamp
    else -> false
}

/** What is quoted for a batch: the pins, the PostageStamp's state and, where one is, the batch. */
internal class BatchQuoter(
    private val contracts: SwarmContracts,
    private val inputs: TransactionInputs,
    private val nativeBalances: NativeBalances,
    private val tokenBalances: TokenBalances,
    private val sentTransfers: SentTransfers,
    private val outbox: Outbox,
    private val cowOrders: CowOrders,
    private val cowChain: CowChain,
    private val lightClient: LightClient,
    private val clock: () -> Long,
) {
    val network = SWARM_NETWORK
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock, outbox)

    /** xBZZ's approval of the PostageStamp, quoted on the network the batches are on, and no other. */
    private val approvals = QuoteTokenApproval(inputs, nativeBalances, sentTransfers, network, outbox, clock)

    /**
     * The pins and the PostageStamp's state, read fresh, once: what a quote rests on, or why there is
     * none; with [node] and [id], the batch too, at the same block, the node's own and not expired.
     */
    suspend fun prepare(node: Address? = null, id: BatchId? = null): Prepared {
        val pinned = contracts.deployment(network) ?: return Prepared.Refused(BatchRefusal.NotPinned)
        val reading = when (val read = contracts.postage(network, node, id)) {
            is PostageReading.Read -> read
            PostageReading.NotPinned -> return Prepared.Refused(BatchRefusal.NotPinned)
            PostageReading.BzzChanged -> return Prepared.Refused(BatchRefusal.BzzChanged)
            is PostageReading.Unavailable -> return Prepared.Refused(BatchRefusal.Unavailable(read.reason))
        }
        if (reading.state.paused) return Prepared.Refused(BatchRefusal.Paused)
        if (id == null) return Prepared.Ready(pinned, reading, batch = null)
        val batch = when (val finding = reading.batch) {
            is BatchFinding.Node -> finding.batch
            null -> return Prepared.Refused(BatchRefusal.Unavailable(ReadUnavailable.UNPROVEN))
            else -> return Prepared.Refused(BatchRefusal.NotTheNodes(finding))
        }
        if (batch.expired) return Prepared.Refused(BatchRefusal.Expired)
        return Prepared.Ready(pinned, reading, batch)
    }

    /** What [prepare] gives. */
    sealed interface Prepared {
        data class Ready(
            val pinned: SwarmDeployment,
            val postage: PostageReading.Read,
            val batch: PostageBatch?,
        ) : Prepared
        data class Refused(val refusal: BatchRefusal) : Prepared
    }

    /**
     * [call] from [from], pulling [total] xBZZ: the PostageStamp approved first when its allowance
     * falls short, then the call quoted (run as a verified call, it must not revert).
     */
    suspend fun quote(
        from: Address,
        call: BatchCall,
        data: ByteArray,
        total: UInt256,
        pinned: SwarmDeployment,
        postage: PostageReading.Read,
        level: FeeLevel,
        batch: PostageBatch? = null,
    ): BatchQuoting {
        if (!total.isZero) {
            // An earlier pull of xBZZ from here, not mined yet, would take the allowance this one counts on.
            val unmined = when (val read = unminedOf(network, sentTransfers, inputs, from, clock)) {
                is Unmined.Read -> read.transfers
                is Unmined.Unavailable -> return BatchQuoting.Refused(BatchRefusal.Unavailable(read.reason))
            }
            unmined.lastOrNull { it.pullsXbzz(pinned.postageStamp) }
                ?.let { return BatchQuoting.EarlierPullPending(it.hash) }
            // What the payer holds, beside what its own transactions not mined yet still move of it:
            // nothing is approved for what it cannot pay.
            val held = when (val read = tokenBalances.observeBalance(network, from, pinned.bzz.address).first()) {
                is TokenBalanceReading.Verified -> UInt256.parse(read.units.decimal)
                    ?: return BatchQuoting.Refused(BatchRefusal.Unavailable(ReadUnavailable.UNPROVEN))
                TokenBalanceReading.Syncing, is TokenBalanceReading.LastKnown ->
                    return BatchQuoting.Refused(BatchRefusal.Unavailable(ReadUnavailable.SYNCING))
                TokenBalanceReading.NotAToken -> return BatchQuoting.Refused(BatchRefusal.BzzChanged)
                is TokenBalanceReading.Unavailable -> return BatchQuoting.Refused(BatchRefusal.Unavailable(read.reason))
            }
            val moving = unmined.mapNotNull { it.tokenAmount(pinned.bzz.address) }.sumOrNull()
                ?: return BatchQuoting.Refused(BatchRefusal.InsufficientBzz(total))
            // What the payer's live CoW orders selling xBZZ may still take is not this call's either,
            // as Send holds it back (docs/swaps.md): the order could not fill, or the call would revert.
            val orders = (cowOrders.observe().first() as? CowOrderList.Loaded)?.orders.orEmpty()
            val finalized = (lightClient.observeStatus(network).first() as? LightClientStatus.Synced)?.finalized
            val ordered = orders.holding(network, from, pinned.bzz.address, finalized, cowChain)
                ?: return BatchQuoting.Refused(BatchRefusal.InsufficientBzz(total))
            val left = held.minusOrNull(moving)?.minusOrNull(ordered) ?: UInt256.ZERO
            if (left < total) {
                return BatchQuoting.Refused(BatchRefusal.InsufficientBzz(total))
            }
            when (val approval = approvals(from, pinned.bzz, pinned.postageStamp, total, level)) {
                is ApprovalQuoting.AlreadyAllowed -> Unit
                is ApprovalQuoting.Quoted -> {
                    val perChunk = when (call) {
                        is BatchCall.Purchase -> call.perChunk
                        is BatchCall.TopUp -> call.perChunk
                        is BatchCall.Dilution -> error("a dilution pulls no xBZZ")
                    }
                    return BatchQuoting.NeedsApproval(approval.quote, total, perChunk)
                }
                is ApprovalQuoting.ApprovalPending -> return BatchQuoting.ApprovalPending(approval.hash)
                else -> return BatchQuoting.Refused(BatchRefusal.ApprovalNotQuoted(approval))
            }
        }
        val native = when (val balance = NativeRead.of(nativeBalances.observeBalance(network, from).first())) {
            is NativeRead.Before -> balance
            is NativeRead.NotRead -> return BatchQuoting.Refused(BatchRefusal.NotQuoted(balance.refusal))
        }
        return when (val quoted = quoter.quote(from, pinned.postageStamp, UInt256.ZERO, data, level, native)) {
            is CallQuoting.Quoted -> BatchQuoting.Quoted(
                BatchQuote(network, call, quoted.quote, postage.state, postage.blockNumber, total, batch),
            )
            is CallQuoting.Refused -> BatchQuoting.Refused(BatchRefusal.NotQuoted(quoted.refusal))
        }
    }
}

/**
 * Quotes a postage batch for the node at `owner`, bought by `buyer`, an EOA whose key is held here
 * on Gnosis Chain (docs/swarm.md, *Buy a batch*): of `depth` (from [SwarmCalls.MIN_DEPTH] to
 * [SwarmCalls.MAX_DEPTH]), `perChunk` PLUR for each chunk, never below the PostageStamp's minimum,
 * `immutable` or not, with a nonce of 32 random bytes made here. The PostageStamp must not be paused.
 * [nonce] is the source of nonces; random by default. [clock] is in unix seconds.
 */
class QuoteBatchPurchase internal constructor(
    private val quoter: BatchQuoter,
    private val nonce: () -> ByteArray,
) {
    constructor(
        contracts: SwarmContracts,
        inputs: TransactionInputs,
        nativeBalances: NativeBalances,
        tokenBalances: TokenBalances,
        sentTransfers: SentTransfers,
        outbox: Outbox,
        cowOrders: CowOrders,
        cowChain: CowChain,
        lightClient: LightClient,
        clock: () -> Long = ::epochSecondsNow,
    ) : this(
        BatchQuoter(
            contracts,
            inputs,
            nativeBalances,
            tokenBalances,
            sentTransfers,
            outbox,
            cowOrders,
            cowChain,
            lightClient,
            clock,
        ),
        { Random.nextBytes(32) },
    )

    suspend operator fun invoke(
        buyer: Address,
        owner: Address,
        depth: Int,
        perChunk: UInt256,
        immutable: Boolean,
        level: FeeLevel,
    ): BatchQuoting = invoke(buyer, owner, depth, ChunkFunding.PerChunk(perChunk), immutable, level)

    /** As above, with the amount per chunk given as [funding]: an amount, or a time at the current price. */
    suspend operator fun invoke(
        buyer: Address,
        owner: Address,
        depth: Int,
        funding: ChunkFunding,
        immutable: Boolean,
        level: FeeLevel,
    ): BatchQuoting {
        if (depth !in SwarmCalls.MIN_DEPTH..SwarmCalls.MAX_DEPTH) {
            return BatchQuoting.Refused(BatchRefusal.DepthNotOffered)
        }
        val prepared = when (val read = quoter.prepare()) {
            is BatchQuoter.Prepared.Ready -> read
            is BatchQuoter.Prepared.Refused -> return BatchQuoting.Refused(read.refusal)
        }
        val (pinned, postage) = prepared.pinned to prepared.postage
        val price = postage.state.lastPrice
        if (funding is ChunkFunding.Lasting && price.isZero) return BatchQuoting.Refused(BatchRefusal.NoPrice)
        val perChunk = perChunkOf(funding, price) ?: return BatchQuoting.Refused(BatchRefusal.TooMuch)
        if (perChunk < postage.state.minimumPerChunk) return belowMinimum(postage.state.minimumPerChunk, price)
        val total = SwarmCalls.total(perChunk, depth) ?: return BatchQuoting.Refused(BatchRefusal.TooMuch)
        val call =
            BatchCall.Purchase(pinned.bzz, pinned.postageStamp, buyer, owner, depth, perChunk, immutable, nonce())
        val data = SwarmCalls.createBatch(owner, perChunk, depth, call.nonce, immutable)
        return quoter.quote(buyer, call, data, total, pinned, postage, level)
    }
}

/**
 * Quotes a top-up of the node at `node`'s batch `id`, paid by `payer`, an EOA whose key is held
 * here on Gnosis Chain (docs/swarm.md, *Top up a batch*): `perChunk` PLUR more for each of its
 * chunks. The batch must be the node's and not expired, read fresh; its balance per chunk after
 * the top-up must reach the PostageStamp's minimum; the PostageStamp must not be paused.
 */
class QuoteBatchTopUp internal constructor(private val quoter: BatchQuoter) {
    constructor(
        contracts: SwarmContracts,
        inputs: TransactionInputs,
        nativeBalances: NativeBalances,
        tokenBalances: TokenBalances,
        sentTransfers: SentTransfers,
        outbox: Outbox,
        cowOrders: CowOrders,
        cowChain: CowChain,
        lightClient: LightClient,
        clock: () -> Long = ::epochSecondsNow,
    ) : this(
        BatchQuoter(
            contracts,
            inputs,
            nativeBalances,
            tokenBalances,
            sentTransfers,
            outbox,
            cowOrders,
            cowChain,
            lightClient,
            clock,
        ),
    )

    suspend operator fun invoke(
        payer: Address,
        node: Address,
        id: BatchId,
        perChunk: UInt256,
        level: FeeLevel,
    ): BatchQuoting = invoke(payer, node, id, ChunkFunding.PerChunk(perChunk), level)

    /** As above, with what is added per chunk given as [funding]: an amount, or a time at the current price. */
    suspend operator fun invoke(
        payer: Address,
        node: Address,
        id: BatchId,
        funding: ChunkFunding,
        level: FeeLevel,
    ): BatchQuoting {
        if ((funding as? ChunkFunding.PerChunk)?.plur?.isZero == true) {
            return BatchQuoting.Refused(BatchRefusal.NothingToAdd)
        }
        val prepared = when (val read = quoter.prepare(node, id)) {
            is BatchQuoter.Prepared.Ready -> read
            is BatchQuoter.Prepared.Refused -> return BatchQuoting.Refused(read.refusal)
        }
        val (pinned, postage) = prepared.pinned to prepared.postage
        val price = postage.state.lastPrice
        if (funding is ChunkFunding.Lasting && price.isZero) return BatchQuoting.Refused(BatchRefusal.NoPrice)
        val perChunk = perChunkOf(funding, price) ?: return BatchQuoting.Refused(BatchRefusal.TooMuch)
        val batch = checkNotNull(prepared.batch)
        val after = batch.remainingPerChunk.plusOrNull(perChunk) ?: return BatchQuoting.Refused(BatchRefusal.TooMuch)
        if (after < postage.state.minimumPerChunk) return belowMinimum(postage.state.minimumPerChunk, price)
        val total = SwarmCalls.total(perChunk, batch.depth) ?: return BatchQuoting.Refused(BatchRefusal.TooMuch)
        val call = BatchCall.TopUp(pinned.bzz, pinned.postageStamp, node, id, batch.depth, perChunk)
        return quoter.quote(payer, call, SwarmCalls.topUp(id, perChunk), total, pinned, postage, level, batch)
    }
}

/**
 * Quotes the dilution of the node at `node`'s batch `id` to `depth`, the node's own transaction
 * (docs/swarm.md, *Dilute a batch*): only the batch's owner may send it, so the node's key signs,
 * held here on Gnosis Chain. The batch must be the node's and not expired, read fresh; `depth`
 * deeper than its own and at most [PostageBatch.MAX_DEPTH]; what is left per chunk once halved for
 * each step must reach the PostageStamp's minimum. It moves no xBZZ.
 */
class QuoteBatchDilution internal constructor(private val quoter: BatchQuoter) {
    constructor(
        contracts: SwarmContracts,
        inputs: TransactionInputs,
        nativeBalances: NativeBalances,
        tokenBalances: TokenBalances,
        sentTransfers: SentTransfers,
        outbox: Outbox,
        cowOrders: CowOrders,
        cowChain: CowChain,
        lightClient: LightClient,
        clock: () -> Long = ::epochSecondsNow,
    ) : this(
        BatchQuoter(
            contracts,
            inputs,
            nativeBalances,
            tokenBalances,
            sentTransfers,
            outbox,
            cowOrders,
            cowChain,
            lightClient,
            clock,
        ),
    )

    suspend operator fun invoke(node: Address, id: BatchId, depth: Int, level: FeeLevel): BatchQuoting {
        val prepared = when (val read = quoter.prepare(node, id)) {
            is BatchQuoter.Prepared.Ready -> read
            is BatchQuoter.Prepared.Refused -> return BatchQuoting.Refused(read.refusal)
        }
        val (pinned, postage) = prepared.pinned to prepared.postage
        val batch = checkNotNull(prepared.batch)
        if (depth <= batch.depth || depth > PostageBatch.MAX_DEPTH) {
            return BatchQuoting.Refused(BatchRefusal.DepthNotOffered)
        }
        val left = SwarmCalls.remainingAfterDilution(batch.remainingPerChunk, batch.depth, depth)
        if (left < postage.state.minimumPerChunk) {
            return belowMinimum(postage.state.minimumPerChunk, postage.state.lastPrice)
        }
        val call = BatchCall.Dilution(pinned.postageStamp, id, batch.depth, depth)
        val data = SwarmCalls.increaseDepth(id, depth)
        return quoter.quote(node, call, data, UInt256.ZERO, pinned, postage, level, batch)
    }
}

/** What [SendBatchCall] did. */
sealed interface BatchSending {
    /** Signed and handed on as [sending] says. */
    data class Sending(val sending: TransferSending) : BatchSending

    /**
     * Not signed: a transaction not sent from here took the nonce the quote is for since it was
     * made (a Bee node signing with the same key, docs/swarm.md, *A key that Bee also uses*). The
     * review is made again.
     */
    data object NonceTaken : BatchSending

    /** The account's count of mined transactions could not be verified right now ([reason]): nothing is signed. */
    data class NotChecked(val reason: ReadUnavailable) : BatchSending

    /**
     * Not signed: the batch's depth is not the one the quote rests on any more (the node's Bee diluted
     * it meanwhile), so a top-up would pull more than was approved, or a dilution would revert; or
     * the PostageStamp was paused, or xBZZ upgraded, since. The review is made again.
     */
    data object BatchChanged : BatchSending
}

/**
 * Signs a reviewed [BatchQuote]'s transaction with the sender's key as [unlock] opens it, and hands
 * it to the network, recorded as the batch call it is. Before it signs, the sender's verified count
 * of mined transactions is read again: one past the quote's nonce means a transaction not sent from
 * here took it, and nothing is signed (docs/swarm.md, *A key that Bee also uses*). A top-up or a
 * dilution is signed only while the batch's depth is still the one quoted; every call only while
 * the PostageStamp is not paused and xBZZ is the pinned one, read fresh. A purchase sent keeps
 * its batch's id for the node ([SwarmCandidates.add], the user's own: bought by the user's tap), so
 * that it is found once mined. [clock] is in unix seconds.
 */
class SendBatchCall(
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val inputs: TransactionInputs,
    private val candidates: SwarmCandidates,
    private val contracts: SwarmContracts,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(quote: BatchQuote, unlock: Unlock): BatchSending {
        val from = quote.quote.from
        val changed = quote.batch?.let { before -> stillAsQuoted(quote, before) } ?: stillPinned(quote)
        changed?.let { return it }
        when (val mined = inputs.minedNonce(quote.network, from)) {
            is NonceReading.Read -> if (mined.nonce > quote.quote.transaction.nonce) return BatchSending.NonceTaken
            is NonceReading.Unavailable -> return BatchSending.NotChecked(mined.reason)
        }
        val action = quote.call.action
        val sending = signAndSend(signer, broadcaster, sentTransfers, quote.network, quote.quote, action, clock, unlock)
        val call = quote.call
        if (call is BatchCall.Purchase && sending.reachedTheNetwork) {
            // Kept however the caller goes on: the batch was sent, and is found by its id once mined.
            withContext(NonCancellable) {
                candidates.add(AccountId(quote.network, call.owner), SwarmCandidate.Batch(call.id), byHand = true)
            }
        }
        return BatchSending.Sending(sending)
    }

    /**
     * Null while the batch is as [before] was in its depth, read fresh; otherwise why nothing is
     * signed. A top-up pulls its amount times `2^depth` at the depth it is mined at.
     */
    private suspend fun stillAsQuoted(quote: BatchQuote, before: PostageBatch): BatchSending? =
        // Read fresh, never the reading the quote was made on.
        when (val reading = contracts.postage(quote.network, before.owner, before.id)) {
            is PostageReading.Read -> {
                val depth = (reading.batch as? BatchFinding.Node)?.batch?.depth
                BatchSending.BatchChanged.takeUnless { depth == before.depth && !reading.state.paused }
            }
            PostageReading.BzzChanged -> BatchSending.BatchChanged
            PostageReading.NotPinned -> BatchSending.NotChecked(ReadUnavailable.NO_ENGINE)
            is PostageReading.Unavailable -> BatchSending.NotChecked(reading.reason)
        }

    /**
     * Null while xBZZ is the pinned one and the PostageStamp is not paused, read fresh; otherwise why
     * nothing is signed.
     */
    private suspend fun stillPinned(quote: BatchQuote): BatchSending? =
        when (val reading = contracts.postage(quote.network)) {
            is PostageReading.Read -> BatchSending.BatchChanged.takeIf { reading.state.paused }
            PostageReading.BzzChanged -> BatchSending.BatchChanged
            PostageReading.NotPinned -> BatchSending.NotChecked(ReadUnavailable.NO_ENGINE)
            is PostageReading.Unavailable -> BatchSending.NotChecked(reading.reason)
        }

    /** Whether a peer took it, or may have: a purchase on its way is kept, so that its batch is found. */
    private val TransferSending.reachedTheNetwork: Boolean
        get() = this is TransferSending.Sent ||
            (this is TransferSending.NotSent && broadcast is Broadcast.Unavailable)
}

/**
 * Signs the reviewed approval a purchase or a top-up needs first ([BatchQuoting.NeedsApproval]) and
 * hands it to the network, as [SendTokenApproval] does, after the same checks as [SendBatchCall]:
 * xBZZ proven unchanged and the PostageStamp not paused, read fresh ([BatchSending.BatchChanged]
 * otherwise); and the payer's verified count of mined transactions read again, and one past the quote's nonce
 * means a transaction not sent from here took it (a Bee node signing with the same key,
 * docs/swarm.md, *A key that Bee also uses*), so nothing is signed. Only approvals of xBZZ to the
 * pinned PostageStamp on Gnosis Chain are signed here. [clock] is in unix seconds.
 */
class SendBatchApproval(
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val inputs: TransactionInputs,
    private val contracts: SwarmContracts,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(quote: TokenApprovalQuote, unlock: Unlock): BatchSending {
        val pinned = contracts.deployment(SWARM_NETWORK)
        require(
            quote.network == SWARM_NETWORK && pinned != null && quote.token == pinned.bzz &&
                quote.spender == pinned.postageStamp,
        ) { "only xBZZ's approval of the pinned PostageStamp is a batch's" }
        // Read fresh: xBZZ's code and implementation proven the pinned ones, the PostageStamp not paused.
        when (val reading = contracts.postage(quote.network)) {
            is PostageReading.Read -> if (reading.state.paused) return BatchSending.BatchChanged
            PostageReading.BzzChanged -> return BatchSending.BatchChanged
            PostageReading.NotPinned -> return BatchSending.NotChecked(ReadUnavailable.NO_ENGINE)
            is PostageReading.Unavailable -> return BatchSending.NotChecked(reading.reason)
        }
        when (val mined = inputs.minedNonce(quote.network, quote.from)) {
            is NonceReading.Read -> if (mined.nonce > quote.transaction.nonce) return BatchSending.NonceTaken
            is NonceReading.Unavailable -> return BatchSending.NotChecked(mined.reason)
        }
        val action = SentAction.TokenApproval(quote.token, quote.spender, quote.amount)
        return BatchSending.Sending(
            signAndSend(signer, broadcaster, sentTransfers, quote.network, quote.call, action, clock, unlock),
        )
    }
}

/**
 * The node at `node`'s batch `id` on Gnosis Chain, as [SwarmContracts.observeBatch] proves it: what a
 * top-up's or a dilution's form is filled in on. A quote reads it fresh again, never this reading.
 */
class ObservePostageBatch(private val contracts: SwarmContracts) {
    operator fun invoke(node: Address, id: BatchId): Flow<SwarmReading<BatchFinding>> =
        contracts.observeBatch(SWARM_NETWORK, node, id)
}
