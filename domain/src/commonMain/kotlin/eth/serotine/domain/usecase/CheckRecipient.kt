package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressLookalikes
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AccountList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/**
 * What the pre-send check finds about a recipient (address poisoning protection). Only [Blocked]
 * stops a send; [NewRecipient] is a notice; the rest say nothing.
 */
sealed interface RecipientCheck {

    /** The user sent to this address before, or it is one of the wallet's accounts. */
    data object Familiar : RecipientCheck

    /**
     * Nothing was sent here before: a non-blocking notice. [historyRead] is false while the account's
     * incoming transfers are not read yet, so that a sender who planted this address cannot be ruled out.
     */
    data class NewRecipient(val historyRead: Boolean = true) : RecipientCheck

    /** The send must not go ahead as it stands. */
    sealed interface Blocked : RecipientCheck

    /**
     * The recipient starts and ends like [known], an address the user really sent to (or an
     * account of the wallet), yet differs from it in the middle: the signature of a planted
     * look-alike.
     */
    data class Lookalike(val recipient: Address, val known: Address) : Blocked

    /**
     * The recipient once sent this wallet a transfer of nothing, dust or an unlisted token's
     * [kind], which is how a look-alike address is planted in a history.
     */
    data class PlantedSender(val recipient: Address, val kind: PlantKind) : Blocked
}

/** How an address planted itself in the history. */
enum class PlantKind { ZERO_VALUE, DUST, UNLISTED_TOKEN }

/**
 * The pre-send check (blocking), over what the wallet knows. [sentTo] are the addresses the user
 * actually sent to (`ObserveSentTo`: transactions this wallet sent, so their `tx.from` is the
 * user), [ownAddresses] the wallet's accounts (its address book for now: the wallet has no other),
 * [incoming] the token transfers that reached the user, [listed] the tokens on the allowlist
 * (matched by contract address, never by name or symbol), and [dustWei] the amount of the network's
 * currency at or below which an arrival is dust.
 */
class CheckRecipient(
    private val dustWei: UInt256 = DEFAULT_DUST_WEI,
) {
    /** The finding for [recipient]; look-alikes before planted senders before the notice. */
    fun check(
        recipient: Address,
        sentTo: Set<Address>,
        ownAddresses: Set<Address>,
        incoming: List<TokenTransfer>,
        listed: Set<Address>,
        historyRead: Boolean = true,
    ): RecipientCheck {
        val known = sentTo + ownAddresses
        // A look-alike of a known address, though never the known address itself.
        if (recipient !in known) {
            known.firstOrNull { AddressLookalikes.lookAlike(recipient, it) }
                ?.let { return RecipientCheck.Lookalike(recipient, it) }
        }
        if (recipient in known) return RecipientCheck.Familiar
        planted(recipient, incoming, listed)?.let { return RecipientCheck.PlantedSender(recipient, it) }
        return RecipientCheck.NewRecipient(historyRead)
    }

    private fun planted(recipient: Address, incoming: List<TokenTransfer>, listed: Set<Address>): PlantKind? {
        val theirs = incoming.filter { it.from == recipient }
        return when {
            theirs.any { it is TokenTransfer.Fungible && it.amount.isZero } -> PlantKind.ZERO_VALUE
            theirs.any { !it.isEth && it.token !in listed } -> PlantKind.UNLISTED_TOKEN
            theirs.any { it is TokenTransfer.Fungible && it.isEth && it.amount <= dustWei } -> PlantKind.DUST
            else -> null
        }
    }

    companion object {
        /** 0.00001 of the network's currency, in wei. */
        val DEFAULT_DUST_WEI: UInt256 = UInt256.of(10_000_000_000_000L)
    }
}

/**
 * [CheckRecipient] kept current for the account [invoke] is asked about: what the user really sent
 * to (`ObserveSentTo`), the wallet's accounts, the transfers that reached the account (the log
 * index's, [ObserveTokenTransfers]) and the tokens on the allowlist, which are the built-in ones
 * their contracts agree with (by contract address, never by name or symbol). While the account's
 * transfers are not read yet, no planted sender is known: look-alikes and the notice still apply.
 */
class ObserveRecipientCheck(
    private val sentTo: ObserveSentTo,
    private val accounts: ObserveAccounts,
    private val transfers: ObserveTokenTransfers,
    private val tokens: ObserveKnownTokens,
    private val check: CheckRecipient = CheckRecipient(),
) {
    operator fun invoke(account: AccountId, recipient: Address): Flow<RecipientCheck> = combine(
        sentTo(),
        accounts(),
        transfers(account).onStart { emit(IndexedHistory.NotFollowed) },
        tokens(account.network),
    ) { sent, list, history, known ->
        val own = (list as? AccountList.Loaded)?.accounts.orEmpty()
            .filter { it.network == account.network }
            .mapTo(HashSet()) { it.address }
        val incoming = (history as? IndexedHistory.Read)?.events.orEmpty()
            .map { it.transfer }
            .filter { it.to == account.address && it.from != account.address }
        val listed = known.tokens
            .filter { it.origin == TokenOrigin.BUILT_IN && it.check !is BuiltInCheck.Disagrees }
            .mapTo(HashSet()) { it.token.address }
        check.check(recipient, sent.addresses, own, incoming, listed, historyRead = history is IndexedHistory.Read)
    }.distinctUntilChanged()
}
