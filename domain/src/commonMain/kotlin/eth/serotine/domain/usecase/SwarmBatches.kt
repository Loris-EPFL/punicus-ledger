package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.PostageBatch
import eth.serotine.domain.model.PostageState
import eth.serotine.domain.model.SwarmCalls
import eth.serotine.domain.model.SwarmDeployment
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BatchFinding
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.PostageReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.SwarmCandidate
import eth.serotine.domain.repository.SwarmCandidates
import eth.serotine.domain.repository.SwarmContracts
import eth.serotine.domain.repository.SwarmReading
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlin.random.Random
import kotlinx.coroutines.flow.first

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
            get() = SentAction.BatchPurchase(stamp, owner, id, depth, perChunk, immutable)

        // A nonce is compared by its bytes: two purchases of one nonce are one batch.
        override fun equals(other: Any?): Boolean =
            other is Purchase && stamp == other.stamp && buyer == other.buyer && owner == other.owner &&
                depth == other.depth && perChunk == other.perChunk && immutable == other.immutable &&
                nonce.contentEquals(other.nonce)

        override fun hashCode(): Int = nonce.contentHashCode()
    }

    /** [perChunk] more for each chunk of the batch [id], of [depth]. */
    data class TopUp(override val stamp: Address, val id: BatchId, val depth: Int, val perChunk: UInt256) : BatchCall {
        override val action: SentAction
            get() = SentAction.BatchTopUp(stamp, id, depth, perChunk)
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
 * (PLUR; zero for a dilution), and, for a dilution or a top-up, the [batch] as read before it.
 * Only this file makes one: what is signed is its [quote]'s transaction.
 */
@ConsistentCopyVisibility
data class BatchQuote internal constructor(
    val network: eth.serotine.domain.model.Network,
    val call: BatchCall,
    val quote: CallQuote,
    val postage: PostageState,
    val postageBlock: Long,
    val total: UInt256,
    val batch: PostageBatch? = null,
)

/** What a batch quote gives. */
sealed interface BatchQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: BatchQuote) : BatchQuoting

    /**
     * The PostageStamp's allowance over the payer's xBZZ falls short of the total: [approval], for
     * exactly the total, is signed first, and the call is quoted again once it is mined.
     */
    data class NeedsApproval(val approval: TokenApprovalQuote, val total: UInt256) : BatchQuoting

    /** An approval of the PostageStamp from here ([hash]) is on its way, not mined yet: the call waits for it. */
    data class ApprovalPending(val hash: String) : BatchQuoting

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

    /** The balance per chunk the batch would keep falls below the contract's minimum, [minimumPerChunk] (a day's). */
    data class BelowMinimum(val minimumPerChunk: UInt256) : BatchRefusal

    /** The batch is not the node's: [finding] says what it is. */
    data class NotTheNodes(val finding: BatchFinding) : BatchRefusal

    /** The batch has expired: a top-up or a dilution of it reverts. A new batch is the way on. */
    data object Expired : BatchRefusal

    /** The total does not fit 256 bits. */
    data object TooMuch : BatchRefusal

    /** Something it rests on could not be verified right now: [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : BatchRefusal

    /** The approval could not be quoted: [quoting] says why. */
    data class ApprovalNotQuoted(val quoting: ApprovalQuoting) : BatchRefusal

    /** The sender's transaction was not quoted: [refusal]. */
    data class NotQuoted(val refusal: CallRefusal) : BatchRefusal
}

/** What is quoted for a batch: the pins, the PostageStamp's state and, where one is, the batch. */
internal class BatchQuoter(
    private val contracts: SwarmContracts,
    private val inputs: TransactionInputs,
    private val nativeBalances: NativeBalances,
    private val sentTransfers: SentTransfers,
    private val outbox: Outbox,
    private val approvals: QuoteTokenApproval,
    private val clock: () -> Long,
) {
    val network = SWARM_NETWORK
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock, outbox)

    /** The pins and the PostageStamp's state, read fresh, once: what a quote rests on, or why there is none. */
    suspend fun prepare(): Prepared {
        val pinned = contracts.deployment(network) ?: return Prepared.Refused(BatchRefusal.NotPinned)
        return when (val reading = contracts.postage(network)) {
            is PostageReading.Read ->
                if (reading.state.paused) Prepared.Refused(BatchRefusal.Paused) else Prepared.Ready(pinned, reading)
            PostageReading.NotPinned -> Prepared.Refused(BatchRefusal.NotPinned)
            PostageReading.BzzChanged -> Prepared.Refused(BatchRefusal.BzzChanged)
            is PostageReading.Unavailable -> Prepared.Refused(BatchRefusal.Unavailable(reading.reason))
        }
    }

    /** What [prepare] gives. */
    sealed interface Prepared {
        data class Ready(val pinned: SwarmDeployment, val postage: PostageReading.Read) : Prepared
        data class Refused(val refusal: BatchRefusal) : Prepared
    }

    /** The batch [id] of [node], read fresh: the node's own and not expired, else why not. */
    suspend fun batch(node: Address, id: BatchId): Pair<PostageBatch?, BatchRefusal?> =
        when (val reading = contracts.observeBatch(network, node, id).first()) {
            is SwarmReading.Verified -> when (val finding = reading.finding) {
                is BatchFinding.Node ->
                    if (finding.batch.expired) null to BatchRefusal.Expired else finding.batch to null
                else -> null to BatchRefusal.NotTheNodes(finding)
            }
            SwarmReading.NotPinned -> null to BatchRefusal.NotPinned
            SwarmReading.Syncing -> null to BatchRefusal.Unavailable(ReadUnavailable.SYNCING)
            is SwarmReading.Unavailable -> null to BatchRefusal.Unavailable(reading.reason)
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
            when (val approval = approvals(from, pinned.bzz, pinned.postageStamp, total, level)) {
                is ApprovalQuoting.AlreadyAllowed -> Unit
                is ApprovalQuoting.Quoted -> return BatchQuoting.NeedsApproval(approval.quote, total)
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
        sentTransfers: SentTransfers,
        outbox: Outbox,
        approvals: QuoteTokenApproval,
        clock: () -> Long = ::epochSecondsNow,
    ) : this(
        BatchQuoter(contracts, inputs, nativeBalances, sentTransfers, outbox, approvals, clock),
        { Random.nextBytes(32) },
    )

    suspend operator fun invoke(
        buyer: Address,
        owner: Address,
        depth: Int,
        perChunk: UInt256,
        immutable: Boolean,
        level: FeeLevel,
    ): BatchQuoting {
        if (depth !in SwarmCalls.MIN_DEPTH..SwarmCalls.MAX_DEPTH) {
            return BatchQuoting.Refused(BatchRefusal.DepthNotOffered)
        }
        val (pinned, postage) = when (val prepared = quoter.prepare()) {
            is BatchQuoter.Prepared.Ready -> prepared.pinned to prepared.postage
            is BatchQuoter.Prepared.Refused -> return BatchQuoting.Refused(prepared.refusal)
        }
        if (perChunk < postage.state.minimumPerChunk) {
            return BatchQuoting.Refused(BatchRefusal.BelowMinimum(postage.state.minimumPerChunk))
        }
        val total = SwarmCalls.total(perChunk, depth) ?: return BatchQuoting.Refused(BatchRefusal.TooMuch)
        val call = BatchCall.Purchase(pinned.postageStamp, buyer, owner, depth, perChunk, immutable, nonce())
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
        sentTransfers: SentTransfers,
        outbox: Outbox,
        approvals: QuoteTokenApproval,
        clock: () -> Long = ::epochSecondsNow,
    ) : this(BatchQuoter(contracts, inputs, nativeBalances, sentTransfers, outbox, approvals, clock))

    suspend operator fun invoke(
        payer: Address,
        node: Address,
        id: BatchId,
        perChunk: UInt256,
        level: FeeLevel,
    ): BatchQuoting {
        val (pinned, postage) = when (val prepared = quoter.prepare()) {
            is BatchQuoter.Prepared.Ready -> prepared.pinned to prepared.postage
            is BatchQuoter.Prepared.Refused -> return BatchQuoting.Refused(prepared.refusal)
        }
        val (batch, refusal) = quoter.batch(node, id)
        if (refusal != null || batch == null) return BatchQuoting.Refused(refusal ?: BatchRefusal.Expired)
        val after = batch.remainingPerChunk.plusOrNull(perChunk) ?: return BatchQuoting.Refused(BatchRefusal.TooMuch)
        if (after < postage.state.minimumPerChunk) {
            return BatchQuoting.Refused(BatchRefusal.BelowMinimum(postage.state.minimumPerChunk))
        }
        val total = SwarmCalls.total(perChunk, batch.depth) ?: return BatchQuoting.Refused(BatchRefusal.TooMuch)
        val call = BatchCall.TopUp(pinned.postageStamp, id, batch.depth, perChunk)
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
        sentTransfers: SentTransfers,
        outbox: Outbox,
        approvals: QuoteTokenApproval,
        clock: () -> Long = ::epochSecondsNow,
    ) : this(BatchQuoter(contracts, inputs, nativeBalances, sentTransfers, outbox, approvals, clock))

    suspend operator fun invoke(node: Address, id: BatchId, depth: Int, level: FeeLevel): BatchQuoting {
        val (pinned, postage) = when (val prepared = quoter.prepare()) {
            is BatchQuoter.Prepared.Ready -> prepared.pinned to prepared.postage
            is BatchQuoter.Prepared.Refused -> return BatchQuoting.Refused(prepared.refusal)
        }
        val (batch, refusal) = quoter.batch(node, id)
        if (refusal != null || batch == null) return BatchQuoting.Refused(refusal ?: BatchRefusal.Expired)
        if (depth <= batch.depth || depth > PostageBatch.MAX_DEPTH) {
            return BatchQuoting.Refused(BatchRefusal.DepthNotOffered)
        }
        val left = SwarmCalls.remainingAfterDilution(batch.remainingPerChunk, batch.depth, depth)
        if (left < postage.state.minimumPerChunk) {
            return BatchQuoting.Refused(BatchRefusal.BelowMinimum(postage.state.minimumPerChunk))
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
}

/**
 * Signs a reviewed [BatchQuote]'s transaction with the sender's key as [unlock] opens it, and hands
 * it to the network, recorded as the batch call it is. Before it signs, the sender's verified count
 * of mined transactions is read again: one past the quote's nonce means a transaction not sent from
 * here took it, and nothing is signed (docs/swarm.md, *A key that Bee also uses*). A purchase sent
 * keeps its batch's id for the node ([SwarmCandidates.add], the user's own), so that it is found
 * once mined. [clock] is in unix seconds.
 */
class SendBatchCall(
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val inputs: TransactionInputs,
    private val candidates: SwarmCandidates,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(quote: BatchQuote, unlock: Unlock): BatchSending {
        val from = quote.quote.from
        when (val mined = inputs.minedNonce(quote.network, from)) {
            is NonceReading.Read -> if (mined.nonce > quote.quote.transaction.nonce) return BatchSending.NonceTaken
            is NonceReading.Unavailable -> return BatchSending.NotChecked(mined.reason)
        }
        val action = quote.call.action
        val sending = signAndSend(signer, broadcaster, sentTransfers, quote.network, quote.quote, action, clock, unlock)
        val call = quote.call
        if (call is BatchCall.Purchase && sending.reachedTheNetwork) {
            candidates.add(AccountId(quote.network, call.owner), SwarmCandidate.Batch(call.id), byHand = true)
        }
        return BatchSending.Sending(sending)
    }

    /** Whether a peer took it, or may have: a purchase on its way is kept, so that its batch is found. */
    private val TransferSending.reachedTheNetwork: Boolean
        get() = this is TransferSending.Sent ||
            (this is TransferSending.NotSent && broadcast is eth.serotine.domain.repository.Broadcast.Unavailable)
}
