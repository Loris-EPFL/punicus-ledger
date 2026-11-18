package eth.serotine.domain.usecase

import eth.serotine.domain.model.AbiWords
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.DelayGeneration
import eth.serotine.domain.repository.DelayModules
import eth.serotine.domain.repository.DelayQueueReading
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.TransactionHistory
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.flow.first

// The Delay's queue is first in, first out, and an entry past its expiration blocks every entry
// behind it: `executeNextTx` reverts on it. The Delay's own `skipExpired()` moves its next entry
// past every expired one; anyone may call it, and it moves nothing (docs/gnosis-pay.md, *Skipping
// expired entries*).

/** `skipExpired()`: the Delay's call that moves its next entry past every expired one. */
internal val SKIP_EXPIRED: ByteArray = AbiWords.selector("skipExpired()")

/**
 * The skip of a Gnosis Pay account's expired Delay entries quoted for review: [delay], the Safe at
 * [safe]'s own Delay of the current mastercopy, whose next entry was queued at [headCreatedAt]
 * (unix seconds) and expired by the head at [headTime]; [call], the sender's `skipExpired()` sent
 * to the Delay. Only this file makes one: what is signed is its [call]'s transaction.
 */
@ConsistentCopyVisibility
data class DelaySkipQuote internal constructor(
    val network: Network,
    val safe: Address,
    val delay: Address,
    val call: CallQuote,
    val headCreatedAt: Long,
    val headTime: Long,
    val waiting: Long,
) {
    /** What the sender's transaction is recorded as. */
    val action: SentAction
        get() = SentAction.DelaySkip(safe, delay)
}

/** What [QuoteDelaySkip] gives. */
sealed interface DelaySkipQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: DelaySkipQuote) : DelaySkipQuoting

    /** The Delay's next entry has not expired, or the queue is empty: there is nothing to skip. */
    data object NothingExpired : DelaySkipQuoting

    /**
     * The Delay is not the Safe's own of the current mastercopy, as proven now ([generation] as in
     * [DelayQuoting.NotTheSafesDelay]): nothing is sent to it.
     */
    data class NotTheSafesDelay(val generation: DelayGeneration?) : DelaySkipQuoting

    /** A skip of this Delay from here ([sentAs], its transaction hash) is on its way, not mined yet. */
    data class SkipOnItsWay(val sentAs: String) : DelaySkipQuoting

    /** The history of what this wallet sent cannot be read right now, so a skip on its way cannot be told. */
    data object HistoryUnreadable : DelaySkipQuoting

    /** The sender's transaction itself was not quoted: [refusal]. */
    data class NotQuoted(val refusal: CallRefusal) : DelaySkipQuoting
}

/**
 * Quotes `skipExpired()` on the Delay at `delay` of the Safe at `safe`, sent by `sender`, any key
 * held here on [network]: it moves nothing, so no control of the account is needed. The Delay's
 * queue is read fresh first ([DelayModules.queue]), which proves the Delay the Safe's own of the
 * current mastercopy (owner's decisions, 2026-11-15: nothing is sent to another); its next entry
 * must have expired by the head the queue was read at, else there is nothing to skip. A skip of the
 * same Delay from here that no block has taken is refused for as long as a sender's record holds
 * its nonce ([RECORD_LIFETIME_SECONDS]), as a second would only pay its fee. The sender's call is
 * then quoted ([CallQuoter]): run as a verified call, it must not revert, and its gas limit has
 * room to skip every entry still waiting, since more may expire by the block that mines it.
 * [clock] is in unix seconds.
 */
class QuoteDelaySkip(
    private val delays: DelayModules,
    private val history: TransactionHistory,
    inputs: TransactionInputs,
    private val nativeBalances: NativeBalances,
    sentTransfers: SentTransfers,
    private val network: Network,
    outbox: Outbox,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock, outbox)

    suspend operator fun invoke(sender: Address, safe: Address, delay: Address, level: FeeLevel): DelaySkipQuoting {
        // The queue is searched for no call: only its head, its periods and the Delay's code are wanted.
        val read = when (val reading = delays.queue(network, safe, delay, NO_CALL)) {
            is DelayQueueReading.Read -> reading
            is DelayQueueReading.NotTheSafes -> return DelaySkipQuoting.NotTheSafesDelay(reading.generation)
            is DelayQueueReading.Unavailable -> return DelaySkipQuoting.NotQuoted(
                if (reading.reason == ReadUnavailable.SYNCING) {
                    CallRefusal.Syncing
                } else {
                    CallRefusal.Unavailable(reading.reason, QuoteFigure.RUN)
                },
            )
        }
        val queue = read.queue
        val createdAt = queue.headCreatedAt
        if (createdAt == null || !queue.headExpired(read.headTime)) return DelaySkipQuoting.NothingExpired
        val entries = (history.observe().first() as? HistoryList.Loaded)?.entries
            ?: return DelaySkipQuoting.HistoryUnreadable
        val now = clock()
        entries.firstOrNull {
            val skip = it.transfer.action as? SentAction.DelaySkip ?: return@firstOrNull false
            it.transfer.network == network && skip.delay == delay &&
                it.outcome == SentOutcome.NotSeen && it.transfer.sentAt >= now - RECORD_LIFETIME_SECONDS
        }?.let { return DelaySkipQuoting.SkipOnItsWay(it.transfer.hash) }
        quoter.heldBack(sender)?.let { return DelaySkipQuoting.NotQuoted(it) }
        val native = when (val balance = NativeRead.of(nativeBalances.observeBalance(network, sender).first())) {
            is NativeRead.Before -> balance
            is NativeRead.NotRead -> return DelaySkipQuoting.NotQuoted(balance.refusal)
        }
        // Every entry still waiting may have expired by the block that mines it: room to skip each.
        val headroom = (queue.queueNonce - queue.txNonce) * GAS_PER_ENTRY
        return when (
            val quoted = quoter.quote(sender, delay, UInt256.ZERO, SKIP_EXPIRED, level, native, extraGas = headroom)
        ) {
            is CallQuoting.Quoted -> DelaySkipQuoting.Quoted(
                DelaySkipQuote(
                    network,
                    safe,
                    delay,
                    quoted.quote,
                    createdAt,
                    read.headTime,
                    waiting = queue.queueNonce - queue.txNonce,
                ),
            )
            is CallQuoting.Refused -> DelaySkipQuoting.NotQuoted(quoted.refusal)
        }
    }

    private companion object {
        /** A hash no call has: the queue read for its head alone. */
        val NO_CALL = Digest.of("00".repeat(32))

        /**
         * Gas for one more entry skipped: the loop's cold reads of `txCreatedAt(n)` and its check
         * (2,100 + 2,100 + the arithmetic), rounded up, and `txNonce`'s write already in the estimate.
         */
        const val GAS_PER_ENTRY = 5_000L
    }
}

/**
 * Signs a reviewed [DelaySkipQuote]'s transaction with the sender's key as [unlock] opens it, and
 * hands it to the network: recorded as the skip it is ([SentAction.DelaySkip]). [clock] is in unix
 * seconds.
 */
class SendDelaySkip(
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(quote: DelaySkipQuote, unlock: Unlock): TransferSending =
        signAndSend(signer, broadcaster, sentTransfers, quote.network, quote.call, quote.action, clock, unlock)
}
