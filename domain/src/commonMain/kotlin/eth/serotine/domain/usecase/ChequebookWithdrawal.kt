package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Chequebook
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SwarmCalls
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.ChequebookFinding
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.SwarmContracts
import eth.serotine.domain.repository.SwarmReading
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

// A Swarm node's xBZZ withdrawn from its chequebook (docs/swarm.md, *Withdraw from the
// chequebook*; owner's decisions, 2026-11-23): `withdraw(amount)`, which only the chequebook's
// issuer, the node, may send, and which always pays the node itself. The node's own key signs,
// held here on Gnosis Chain; up to the liquid balance, read fresh. Strict in both modes.

/**
 * A withdrawal quoted for review: [amount] of [token] (xBZZ) from the node's [chequebook] as read
 * fresh at block [readAt], and the [quote] of the node's transaction. Only this file makes one:
 * what is signed is its [quote]'s transaction.
 */
@ConsistentCopyVisibility
data class WithdrawalQuote internal constructor(
    val network: Network,
    val token: Token,
    val chequebook: Chequebook,
    val amount: UInt256,
    val readAt: Long,
    val quote: CallQuote,
) {
    /** What the sender's transaction is recorded as. */
    val action: SentAction
        get() = SentAction.ChequebookWithdrawal(token, chequebook.address, amount)
}

/** What a withdrawal's quote gives. */
sealed interface WithdrawalQuoting {
    /** [quote] is ready for review. */
    data class Quoted(val quote: WithdrawalQuote) : WithdrawalQuoting

    /** Not quoted: [refusal]. */
    data class Refused(val refusal: WithdrawalRefusal) : WithdrawalQuoting
}

/** Why a withdrawal is not quoted. */
sealed interface WithdrawalRefusal {
    /** No Swarm contracts are pinned on Gnosis Chain in this release. */
    data object NotPinned : WithdrawalRefusal

    /** The contract is not the node's chequebook: [finding] says what it is. */
    data class NotTheNodes(val finding: ChequebookFinding) : WithdrawalRefusal

    /** A withdrawal of nothing: it would move nothing and cost a fee. */
    data object NothingToWithdraw : WithdrawalRefusal

    /** More than the chequebook's liquid balance, [free], which is all it lets the issuer take. */
    data class MoreThanFree(val free: UInt256) : WithdrawalRefusal

    /** No verified answer can be had right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : WithdrawalRefusal

    /** The node's transaction was not quoted: [refusal]. */
    data class NotQuoted(val refusal: CallRefusal) : WithdrawalRefusal
}

/**
 * Quotes the withdrawal of `amount` PLUR from the node at `node`'s chequebook at `chequebook` on
 * Gnosis Chain (docs/swarm.md, *Withdraw from the chequebook*): the node's own transaction, so its
 * key signs, held here. The chequebook is read fresh, all at one block: proven Bee's, the node's,
 * and holding at least `amount` liquid beside what the node's withdrawals from it sent from here
 * and not mined yet still take. Run as a verified call, the withdrawal must not revert.
 * [clock] is in unix seconds.
 */
class QuoteChequebookWithdrawal(
    private val contracts: SwarmContracts,
    private val inputs: TransactionInputs,
    private val nativeBalances: NativeBalances,
    private val sentTransfers: SentTransfers,
    outbox: Outbox,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    private val network = SWARM_NETWORK
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock, outbox)

    suspend operator fun invoke(
        node: Address,
        chequebook: Address,
        amount: UInt256,
        level: FeeLevel,
    ): WithdrawalQuoting {
        if (amount.isZero) return WithdrawalQuoting.Refused(WithdrawalRefusal.NothingToWithdraw)
        val pinned = contracts.deployment(network) ?: return WithdrawalQuoting.Refused(WithdrawalRefusal.NotPinned)
        val (book, block) = when (val read = nodesChequebook(contracts, network, node, chequebook)) {
            is Read.Node -> read.chequebook to read.blockNumber
            is Read.Refused -> return WithdrawalQuoting.Refused(read.refusal)
        }
        val free = when (val left = freeOf(book, network, sentTransfers, inputs, node, clock)) {
            is Free.Read -> left.amount
            is Free.Unavailable -> return WithdrawalQuoting.Refused(WithdrawalRefusal.Unavailable(left.reason))
        }
        if (amount > free) return WithdrawalQuoting.Refused(WithdrawalRefusal.MoreThanFree(free))
        val native = when (val balance = NativeRead.of(nativeBalances.observeBalance(network, node).first())) {
            is NativeRead.Before -> balance
            is NativeRead.NotRead -> return WithdrawalQuoting.Refused(WithdrawalRefusal.NotQuoted(balance.refusal))
        }
        val data = SwarmCalls.withdraw(amount)
        return when (val quoted = quoter.quote(node, chequebook, UInt256.ZERO, data, level, native)) {
            is CallQuoting.Quoted ->
                WithdrawalQuoting.Quoted(WithdrawalQuote(network, pinned.bzz, book, amount, block, quoted.quote))
            is CallQuoting.Refused -> WithdrawalQuoting.Refused(WithdrawalRefusal.NotQuoted(quoted.refusal))
        }
    }
}

/** What [SendChequebookWithdrawal] did. */
sealed interface WithdrawalSending {
    /** Signed and handed on as [sending] says. */
    data class Sending(val sending: TransferSending) : WithdrawalSending

    /**
     * Not signed: a transaction not sent from here took the nonce the quote is for since it was
     * made (a Bee node signing with the same key, docs/swarm.md, *A key that Bee also uses*). The
     * review is made again.
     */
    data object NonceTaken : WithdrawalSending

    /** What it is checked on could not be verified right now ([reason]): nothing is signed. */
    data class NotChecked(val reason: ReadUnavailable) : WithdrawalSending

    /**
     * Not signed: the chequebook, read fresh, no longer holds the amount liquid (Bee cashed out or
     * a cheque was cashed meanwhile), or is no longer the node's. The review is made again.
     */
    data object Changed : WithdrawalSending
}

/**
 * Signs a reviewed [WithdrawalQuote]'s transaction with the node's key as [unlock] opens it, and
 * hands it to the network, recorded as the withdrawal it is. Before it signs, the chequebook is
 * read fresh again and must still be the node's with the amount liquid, and the node's verified
 * count of mined transactions must not have moved past the quote's nonce (docs/swarm.md, *A key
 * that Bee also uses*). [clock] is in unix seconds.
 */
class SendChequebookWithdrawal(
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val inputs: TransactionInputs,
    private val contracts: SwarmContracts,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(quote: WithdrawalQuote, unlock: Unlock): WithdrawalSending {
        val node = quote.quote.from
        when (val read = nodesChequebook(contracts, quote.network, node, quote.chequebook.address)) {
            is Read.Node -> when (
                val left = freeOf(read.chequebook, quote.network, sentTransfers, inputs, node, clock)
            ) {
                is Free.Read -> if (left.amount < quote.amount) return WithdrawalSending.Changed
                is Free.Unavailable -> return WithdrawalSending.NotChecked(left.reason)
            }
            is Read.Refused -> return when (val refusal = read.refusal) {
                is WithdrawalRefusal.Unavailable -> WithdrawalSending.NotChecked(refusal.reason)
                WithdrawalRefusal.NotPinned -> WithdrawalSending.NotChecked(ReadUnavailable.NO_ENGINE)
                else -> WithdrawalSending.Changed
            }
        }
        when (val mined = inputs.minedNonce(quote.network, node)) {
            is NonceReading.Read -> if (mined.nonce > quote.quote.transaction.nonce) return WithdrawalSending.NonceTaken
            is NonceReading.Unavailable -> return WithdrawalSending.NotChecked(mined.reason)
        }
        return WithdrawalSending.Sending(
            signAndSend(signer, broadcaster, sentTransfers, quote.network, quote.quote, quote.action, clock, unlock),
        )
    }
}

/** What is free to withdraw from a chequebook now, or why that cannot be told. */
private sealed interface Free {
    data class Read(val amount: UInt256) : Free
    data class Unavailable(val reason: ReadUnavailable) : Free
}

/**
 * What [book] lets its issuer [node] withdraw now: its liquid balance, less what the node's own
 * withdrawals from it, sent from here and not mined yet, still take of it.
 */
private suspend fun freeOf(
    book: Chequebook,
    network: Network,
    sentTransfers: SentTransfers,
    inputs: TransactionInputs,
    node: Address,
    clock: () -> Long,
): Free {
    val unmined = when (val read = unminedOf(network, sentTransfers, inputs, node, clock)) {
        is Unmined.Read -> read.transfers
        is Unmined.Unavailable -> return Free.Unavailable(read.reason)
    }
    var pending = UInt256.ZERO
    unmined.mapNotNull { (it.action as? SentAction.ChequebookWithdrawal)?.takeIf { w -> w.chequebook == book.address } }
        .forEach { pending = pending.plusOrNull(it.amount) ?: return Free.Read(UInt256.ZERO) }
    return Free.Read(book.liquidBalance.minusOrNull(pending) ?: UInt256.ZERO)
}

/** A chequebook read fresh for a withdrawal: the node's, or why not. */
private sealed interface Read {
    data class Node(val chequebook: Chequebook, val blockNumber: Long) : Read
    data class Refused(val refusal: WithdrawalRefusal) : Read
}

private suspend fun nodesChequebook(
    contracts: SwarmContracts,
    network: Network,
    node: Address,
    chequebook: Address,
): Read = when (val reading = contracts.chequebook(network, node, chequebook)) {
    is SwarmReading.Verified -> when (val finding = reading.finding) {
        is ChequebookFinding.Node -> Read.Node(finding.chequebook, reading.blockNumber)
        else -> Read.Refused(WithdrawalRefusal.NotTheNodes(finding))
    }
    SwarmReading.NotPinned -> Read.Refused(WithdrawalRefusal.NotPinned)
    SwarmReading.Syncing -> Read.Refused(WithdrawalRefusal.Unavailable(ReadUnavailable.SYNCING))
    is SwarmReading.Unavailable -> Read.Refused(WithdrawalRefusal.Unavailable(reading.reason))
}

/**
 * The node at `node`'s chequebook at `chequebook` on Gnosis Chain, as [SwarmContracts.observeChequebook]
 * proves it: what the withdrawal's form is filled in on. A quote reads it fresh again, never this reading.
 */
class ObserveChequebook(private val contracts: SwarmContracts) {
    operator fun invoke(node: Address, chequebook: Address): Flow<SwarmReading<ChequebookFinding>> =
        contracts.observeChequebook(SWARM_NETWORK, node, chequebook)
}
