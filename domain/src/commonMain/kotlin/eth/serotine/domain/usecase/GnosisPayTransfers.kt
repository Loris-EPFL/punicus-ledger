package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DelayTransfer
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.DelayFinding
import eth.serotine.domain.repository.DelayGeneration
import eth.serotine.domain.repository.DelayModules
import eth.serotine.domain.repository.DelayQueue
import eth.serotine.domain.repository.DelayQueueReading
import eth.serotine.domain.repository.DelayReading
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
     * queued them, or skipped once expired (the Delay's `skipExpired`, not offered here yet). It may
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
        expiresAt != null && headTime + DELAY_EXPIRY_MARGIN_SECONDS > expiresAt -> DelayTransferState.Expired(expiresAt)
        headTime < readyAt -> DelayTransferState.Waiting(readyAt, expiresAt, ahead)
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
 * A Gnosis Pay account's [transfer] quoted for review: the member's [step], its transaction quoted
 * from the member ([call]), and the Safe's balance of the asset it was checked against
 * ([safeBalance], at [safeBalanceBlock]). Only this file makes one: what is signed is its [call]'s
 * transaction.
 */
@ConsistentCopyVisibility
data class DelayQuote internal constructor(
    val network: Network,
    val transfer: DelayTransfer,
    val step: DelayStep,
    val call: CallQuote,
    val safeBalance: UInt256,
    val safeBalanceBlock: Long?,
) {
    /** What the member's transaction is recorded as. */
    val action: SentAction
        get() = when (step) {
            DelayStep.QUEUE -> SentAction.DelayQueue(transfer)
            DelayStep.EXECUTE -> SentAction.DelayExecution(transfer)
        }
}

/** What [QuoteDelayQueue] and [QuoteDelayExecution] give. */
sealed interface DelayQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: DelayQuote) : DelayQuoting

    /**
     * The Delay is no longer the Safe's own of the current mastercopy, as proven now ([finding];
     * null when the module is no Delay at all): nothing is sent through it.
     */
    data class NotTheSafesDelay(val finding: DelayFinding?) : DelayQuoting

    /** The Safe holds less of the asset than the amount: the execution would fail, its fee paid. */
    data object SafeInsufficient : DelayQuoting

    /**
     * The same transfer, queued from here, waits for its execution still ([queuedBy], its
     * queueing): queuing it again would make two entries of one hash, which nothing tells apart.
     */
    data class AlreadyQueued(val queuedBy: String) : DelayQuoting

    /** The member's transaction itself was not quoted: [refusal]. */
    data class NotQuoted(val refusal: CallRefusal) : DelayQuoting

    /** The transfer cannot be executed now, standing as [state] in its queue. */
    data class NotReady(val state: DelayTransferState) : DelayQuoting
}

/**
 * The reads both quotes share: the Delay proven, fresh, to be the Safe's own of the current
 * mastercopy; the Safe's verified balance of the transfer's asset, at least its amount; the
 * member's native balance; then the member's call of the Delay quoted ([CallQuoter]) with [data],
 * which must not revert and must return true.
 */
private class DelayCallQuoter(
    private val delays: DelayModules,
    inputs: TransactionInputs,
    private val nativeBalances: NativeBalances,
    private val tokenBalances: TokenBalances,
    sentTransfers: SentTransfers,
    private val network: Network,
    clock: () -> Long,
    outbox: Outbox,
) {
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock, outbox)

    /** Why nothing may be sent through [transfer]'s Delay, as proven now; null when it is the Safe's own, current. */
    suspend fun refusedDelay(transfer: DelayTransfer): DelayQuoting? {
        val reading = delays.observeDelay(network, transfer.safe, transfer.delay).first { it != DelayReading.Syncing }
        return when (reading) {
            DelayReading.Syncing -> error("filtered out")
            is DelayReading.Unavailable -> DelayQuoting.NotQuoted(CallRefusal.Unavailable(reading.reason))
            is DelayReading.Verified -> when (val finding = reading.finding) {
                is DelayFinding.Wired -> if (finding.module.generation == DelayGeneration.CURRENT) {
                    null
                } else {
                    DelayQuoting.NotTheSafesDelay(finding)
                }
                is DelayFinding.NotWired -> DelayQuoting.NotTheSafesDelay(finding)
                DelayFinding.NotDelay -> DelayQuoting.NotTheSafesDelay(null)
            }
        }
    }

    suspend fun quote(
        member: Address,
        transfer: DelayTransfer,
        step: DelayStep,
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
        val data = when (step) {
            DelayStep.QUEUE -> transfer.queueData()
            DelayStep.EXECUTE -> transfer.executeData()
        }
        // Both Delay calls return a bool: a word of zero is a refusal as surely as a revert.
        val accepts: (String) -> Boolean = { returned -> !Erc20.returnedFalse(returned) }
        when (val quoted = quoter.quote(member, transfer.delay, UInt256.ZERO, data, level, native, accepts = accepts)) {
            is CallQuoting.Quoted -> DelayQuoting.Quoted(
                DelayQuote(network, transfer, step, quoted.quote, safeHolds.amount, safeHolds.blockNumber),
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
 * Quotes the queueing of a Gnosis Pay account's [DelayTransfer] by [member], a member of its Delay
 * whose key is held here: `execTransactionFromModule` sent to the Delay, run as a verified call from
 * the member, which must succeed (it reverts for anyone who is not a member). The Delay must be
 * proven, now, to be the Safe's own of the current mastercopy (owner's decisions, 2026-11-15); the
 * Safe must hold the amount now, verified: a transfer it cannot pay would be queued, waited for and
 * fail. The same transfer still waiting from here is refused ([DelayQuoting.AlreadyQueued]). On
 * [network] only. [clock] is in unix seconds.
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
    clock: () -> Long = ::epochSecondsNow,
) {
    private val quoter =
        DelayCallQuoter(delays, inputs, nativeBalances, tokenBalances, sentTransfers, network, clock, outbox)

    suspend operator fun invoke(member: Address, transfer: DelayTransfer, level: FeeLevel): DelayQuoting {
        quoter.refusedDelay(transfer)?.let { return it }
        val entries = (history.observe().first() as? HistoryList.Loaded)?.entries
            ?: return DelayQuoting.NotQuoted(CallRefusal.Unavailable(ReadUnavailable.UNPROVEN))
        entries.pendingQueueings(network, transfer).firstOrNull()
            ?.let { return DelayQuoting.AlreadyQueued(it.entry.transfer.hash) }
        return quoter.quote(member, transfer, DelayStep.QUEUE, level)
    }
}

/**
 * Quotes the execution of a Gnosis Pay account's [DelayTransfer] that [member] queued before:
 * `executeNextTx` sent to the Delay. Only through the Safe's own Delay of the current mastercopy,
 * proven now, and only once its queue, read fresh ([DelayModules.queue]), has the transfer as the
 * next entry, past its cooldown and short of its expiration ([DelayTransferState.Ready]); otherwise
 * [DelayQuoting.NotReady] says where it stands. The run as a verified call then makes the Safe's
 * own transfer, which must succeed. On [network] only. [clock] is in unix seconds.
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
    private val quoter =
        DelayCallQuoter(delays, inputs, nativeBalances, tokenBalances, sentTransfers, network, clock, outbox)

    suspend operator fun invoke(member: Address, transfer: DelayTransfer, level: FeeLevel): DelayQuoting {
        quoter.refusedDelay(transfer)?.let { return it }
        val read = when (val reading = delays.queue(network, transfer.delay, transfer.hash)) {
            is DelayQueueReading.Read -> reading
            is DelayQueueReading.Unavailable ->
                return DelayQuoting.NotQuoted(CallRefusal.Unavailable(reading.reason, QuoteFigure.RUN))
        }
        val state = read.queue.state(read.headTime)
        if (state !is DelayTransferState.Ready) return DelayQuoting.NotReady(state)
        return quoter.quote(member, transfer, DelayStep.EXECUTE, level)
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
 * transaction [queuedBy] at [queuedAt] (unix seconds) and how that ended ([queueOutcome]), and where
 * it stands in the Delay's queue now ([state]; null when the queue could not be read).
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
 * and has not executed: each queueing in the history ([TransactionHistory]) with no execution of
 * the same transfer from here after it that a verified receipt shows succeeded, and where each
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
            is HistoryList.Loaded -> list.entries
            HistoryList.Unreadable -> return DelayTransfersReading.HistoryUnreadable
        }
        val pending = entries.pendingQueueings(safe.network, null)
            .filter { it.queued.safe == safe.address && it.queued.delay == delay }
        var unread: ReadUnavailable? = null
        val transfers = pending.sortedBy { it.entry.transfer.sentAt }.map { (entry, transfer) ->
            val state = when (val read = delays.queue(safe.network, delay, transfer.hash)) {
                is DelayQueueReading.Read -> read.queue.state(read.headTime)
                is DelayQueueReading.Unavailable -> null.also { unread = read.reason }
            }
            QueuedDelayTransfer(
                transfer,
                entry.transfer.account,
                entry.transfer.hash,
                entry.transfer.sentAt,
                entry.outcome,
                state,
            )
        }
        return DelayTransfersReading.Read(transfers, unread)
    }
}

/** A queueing in the history: its [entry], and the transfer it [queued]. */
private data class Queueing(val entry: HistoryEntry, val queued: DelayTransfer)

/**
 * The queueings on [network] in this history of [transfer] (null: of any) that wait for their
 * execution still: neither failed on chain, nor followed by an execution of the same transfer from
 * here that a verified receipt shows succeeded.
 */
private fun List<HistoryEntry>.pendingQueueings(network: Network, transfer: DelayTransfer?): List<Queueing> =
    mapNotNull { entry ->
        val queued = (entry.transfer.action as? SentAction.DelayQueue)?.transfer ?: return@mapNotNull null
        if (entry.transfer.network != network || (transfer != null && queued != transfer)) return@mapNotNull null
        if (entry.outcome.failed()) return@mapNotNull null
        val executed = any {
            (it.transfer.action as? SentAction.DelayExecution)?.transfer == queued &&
                it.transfer.network == network && it.transfer.sentAt >= entry.transfer.sentAt && it.outcome.succeeded()
        }
        if (executed) null else Queueing(entry, queued)
    }

private fun SentOutcome.succeeded(): Boolean =
    (this as? SentOutcome.Included)?.succeeded == true || (this as? SentOutcome.Finalized)?.succeeded == true

private fun SentOutcome.failed(): Boolean =
    (this as? SentOutcome.Included)?.succeeded == false || (this as? SentOutcome.Finalized)?.succeeded == false
