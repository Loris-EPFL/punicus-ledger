package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DelayTransfer
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.Broadcaster
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
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TransactionHistory
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first

// A Gnosis Pay account's transfer, through its Delay (docs/gnosis-pay.md, *Sending*; owner's
// decisions, 2026-11-15): a member whose key is held here queues it, and after the cooldown sends
// its execution. Both are the member's own transactions, quoted and sent as every transaction from
// an account this wallet holds ([CallQuoter], [signAndSend]). Strict in either sync mode: nothing
// here is drafted on state last verified, as swaps and dApp requests are not (CLAUDE.md, *Trust*).

/**
 * Where a queued transfer stands in its Delay's queue at a verified head produced at `headTime`
 * (unix seconds): what the queue's own `executeNextTx` checks, worked out from its getters so that
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
     * Its cooldown has passed, but [ahead] entries queued before it come first; it may be executed
     * until [expiresAt] (null: for ever). Punicus never executes an entry it did not queue.
     */
    data class Behind(val ahead: Int, val expiresAt: Long?) : DelayTransferState

    /** Its window has passed: it can never be executed, and was never moved. */
    data object Expired : DelayTransferState

    /** The queue holds no entry of its hash from its next one on: not queued yet, or executed or skipped since. */
    data object NotInQueue : DelayTransferState
}

/**
 * Where [transfer] stands in [queue] at a head produced at [headTime]: the first entry of its hash
 * from the next to execute on. An entry past what the queue was read up to is [DelayTransferState.NotInQueue].
 */
fun DelayQueue.stateOf(transfer: DelayTransfer, headTime: Long): DelayTransferState {
    val index = entries.indexOfFirst { it.hash == transfer.hash }
    if (index < 0) return DelayTransferState.NotInQueue
    val entry = entries[index]
    val readyAt = entry.createdAt + cooldownSeconds
    val expiresAt = if (expirationSeconds == 0L) null else readyAt + expirationSeconds
    return when {
        expiresAt != null && headTime > expiresAt -> DelayTransferState.Expired
        headTime < readyAt -> DelayTransferState.Waiting(readyAt, expiresAt, index)
        index == 0 -> DelayTransferState.Ready(expiresAt)
        else -> DelayTransferState.Behind(index, expiresAt)
    }
}

/**
 * A Gnosis Pay account's transfer quoted for review: [action], what the member's transaction does
 * (its queueing or its execution), its transaction quoted from the member ([call]), and the Safe's
 * balance of the asset it was checked against ([safeBalance], at [safeBalanceBlock]). Only this file
 * makes one: what is signed is its [call]'s transaction.
 */
@ConsistentCopyVisibility
data class DelayQuote internal constructor(
    val network: Network,
    val action: SentAction,
    val call: CallQuote,
    val safeBalance: UInt256,
    val safeBalanceBlock: Long?,
) {
    /** The transfer queued or executed. */
    val transfer: DelayTransfer
        get() = when (val action = action) {
            is SentAction.DelayQueue -> action.transfer
            is SentAction.DelayExecution -> action.transfer
            else -> error("a Delay quote is of a Delay call")
        }
}

/** What [QuoteDelayQueue] and [QuoteDelayExecution] give. */
sealed interface DelayQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: DelayQuote) : DelayQuoting

    /** The Safe holds less of the asset than the amount: the execution would fail, its fee paid. */
    data object SafeInsufficient : DelayQuoting

    /** The member's transaction itself was not quoted: [refusal]. */
    data class NotQuoted(val refusal: CallRefusal) : DelayQuoting

    /** The transfer cannot be executed now, standing as [state] in its queue. */
    data class NotReady(val state: DelayTransferState) : DelayQuoting
}

/**
 * The reads both quotes share: the Safe's verified balance of the transfer's asset, at least its
 * amount, and the member's native balance, then the member's call of the Delay quoted ([CallQuoter])
 * with [data], which must not revert and must return true.
 */
private class DelayCallQuoter(
    private val inputs: TransactionInputs,
    private val nativeBalances: NativeBalances,
    private val tokenBalances: TokenBalances,
    sentTransfers: SentTransfers,
    private val network: Network,
    clock: () -> Long,
    outbox: Outbox,
) {
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock, outbox)

    suspend fun quote(
        member: Address,
        transfer: DelayTransfer,
        action: SentAction,
        data: ByteArray,
        level: FeeLevel,
    ): DelayQuoting = cancellingReads {
        quoter.heldBack(member)?.let { return@cancellingReads DelayQuoting.NotQuoted(it) }
        val memberNative = async { nativeBalances.observeBalance(network, member).first() }
        val safeHolds = when (val read = safeBalance(transfer)) {
            is SafeHolds.Read -> read
            is SafeHolds.NotRead -> return@cancellingReads DelayQuoting.NotQuoted(read.refusal)
        }
        if (safeHolds.amount < transfer.amount) return@cancellingReads DelayQuoting.SafeInsufficient
        val native = when (val read = NativeRead.of(memberNative.await())) {
            is NativeRead.Before -> read
            is NativeRead.NotRead -> return@cancellingReads DelayQuoting.NotQuoted(read.refusal)
        }
        // Both Delay calls return a bool: a word of zero is a refusal as surely as a revert.
        val accepts: (String) -> Boolean = { returned -> returned.isEmpty() || returned.any { it != '0' } }
        when (val quoted = quoter.quote(member, transfer.delay, UInt256.ZERO, data, level, native, accepts = accepts)) {
            is CallQuoting.Quoted -> DelayQuoting.Quoted(
                DelayQuote(network, action, quoted.quote, safeHolds.amount, safeHolds.blockNumber),
            )
            is CallQuoting.Refused -> DelayQuoting.NotQuoted(quoted.refusal)
        }
    }

    private suspend fun safeBalance(transfer: DelayTransfer): SafeHolds =
        when (val asset = transfer.asset) {
            TransferAsset.Native -> when (val reading = nativeBalances.observeBalance(network, transfer.safe).first()) {
                is BalanceReading.Verified -> reading.wei.toUInt256OrNull()
                    ?.let { SafeHolds.Read(it, reading.blockNumber) }
                    ?: SafeHolds.NotRead(CallRefusal.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.BALANCE))
                BalanceReading.Syncing, is BalanceReading.LastKnown -> SafeHolds.NotRead(CallRefusal.Syncing)
                is BalanceReading.Unavailable ->
                    SafeHolds.NotRead(CallRefusal.Unavailable(reading.reason, QuoteFigure.BALANCE))
            }
            is TransferAsset.Erc20Token -> when (
                val reading = tokenBalances.observeBalance(network, transfer.safe, asset.token.address).first()
            ) {
                is TokenBalanceReading.Verified -> UInt256.parse(reading.units.decimal)
                    ?.let { SafeHolds.Read(it, reading.blockNumber) }
                    ?: SafeHolds.NotRead(CallRefusal.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.BALANCE))
                TokenBalanceReading.Syncing, is TokenBalanceReading.LastKnown -> SafeHolds.NotRead(CallRefusal.Syncing)
                TokenBalanceReading.NotAToken ->
                    SafeHolds.NotRead(CallRefusal.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.BALANCE))
                is TokenBalanceReading.Unavailable ->
                    SafeHolds.NotRead(CallRefusal.Unavailable(reading.reason, QuoteFigure.BALANCE))
            }
        }

    private sealed interface SafeHolds {
        data class Read(val amount: UInt256, val blockNumber: Long?) : SafeHolds
        data class NotRead(val refusal: CallRefusal) : SafeHolds
    }
}

/**
 * Quotes the queueing of a Gnosis Pay account's [DelayTransfer] by [member], a member of its Delay
 * whose key is held here: `execTransactionFromModule` sent to the Delay, run as a verified call from
 * the member, which must succeed (it reverts for anyone who is not a member). The Safe must hold the
 * amount now, verified: a transfer it cannot pay would be queued, waited for and fail. On [network]
 * only. [clock] is in unix seconds.
 */
class QuoteDelayQueue(
    inputs: TransactionInputs,
    nativeBalances: NativeBalances,
    tokenBalances: TokenBalances,
    sentTransfers: SentTransfers,
    network: Network,
    outbox: Outbox,
    clock: () -> Long = ::epochSecondsNow,
) {
    private val quoter = DelayCallQuoter(inputs, nativeBalances, tokenBalances, sentTransfers, network, clock, outbox)

    suspend operator fun invoke(member: Address, transfer: DelayTransfer, level: FeeLevel): DelayQuoting =
        quoter.quote(member, transfer, SentAction.DelayQueue(transfer), transfer.queueData(), level)
}

/**
 * Quotes the execution of a Gnosis Pay account's [DelayTransfer] that [member] queued before:
 * `executeNextTx` sent to the Delay. Only once its queue, read fresh ([DelayModules.queue]), has it
 * as the next entry, past its cooldown and before its expiration ([DelayTransferState.Ready]);
 * otherwise [DelayQuoting.NotReady] says where it stands. The run as a verified call then makes the
 * Safe's own transfer, which must succeed. On [network] only. [clock] is in unix seconds.
 */
class QuoteDelayExecution(
    private val delays: DelayModules,
    inputs: TransactionInputs,
    nativeBalances: NativeBalances,
    tokenBalances: TokenBalances,
    sentTransfers: SentTransfers,
    private val network: Network,
    outbox: Outbox,
    clock: () -> Long = ::epochSecondsNow,
) {
    private val quoter = DelayCallQuoter(inputs, nativeBalances, tokenBalances, sentTransfers, network, clock, outbox)

    suspend operator fun invoke(member: Address, transfer: DelayTransfer, level: FeeLevel): DelayQuoting {
        val read = when (val reading = delays.queue(network, transfer.delay)) {
            is DelayQueueReading.Read -> reading
            is DelayQueueReading.Unavailable ->
                return DelayQuoting.NotQuoted(CallRefusal.Unavailable(reading.reason, QuoteFigure.RUN))
        }
        val state = read.queue.stateOf(transfer, read.headTime)
        if (state !is DelayTransferState.Ready) return DelayQuoting.NotReady(state)
        return quoter.quote(member, transfer, SentAction.DelayExecution(transfer), transfer.executeData(), level)
    }
}

/**
 * Signs a reviewed [DelayQuote]'s transaction with the member's key as [unlock] opens it, and hands
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
 * A transfer this wallet queued for a Gnosis Pay account: [transfer], queued by [member] in the
 * transaction [queuedBy] and how that ended ([queueOutcome]), and where it stands in the Delay's
 * queue now ([state]; null when the queue could not be read).
 */
data class QueuedDelayTransfer(
    val transfer: DelayTransfer,
    val member: Address,
    val queuedBy: String,
    val queuedAt: Long,
    val queueOutcome: SentOutcome,
    val state: DelayTransferState?,
)

/** What [ReadDelayTransfers] gives. */
sealed interface DelayTransfersReading {

    /** The account's [transfers] waiting for their execution, oldest first; [queueUnread] why their queue was not read. */
    data class Read(val transfers: List<QueuedDelayTransfer>, val queueUnread: ReadUnavailable? = null) :
        DelayTransfersReading

    /** The history of what this wallet sent cannot be read right now. */
    data object HistoryUnreadable : DelayTransfersReading
}

/**
 * The transfers this wallet queued for the Gnosis Pay account [safe] through its Delay at [delay]
 * and has not executed: each queueing in the history ([TransactionHistory]) with no execution of
 * the same transfer sent from here after it, whatever became of it on chain since, and where each
 * stands in the queue, read fresh ([DelayModules.queue]). One no longer in the queue whose queueing
 * was mined was executed or skipped by someone else: it is still listed, as
 * [DelayTransferState.NotInQueue], so that the user sees it went.
 */
class ReadDelayTransfers(
    private val history: TransactionHistory,
    private val delays: DelayModules,
) {
    suspend operator fun invoke(safe: AccountId, delay: Address): DelayTransfersReading {
        val entries = when (val list = history.observe().first()) {
            is HistoryList.Loaded -> list.entries.filter { it.transfer.network == safe.network }
            HistoryList.Unreadable -> return DelayTransfersReading.HistoryUnreadable
        }
        val pending = entries.filter { entry ->
            val queued = (entry.transfer.action as? SentAction.DelayQueue)?.transfer ?: return@filter false
            queued.safe == safe.address && queued.delay == delay && !entries.executedAfter(entry, queued)
        }
        if (pending.isEmpty()) return DelayTransfersReading.Read(emptyList())
        val read = delays.queue(safe.network, delay)
        val transfers = pending.sortedBy { it.transfer.sentAt }.map { entry ->
            val transfer = (entry.transfer.action as SentAction.DelayQueue).transfer
            QueuedDelayTransfer(
                transfer,
                entry.transfer.account,
                entry.transfer.hash,
                entry.transfer.sentAt,
                entry.outcome,
                (read as? DelayQueueReading.Read)?.let { it.queue.stateOf(transfer, it.headTime) },
            )
        }
        return DelayTransfersReading.Read(transfers, (read as? DelayQueueReading.Unavailable)?.reason)
    }

    /** Whether an execution of [transfer] was sent from here after [queueing]. */
    private fun List<HistoryEntry>.executedAfter(queueing: HistoryEntry, transfer: DelayTransfer): Boolean = any {
        val executed = (it.transfer.action as? SentAction.DelayExecution)?.transfer
        executed == transfer && it.transfer.sentAt >= queueing.transfer.sentAt
    }
}
