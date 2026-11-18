package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DelayCall
import eth.serotine.domain.model.DelayMemberChange
import eth.serotine.domain.model.DelayMemberChangePlan
import eth.serotine.domain.model.DelayTransfer
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.sameCall
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.DelayGeneration
import eth.serotine.domain.repository.DelayModules
import eth.serotine.domain.repository.DelayQueue
import eth.serotine.domain.repository.DelayQueueReading
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TransactionHistory
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first

// A Gnosis Pay account's transfer, through its Delay (docs/gnosis-pay.md, *Sending*; owner's
// decisions, 2026-11-15): a key held here that controls the Delay queues it, as a member or through
// member Safes that each need one signature (owner's request, 2026-11-17), and after the cooldown
// sends its execution. Both are that key's own transactions, quoted and sent as every transaction from
// an account this wallet holds ([CallQuoter], [signAndSend]). Strict in either sync mode: nothing
// here is drafted on state last verified, as swaps and dApp requests are not (CLAUDE.md, *Trust*).

/**
 * How long before its expiration a queued transfer is no longer offered for execution: its
 * transaction must be mined while the window is still open, or it reverts and its fee is lost.
 */
const val DELAY_EXPIRY_MARGIN_SECONDS: Long = 5 * 60

/**
 * Where a queued transfer stands in its Delay's queue at a verified head produced at `headTime`
 * (unix seconds): what the Delay's own `executeNextTx` checks, worked out from its getters so that
 * the screen can say when, and the execution is offered only once it can succeed.
 */
sealed interface DelayTransferState {

    /**
     * Its cooldown runs until [readyAt]; it may be executed until [expiresAt] (null: for ever) once
     * the [ahead] entries queued before it are executed or skipped.
     */
    data class Waiting(val readyAt: Long, val expiresAt: Long?, val ahead: Int) : DelayTransferState

    /** It is the next entry, its cooldown has passed, and it may be executed until [expiresAt] (null: for ever). */
    data class Ready(val expiresAt: Long?) : DelayTransferState

    /**
     * Its cooldown has passed, but [ahead] entries queued before it come first: executed by whoever
     * queued them, or skipped once expired (the Delay's `skipExpired`, [QuoteDelaySkip]). It may
     * be executed until [expiresAt] (null: for ever). Punicus never executes an entry it did not queue.
     */
    data class Behind(val ahead: Int, val expiresAt: Long?) : DelayTransferState

    /**
     * Its window closes at [expiresAt], less than [DELAY_EXPIRY_MARGIN_SECONDS] from now, or has
     * closed: an execution sent now could not be mined in time, so none is offered. Nothing moved.
     */
    data class Expired(val expiresAt: Long) : DelayTransferState

    /** The queue from its next entry on holds no entry of its hash: not queued yet, or executed or skipped since. */
    data object NotInQueue : DelayTransferState

    /** No entry of its hash among the first [searched] from the next one: it may be further back. */
    data class FurtherBack(val searched: Int) : DelayTransferState
}

/** Where the transfer this [DelayQueue] was searched for stands, at a head produced at [headTime] (unix seconds). */
fun DelayQueue.state(headTime: Long): DelayTransferState {
    val found = entry
        ?: return if (searchedAll) DelayTransferState.NotInQueue else DelayTransferState.FurtherBack(searched)
    val readyAt = found.createdAt + cooldownSeconds
    val expiresAt = if (expirationSeconds == 0L) null else readyAt + expirationSeconds
    val ahead = (found.nonce - txNonce).toInt()
    return when {
        // Still cooling down is waiting, whatever the window: never said to have expired before it opened.
        headTime < readyAt -> DelayTransferState.Waiting(readyAt, expiresAt, ahead)
        expiresAt != null && headTime + DELAY_EXPIRY_MARGIN_SECONDS > expiresAt -> DelayTransferState.Expired(expiresAt)
        ahead == 0 -> DelayTransferState.Ready(expiresAt)
        else -> DelayTransferState.Behind(ahead, expiresAt)
    }
}

/** What a member's call of a Gnosis Pay account's Delay does. */
enum class DelayStep {
    /** `execTransactionFromModule`: queues the transfer. */
    QUEUE,

    /** `executeNextTx`: executes it once its cooldown has passed. */
    EXECUTE,
}

/**
 * A Gnosis Pay account's [delayCall] (a transfer, or a change of its Delay's members) quoted for
 * review: the [step], its transaction quoted from the key that sends it ([call]), the Safes a
 * queueing goes through ([via], the Delay's member first; empty when the sender is a member, and
 * for an execution, which anyone may send), and, for a transfer, the Safe's balance of the asset it
 * was checked against ([safeBalance], at [safeBalanceBlock]; null for a change of members, which
 * moves nothing). Only this file makes one: what is signed is its [call]'s transaction.
 */
@ConsistentCopyVisibility
data class DelayQuote internal constructor(
    val network: Network,
    val delayCall: DelayCall,
    val step: DelayStep,
    val call: CallQuote,
    val safeBalance: UInt256?,
    val safeBalanceBlock: Long?,
    val via: List<Address> = emptyList(),
) {
    /** The [delayCall] when it is a transfer; null for a change of the Delay's members. */
    val transfer: DelayTransfer? get() = delayCall as? DelayTransfer

    /** What the sender's transaction is recorded as. */
    val action: SentAction
        get() = when (step) {
            DelayStep.QUEUE -> SentAction.DelayQueue(delayCall)
            DelayStep.EXECUTE -> SentAction.DelayExecution(delayCall)
        }
}

/** What [QuoteDelayQueue] and [QuoteDelayExecution] give. */
sealed interface DelayQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: DelayQuote) : DelayQuoting

    /**
     * The Delay is not the Safe's own of the current mastercopy, as proven now: [generation]
     * RETIRED for a proxy of the retired one, CURRENT for one not wired to the Safe, null for no
     * proxy of a pinned mastercopy. Nothing is sent through it.
     */
    data class NotTheSafesDelay(val generation: DelayGeneration?) : DelayQuoting

    /**
     * The Safe holds less of the asset than the amount, with what transfers queued from here and
     * not executed yet will move of it ([alreadyQueued]): the execution would fail, its fee paid.
     */
    data class SafeInsufficient(val alreadyQueued: UInt256 = UInt256.ZERO) : DelayQuoting

    /**
     * An entry of the same call waits in the Delay's queue still, or its queueing from here
     * ([queuedBy], null when the queue holds it and this device sent none) is not mined yet:
     * queuing it again would make two entries of one hash, which nothing tells apart.
     */
    data class AlreadyQueued(val queuedBy: String?) : DelayQuoting

    /**
     * An entry of the same call is still in the Delay's queue, expired or within
     * [DELAY_EXPIRY_MARGIN_SECONDS] of its expiration, too late to be executed in time: it stays
     * there until the Delay's `skipExpired` removes it once expired ([QuoteDelaySkip]), and a new
     * entry of the same hash could not be told from it. [skippable] when it has expired by the head
     * the queue was read at, so a skip removes it now; false while it is only too close to its end.
     */
    data class SameCallExpired(val skippable: Boolean = true) : DelayQuoting

    /**
     * The Delay's next entry expired: `executeNextTx` reverts on it, so a transfer queued now would
     * wait behind it until the Delay's `skipExpired` removes it ([QuoteDelaySkip]).
     */
    data object HeadExpired : DelayQuoting

    /**
     * The Delay's expiration ([expirationSeconds]) is no longer than [DELAY_EXPIRY_MARGIN_SECONDS]:
     * an execution sent once the cooldown has passed could not be mined in time, so none would be
     * offered, and nothing is queued.
     */
    data class WindowTooShort(val expirationSeconds: Long) : DelayQuoting

    /**
     * The Safe's own transfer reverts, run as a verified call at [blockNumber] from the Safe: the
     * recipient does not take it (a contract without a payable `receive`), or the token refuses.
     */
    data class SafeCallReverts(val blockNumber: Long?) : DelayQuoting

    /**
     * The queue holds as many entries as Punicus reads ([searched], [DelayModules.MAX_QUEUE_READ]),
     * or more: one more could not be found again, nor whether one of them is the same call be told,
     * so nothing is queued.
     */
    data class QueueTooLong(val searched: Int) : DelayQuoting

    /**
     * An execution of the transfer from here ([sentAs], its transaction hash) is on its way, not
     * mined yet: a second one would revert once the first is mined, its fee paid.
     */
    data class ExecutionOnItsWay(val sentAs: String) : DelayQuoting

    /** The token answers false to the Safe's `transfer`: the Delay would spend its entry and move nothing. */
    data object TokenRefuses : DelayQuoting

    /** The history of what this wallet sent cannot be read right now, so what waits from here cannot be told. */
    data object HistoryUnreadable : DelayQuoting

    /** The sender's transaction itself was not quoted: [refusal]. */
    data class NotQuoted(val refusal: CallRefusal) : DelayQuoting

    /** The transfer cannot be executed now, standing as [state] in its queue. */
    data class NotReady(val state: DelayTransferState) : DelayQuoting

    /**
     * The change of the Delay's members is refused against its members as read now ([refusal]):
     * one added since, the last member left, or (at an execution) a removal that would leave none.
     */
    data class MemberChangeRefused(val refusal: DelayMemberChangePlan) : DelayQuoting

    /**
     * The Delay's members changed since the change was planned: a removal names another member
     * before it now. Planned again from the members as they are, it is a new call.
     */
    data object MembersChanged : DelayQuoting

    /**
     * The Safe's own change of its Delay's members reverts, run as a verified call from the Safe at
     * [blockNumber]: the members changed since it was queued (a removal's member before it is
     * another now, or the member was added or removed already).
     */
    data class MemberChangeReverts(val blockNumber: Long?) : DelayQuoting

    /**
     * Another change of the Delay's members queued from here ([queuedBy], its queueing's hash)
     * still waits: each change is planned against the members as they are, so one waits for the
     * other's execution or end.
     */
    data class MemberChangeWaiting(val queuedBy: String) : DelayQuoting
}

/**
 * The reads both quotes share: the Delay's queue read fresh at one block ([DelayModules.queue]),
 * which also proves the Delay the Safe's own of the current mastercopy; the Safe's verified balance
 * of the transfer's asset, at least its amount and what is already queued of it; for a token, the
 * Safe's own `transfer` run as a verified call, which must not answer false; the sender's native
 * balance; then the sender's call quoted ([CallQuoter]): the queueing, straight to the Delay or
 * through member Safes ([DelayTransfer.queueCall]), must return true, the execution, which returns
 * nothing, must not revert. Through Safes, the run is what proves the way: it reverts unless each
 * Safe is a member or owner of the next, as the call needs, and needs one signature.
 */
private class DelayCallQuoter(
    private val delays: DelayModules,
    private val inputs: TransactionInputs,
    private val nativeBalances: NativeBalances,
    private val tokenBalances: TokenBalances,
    sentTransfers: SentTransfers,
    private val network: Network,
    clock: () -> Long,
    outbox: Outbox,
) {
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock, outbox)

    /**
     * [call]'s queue as it is now, or why nothing is quoted through its Delay; with the Delay's
     * members at the same block for a change of them.
     */
    suspend fun freshQueue(call: DelayCall): Fresh =
        when (
            val reading =
                delays.queue(network, call.safe, call.delay, call.hash, withMembers = call is DelayMemberChange)
        ) {
            is DelayQueueReading.Read -> Fresh.Read(reading)
            is DelayQueueReading.NotTheSafes -> Fresh.Refused(DelayQuoting.NotTheSafesDelay(reading.generation))
            is DelayQueueReading.Unavailable -> Fresh.Refused(
                DelayQuoting.NotQuoted(
                    if (reading.reason == ReadUnavailable.SYNCING) {
                        CallRefusal.Syncing
                    } else {
                        CallRefusal.Unavailable(reading.reason, QuoteFigure.RUN)
                    },
                ),
            )
        }

    /** What [freshQueue] gives. */
    sealed interface Fresh {
        data class Read(val reading: DelayQueueReading.Read) : Fresh
        data class Refused(val quoting: DelayQuoting) : Fresh
    }

    suspend fun quote(
        sender: Address,
        delayCall: DelayCall,
        step: DelayStep,
        level: FeeLevel,
        alreadyQueued: UInt256 = UInt256.ZERO,
        via: List<Address> = emptyList(),
    ): DelayQuoting = cancellingReads {
        quoter.heldBack(sender)?.let { return@cancellingReads DelayQuoting.NotQuoted(it) }
        val memberNative = async { nativeBalances.observeBalance(network, sender).first() }
        val transfer = delayCall as? DelayTransfer
        // A change of the Delay's members moves nothing: no balance of the Safe's is checked for it.
        val safeHolds = if (transfer == null) {
            null
        } else {
            when (val read = safeBalance(transfer)) {
                is SafeHolds.Read -> read
                is SafeHolds.NotRead -> return@cancellingReads DelayQuoting.NotQuoted(read.refusal)
            }
        }
        if (transfer != null && safeHolds != null) {
            val needed = transfer.amount.plusOrNull(alreadyQueued)
            if (needed == null || safeHolds.amount < needed) {
                return@cancellingReads DelayQuoting.SafeInsufficient(alreadyQueued)
            }
        }
        // The Safe's own call, run as it would be executed: xDAI to a contract that does not take it
        // reverts, and a token that answers false instead of reverting would let the Delay spend its
        // entry and move nothing. Either would be queued, waited for and fail. A change of members is
        // the Safe's call of its own Delay, which reverts when the members changed since it was planned.
        when (val run = inputs.simulate(network, delayCall.safe, delayCall.to, delayCall.value, delayCall.data)) {
            is Simulation.Succeeded -> {
                val refuses = transfer?.asset is TransferAsset.Erc20Token && Erc20.returnedFalse(run.returnHex)
                if (refuses) return@cancellingReads DelayQuoting.TokenRefuses
            }
            is Simulation.Reverted -> return@cancellingReads if (transfer == null) {
                DelayQuoting.MemberChangeReverts(run.blockNumber)
            } else {
                DelayQuoting.SafeCallReverts(run.blockNumber)
            }
            Simulation.Refused -> return@cancellingReads DelayQuoting.NotQuoted(CallRefusal.Refused)
            is Simulation.Unavailable ->
                return@cancellingReads DelayQuoting.NotQuoted(CallRefusal.Unavailable(run.reason, QuoteFigure.RUN))
        }
        val native = when (val read = NativeRead.of(memberNative.await())) {
            is NativeRead.Before -> read
            is NativeRead.NotRead -> return@cancellingReads DelayQuoting.NotQuoted(read.refusal)
        }
        val (to, data) = when (step) {
            DelayStep.QUEUE -> delayCall.queueCall(sender, via)
            DelayStep.EXECUTE -> delayCall.delay to delayCall.executeData()
        }
        val accepts = when (step) {
            // `execTransactionFromModule` returns a bool, and so does a Safe's `execTransaction` around
            // it: a word of zero is a refusal as surely as a revert.
            DelayStep.QUEUE -> { returned: String -> !Erc20.returnedFalse(returned) }
            // `executeNextTx` returns nothing, and reverts when the Safe's call fails.
            DelayStep.EXECUTE -> { _: String -> true }
        }
        val route = if (step == DelayStep.QUEUE) via else emptyList()
        when (val quoted = quoter.quote(sender, to, UInt256.ZERO, data, level, native, accepts = accepts)) {
            is CallQuoting.Quoted -> DelayQuoting.Quoted(
                DelayQuote(network, delayCall, step, quoted.quote, safeHolds?.amount, safeHolds?.blockNumber, route),
            )
            is CallQuoting.Refused -> DelayQuoting.NotQuoted(quoted.refusal)
        }
    }

    private suspend fun safeBalance(transfer: DelayTransfer): SafeHolds = when (val asset = transfer.asset) {
        TransferAsset.Native -> when (val reading = nativeBalances.observeBalance(network, transfer.safe).first()) {
            is BalanceReading.Verified -> reading.wei.toUInt256OrNull()
                ?.let { SafeHolds.Read(it, reading.blockNumber) }
                ?: UNPROVEN_BALANCE
            BalanceReading.Syncing, is BalanceReading.LastKnown -> SafeHolds.NotRead(CallRefusal.Syncing)
            is BalanceReading.Unavailable ->
                SafeHolds.NotRead(CallRefusal.Unavailable(reading.reason, QuoteFigure.BALANCE))
        }
        is TransferAsset.Erc20Token -> when (
            val reading = tokenBalances.observeBalance(network, transfer.safe, asset.token.address).first()
        ) {
            is TokenBalanceReading.Verified -> UInt256.parse(reading.units.decimal)
                ?.let { SafeHolds.Read(it, reading.blockNumber) }
                ?: UNPROVEN_BALANCE
            TokenBalanceReading.Syncing, is TokenBalanceReading.LastKnown -> SafeHolds.NotRead(CallRefusal.Syncing)
            TokenBalanceReading.NotAToken -> UNPROVEN_BALANCE
            is TokenBalanceReading.Unavailable ->
                SafeHolds.NotRead(CallRefusal.Unavailable(reading.reason, QuoteFigure.BALANCE))
        }
    }

    private sealed interface SafeHolds {
        data class Read(val amount: UInt256, val blockNumber: Long?) : SafeHolds
        data class NotRead(val refusal: CallRefusal) : SafeHolds
    }

    private companion object {
        val UNPROVEN_BALANCE = SafeHolds.NotRead(CallRefusal.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.BALANCE))
    }
}

/**
 * Quotes the queueing of a Gnosis Pay account's [DelayCall] by `sender`, a key held here that
 * controls its Delay by itself: `execTransactionFromModule` sent to the Delay by `sender` as a
 * member, or, with `via`, through the member Safes on the way down to `sender`, each executing the
 * call of the one before as its one owner's transaction ([DelayTransfer.queueCall]; owner's request,
 * 2026-11-17). Run as a verified call from `sender`, which must succeed: it reverts for anyone who
 * is not a member, or not the one owner a Safe on the way needs. The Delay's queue is
 * read fresh first, which proves the Delay the Safe's own of the current mastercopy (owner's
 * decisions, 2026-11-15); an entry of the same call still in it, or a queueing of it from here not
 * mined yet, is refused ([DelayQuoting.AlreadyQueued]), so no two entries share a hash. The Safe must
 * hold the amount now, verified, beside what transfers of the same asset queued from here and not
 * executed will move: a transfer it cannot pay would be queued, waited for and fail.
 *
 * A [DelayMemberChange] is queued the same way (owner ruling, 2026-11-18), with no balance of the
 * Safe's to check: the queue is read with the Delay's members at the same block, against which
 * the change must still be planned as it was ([DelayQuoting.MemberChangeRefused],
 * [DelayQuoting.MembersChanged]); another change of the members queued from here and still waiting
 * holds it back ([DelayQuoting.MemberChangeWaiting]); and the Safe's own call of its Delay must not
 * revert, run as a verified call from the Safe ([DelayQuoting.MemberChangeReverts]). On [network]
 * only. [clock] is in unix seconds.
 */
class QuoteDelayQueue(
    delays: DelayModules,
    private val history: TransactionHistory,
    inputs: TransactionInputs,
    nativeBalances: NativeBalances,
    tokenBalances: TokenBalances,
    sentTransfers: SentTransfers,
    private val network: Network,
    outbox: Outbox,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    private val quoter =
        DelayCallQuoter(delays, inputs, nativeBalances, tokenBalances, sentTransfers, network, clock, outbox)

    suspend operator fun invoke(
        sender: Address,
        call: DelayCall,
        level: FeeLevel,
        via: List<Address> = emptyList(),
    ): DelayQuoting {
        val read = when (val fresh = quoter.freshQueue(call)) {
            is DelayCallQuoter.Fresh.Read -> fresh.reading
            is DelayCallQuoter.Fresh.Refused -> return fresh.quoting
        }
        val entries = (history.observe().first() as? HistoryList.Loaded)?.entries
            ?: return DelayQuoting.HistoryUnreadable
        val waiting = entries.waitingQueueings(network, call.safe, call.delay, clock(), read.queue)
        // A mined one is in the queue just read, or gone: only one on its way is the history's to tell.
        val sameCall = waiting.firstOrNull { it.queued.sameCall(call) && it.entry.onItsWay(clock()) }
        val queue = read.queue
        when {
            queue.expirationSeconds in 1..DELAY_EXPIRY_MARGIN_SECONDS ->
                return DelayQuoting.WindowTooShort(queue.expirationSeconds)
            queue.entry != null -> return if (queue.state(read.headTime) is DelayTransferState.Expired) {
                // A skip removes it now only once it is the next entry and expired as the Delay counts.
                val head = queue.entry.nonce == queue.txNonce
                DelayQuoting.SameCallExpired(skippable = head && queue.headExpired(read.headTime))
            } else {
                DelayQuoting.AlreadyQueued(sameCall?.entry?.transfer?.hash)
            }
            queue.headExpired(read.headTime) -> return DelayQuoting.HeadExpired
            // As deep as the queue is searched: an entry past it could not be found again to execute.
            queue.waiting >= DelayModules.MAX_QUEUE_READ -> return DelayQuoting.QueueTooLong(queue.searched)
            sameCall != null -> return DelayQuoting.AlreadyQueued(sameCall.entry.transfer.hash)
        }
        when (call) {
            is DelayTransfer -> {
                val alreadyQueued = waiting.mapNotNull { it.queued as? DelayTransfer }
                    .filter { it.asset.sameAsset(call.asset) }
                    .map { it.amount }
                    .sumOrNull() ?: return DelayQuoting.SafeInsufficient()
                return quoter.quote(sender, call, DelayStep.QUEUE, level, alreadyQueued, via)
            }
            is DelayMemberChange -> {
                // One change of the members at a time from here: each is planned against them as they are.
                waiting.firstOrNull { it.queued is DelayMemberChange }
                    ?.let { return DelayQuoting.MemberChangeWaiting(it.entry.transfer.hash) }
                memberCheck(call, queue)?.let { return it }
                return quoter.quote(sender, call, DelayStep.QUEUE, level, via = via)
            }
        }
    }
}

/**
 * Why [change] is not quoted against the members [queue] was read with (owner ruling, 2026-11-18):
 * a refusal it now meets, or members changed so that it is another call; null when it stands as
 * planned. A queue read without members proves nothing of them: it is a read that did not answer.
 */
private fun memberCheck(change: DelayMemberChange, queue: DelayQueue): DelayQuoting? {
    val members = queue.members
        ?: return DelayQuoting.NotQuoted(CallRefusal.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.RUN))
    return when (val plan = change.against(members)) {
        is DelayMemberChangePlan.Planned -> if (plan.change == change) null else DelayQuoting.MembersChanged
        else -> DelayQuoting.MemberChangeRefused(plan)
    }
}

/**
 * Quotes the execution of a Gnosis Pay account's [DelayTransfer] that `member` queued before, sent
 * by `member` itself, straight to the Delay: `executeNextTx`, which anyone may send. Only once its
 * queue, read fresh ([DelayModules.queue]), proves the Delay the Safe's own of the current
 * mastercopy and has the transfer as the next entry, past
 * its cooldown and short of its expiration ([DelayTransferState.Ready]); otherwise
 * [DelayQuoting.NotReady] says where it stands. The run as a verified call then makes the Safe's
 * own transfer, which must succeed. On [network] only. [clock] is in unix seconds.
 */
class QuoteDelayExecution(
    delays: DelayModules,
    private val history: TransactionHistory,
    inputs: TransactionInputs,
    nativeBalances: NativeBalances,
    tokenBalances: TokenBalances,
    sentTransfers: SentTransfers,
    private val network: Network,
    outbox: Outbox,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    private val quoter =
        DelayCallQuoter(delays, inputs, nativeBalances, tokenBalances, sentTransfers, network, clock, outbox)

    suspend operator fun invoke(member: Address, call: DelayCall, level: FeeLevel): DelayQuoting {
        val read = when (val fresh = quoter.freshQueue(call)) {
            is DelayCallQuoter.Fresh.Read -> fresh.reading
            is DelayCallQuoter.Fresh.Refused -> return fresh.quoting
        }
        val entries = (history.observe().first() as? HistoryList.Loaded)?.entries
            ?: return DelayQuoting.HistoryUnreadable
        // Until it is mined the queue still shows the entry next: the history tells it is on its way.
        entries.executionOnItsWay(network, call, clock())?.let { return DelayQuoting.ExecutionOnItsWay(it) }
        val state = read.queue.state(read.headTime)
        if (state !is DelayTransferState.Ready) return DelayQuoting.NotReady(state)
        // A removal never leaves the Delay without a member, whoever removed the others meanwhile.
        // Any other change of the members since makes the Safe's own call revert, which the run says.
        if (call is DelayMemberChange && call.kind == DelayMemberChange.Kind.REMOVE) {
            val members = read.queue.members
                ?: return DelayQuoting.NotQuoted(CallRefusal.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.RUN))
            if (members == listOf(call.member)) {
                return DelayQuoting.MemberChangeRefused(DelayMemberChangePlan.LastMember)
            }
        }
        return quoter.quote(member, call, DelayStep.EXECUTE, level)
    }
}

/**
 * Signs a reviewed [DelayQuote]'s transaction with the sender's key as [unlock] opens it, and hands
 * it to the network ([signAndSend]): recorded as the queueing or the execution it is, which is how
 * the account's queued transfers are found again ([ReadDelayTransfers]). [clock] is in unix seconds.
 */
class SendDelayCall(
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(quote: DelayQuote, unlock: Unlock): TransferSending =
        signAndSend(signer, broadcaster, sentTransfers, quote.network, quote.call, quote.action, clock, unlock)
}

/**
 * A call this wallet queued for a Gnosis Pay account: [call] (a transfer, or a change of the
 * Delay's members), queued by [member] (the key
 * that sent the queueing, a member or the owner of a member Safe) in the
 * transaction [queuedBy] at [queuedAt] (unix seconds) and how that ended ([queueOutcome]), and where
 * it stands in the Delay's queue now ([state]; null when the queue could not be read),
 * [executing], the hash of its execution from here while that is on its way, not mined yet, and
 * [expiredHead] when it is in the queue and the Delay's next entry, this one or one ahead of it, has
 * expired as the Delay counts: nothing from there on can be executed until that one is skipped
 * ([QuoteDelaySkip]).
 */
data class QueuedDelayTransfer(
    val call: DelayCall,
    val member: Address,
    val queuedBy: String,
    val queuedAt: Long,
    val queueOutcome: SentOutcome,
    val state: DelayTransferState?,
    val executing: String? = null,
    val expiredHead: Boolean = false,
)

/** What [ReadDelayTransfers] gives. */
sealed interface DelayTransfersReading {

    /**
     * The account's [transfers] waiting for their execution, oldest first; [queueUnread] why a
     * queue could not be read (null: every one was).
     */
    data class Read(val transfers: List<QueuedDelayTransfer>, val queueUnread: ReadUnavailable? = null) :
        DelayTransfersReading

    /** The history of what this wallet sent cannot be read right now. */
    data object HistoryUnreadable : DelayTransfersReading
}

/**
 * The transfers this wallet queued for the Gnosis Pay account [safe] through its Delay at [delay]
 * that still wait, and where each stands, its queue read fresh ([DelayModules.queue]). The history
 * ([TransactionHistory]) names the candidates: each queueing that did not fail and that no
 * execution from here was seen to succeed after. The queue decides: one whose queueing is not
 * mined yet (and was taken within [FOLLOW_SECONDS]) is listed while it is on its way; once it is,
 * only while the queue holds an entry of its call, or may hold one further back, or cannot be read.
 * One executed or skipped by anyone, expired and skipped, or never mined leaves the list, and so
 * does one mined longer ago than its entry can wait and be executed, plus a day. Each call's queue
 * is read once. [clock] is in unix seconds.
 */
class ReadDelayTransfers(
    private val history: TransactionHistory,
    private val delays: DelayModules,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(safe: AccountId, delay: Address): DelayTransfersReading {
        val entries = when (val list = history.observe().first()) {
            is HistoryList.Loaded -> list.entries
            HistoryList.Unreadable -> return DelayTransfersReading.HistoryUnreadable
        }
        val now = clock()
        var unread: ReadUnavailable? = null
        // One read per call, however many queueings share it, newest first: the first read's periods
        // bound which older ones can still hold an entry, so the poll's cost stays with the window,
        // not with the history.
        val reads = mutableMapOf<Digest, DelayQueueReading>()
        var window: Long? = null
        val transfers = entries.candidateQueueings(safe.network, safe.address, delay)
            .sortedByDescending { it.entry.transfer.sentAt }
            // One row per call: the queue holds one entry of a hash, which the newest queueing of it made.
            .distinctBy { it.queued.hash }
            .mapNotNull { (entry, call) ->
                val onItsWay = entry.onItsWay(now)
                val bound = window
                if (!onItsWay && bound != null && entry.transfer.sentAt < now - bound) return@mapNotNull null
                val read = reads.getOrPut(call.hash) {
                    delays.queue(safe.network, safe.address, delay, call.hash)
                }
                var blocked = false
                val state = when (read) {
                    is DelayQueueReading.Read -> {
                        // Only one found in the queue: a queueing not mined yet waits for its block first.
                        blocked = read.queue.entry != null && read.queue.headExpired(read.headTime)
                        val shown = window ?: read.queue.shownFor()?.also { window = it }
                        if (!onItsWay && shown != null && entry.transfer.sentAt < now - shown) return@mapNotNull null
                        read.queue.state(read.headTime)
                    }
                    // Not this Safe's Delay any more: nothing of it waits through it.
                    is DelayQueueReading.NotTheSafes -> return@mapNotNull null
                    is DelayQueueReading.Unavailable -> null.also { unread = read.reason }
                }
                val holds = state != null && state != DelayTransferState.NotInQueue
                if (!onItsWay && state != null && !holds) return@mapNotNull null
                QueuedDelayTransfer(
                    call,
                    entry.transfer.account,
                    entry.transfer.hash,
                    entry.transfer.sentAt,
                    entry.outcome,
                    state,
                    entries.executionOnItsWay(safe.network, call, now),
                    expiredHead = blocked,
                )
            }
            .reversed()
        return DelayTransfersReading.Read(transfers, unread)
    }
}

/**
 * How long after its queueing a transfer is still listed: while its entry can wait and be executed
 * ([DelayQueue.cooldownSeconds] and [DelayQueue.expirationSeconds]), and a day more, so that an
 * expired one is said to have expired before it leaves the list; null with no expiration, when an
 * entry waits for good.
 */
private fun DelayQueue.shownFor(): Long? =
    if (expirationSeconds == 0L) null else cooldownSeconds + expirationSeconds + EXPIRED_SHOWN_SECONDS

/** How long an expired transfer stays listed, saying it expired: a day. */
private const val EXPIRED_SHOWN_SECONDS = 86_400L

/** A queueing in the history: its [entry], and the call it [queued]. */
private data class Queueing(val entry: HistoryEntry, val queued: DelayCall)

/**
 * The queueings on [network] in this history for the Safe [safe] through [delay] that may still
 * wait: neither failed on chain, nor followed by an execution of the same call from here that a
 * verified receipt shows succeeded.
 */
private fun List<HistoryEntry>.candidateQueueings(network: Network, safe: Address, delay: Address): List<Queueing> =
    mapNotNull { entry ->
        val queued = (entry.transfer.action as? SentAction.DelayQueue)?.call ?: return@mapNotNull null
        if (entry.transfer.network != network || queued.safe != safe || queued.delay != delay) return@mapNotNull null
        if (entry.outcome.failed()) return@mapNotNull null
        val executed = any {
            val execution = (it.transfer.action as? SentAction.DelayExecution)?.call ?: return@any false
            execution.sameCall(queued) && it.transfer.network == network &&
                it.transfer.sentAt >= entry.transfer.sentAt && it.outcome.succeeded()
        }
        if (executed) null else Queueing(entry, queued)
    }

/**
 * Of [candidateQueueings], those that still hold the Safe's funds, as far as a quote can tell
 * without reading each one's entry: on their way (not mined yet, taken within [FOLLOW_SECONDS]), or
 * mined within the window [queue]'s periods give an entry. What the next queueing must leave room for.
 */
private fun List<HistoryEntry>.waitingQueueings(
    network: Network,
    safe: Address,
    delay: Address,
    now: Long,
    queue: DelayQueue,
): List<Queueing> {
    val window = if (queue.expirationSeconds == 0L) null else queue.cooldownSeconds + queue.expirationSeconds
    // Entries that never expire (no window): no age tells which still wait, so every one counts.
    // That may count one executed elsewhere; it never counts too little, which would queue a transfer
    // the Safe cannot pay.
    return candidateQueueings(network, safe, delay).filter { (entry, _) ->
        entry.onItsWay(now) || window == null || entry.transfer.sentAt >= now - window
    }
}

/**
 * The hash of an execution of [call] from here on [network] that is on its way, not
 * mined yet and taken within [FOLLOW_SECONDS]; null when there is none.
 */
private fun List<HistoryEntry>.executionOnItsWay(network: Network, call: DelayCall, now: Long): String? =
    firstOrNull {
        val execution = (it.transfer.action as? SentAction.DelayExecution)?.call ?: return@firstOrNull false
        it.transfer.network == network && execution.sameCall(call) && it.onItsWay(now)
    }?.transfer?.hash

/** Whether this queueing is not mined yet, and was taken recently enough to still be. */
private fun HistoryEntry.onItsWay(now: Long): Boolean =
    outcome == SentOutcome.NotSeen && transfer.sentAt >= now - FOLLOW_SECONDS

private fun TransferAsset.sameAsset(other: TransferAsset): Boolean = when (this) {
    TransferAsset.Native -> other == TransferAsset.Native
    is TransferAsset.Erc20Token -> (other as? TransferAsset.Erc20Token)?.token?.address == token.address
}

private fun SentOutcome.succeeded(): Boolean =
    (this as? SentOutcome.Included)?.succeeded == true || (this as? SentOutcome.Finalized)?.succeeded == true

private fun SentOutcome.failed(): Boolean =
    (this as? SentOutcome.Included)?.succeeded == false || (this as? SentOutcome.Finalized)?.succeeded == false

/**
 * What removing [member] from the Delay does to this device's control of the account (owner
 * ruling, 2026-11-18: warned, not refused, when it is the way this device controls it).
 */
enum class MemberRemovalEffect {
    /** No key held here reaches the Delay through [member]: this device's control stays as it is. */
    NOT_A_WAY_HERE,

    /** A key held here reaches the Delay through [member], and another way held here remains. */
    ANOTHER_WAY_REMAINS,

    /** Every way this device controls the account goes through [member]: once executed, it sends no more. */
    LAST_WAY_HERE,
}

/**
 * What removing [member] does to the ways this device controls the account
 * ([GnosisPayReading.Found.controllersHeld]): a way goes through [member] when [member] is the held
 * key itself, a member, or the member Safe the key reaches the Delay through.
 */
fun GnosisPayReading.Found.removalOf(member: Address): MemberRemovalEffect {
    val through = { held: HeldController -> (held.via.firstOrNull() ?: held.account.address) == member }
    return when {
        controllersHeld.none(through) -> MemberRemovalEffect.NOT_A_WAY_HERE
        controllersHeld.all(through) -> MemberRemovalEffect.LAST_WAY_HERE
        else -> MemberRemovalEffect.ANOTHER_WAY_REMAINS
    }
}
