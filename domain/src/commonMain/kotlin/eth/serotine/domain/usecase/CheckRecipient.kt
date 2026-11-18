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
 * What the pre-send check finds about a recipient (address poisoning protection; owner's request,
 * 2026-11-18, on #324). Only [Blocked] stops a send; [NewRecipient] is a notice; [Familiar] says
 * nothing.
 */
sealed interface RecipientCheck {

    /** This device's history records a transfer to this address, or it is one of the wallet's accounts. */
    data object Familiar : RecipientCheck

    /**
     * This device's history records no transfer to this address: a non-blocking notice. [historyRead]
     * is false while the account's token transfers are not read from the log index yet, so that a
     * sender who planted this address cannot be ruled out.
     */
    data class NewRecipient(val historyRead: Boolean = true) : RecipientCheck

    /** The send must not go ahead as it stands. */
    sealed interface Blocked : RecipientCheck {

        /** Whether the user may choose to send anyway, after comparing the address. */
        val overridable: Boolean
    }

    /**
     * The recipient starts and ends like [known], an address this device sent to (or an account of
     * the wallet), yet differs from it in the middle: the signature of a planted look-alike. Never
     * overridden: the user changes the recipient.
     */
    data class Lookalike(val recipient: Address, val known: Address) : Blocked {
        override val overridable: Boolean
            get() = false
    }

    /**
     * A transfer between the account and the recipient that this device never signed is in the
     * account's history, of the [kind] that plants a look-alike: a transfer of nothing (either way:
     * `transferFrom(account, recipient, 0)` needs no allowance), an unlisted token's claim, or dust
     * received. The user may send anyway: a real counterparty can have sent an unlisted token.
     */
    data class PlantedSender(val recipient: Address, val kind: PlantKind) : Blocked {
        override val overridable: Boolean
            get() = true
    }

    /**
     * This device's history of what it sent cannot be read, so the recipient cannot be compared with
     * the addresses it sent to. The user may send anyway, having compared it themselves.
     */
    data object SentToUnreadable : Blocked {
        override val overridable: Boolean
            get() = true
    }
}

/** How an address planted itself in the history. */
enum class PlantKind { ZERO_VALUE, DUST, UNLISTED_TOKEN }

/**
 * The pre-send check, over what the wallet knows. [dustWei] is the amount of the network's currency
 * at or below which an arrival is dust.
 */
class CheckRecipient(
    private val dustWei: UInt256 = DEFAULT_DUST_WEI,
) {
    /**
     * The finding for sending from [account] to [recipient]: look-alikes first, then the familiar,
     * then planted senders, then an unreadable history, then the notice.
     *
     * [sentTo] are the addresses this device sent to (`ObserveSentTo`: transactions it signed, so
     * their `tx.from` is the user), or null while that history cannot be read. [ownAddresses] are
     * the wallet's accounts on the network (its address book for now: the wallet has no other).
     * [transfers] are the account's token transfers from the log index, both ways; [listed] the
     * ERC-20 tokens the wallet lists, by contract address, never by name or symbol. ERC-721
     * transfers are not judged: the built-in collections and NFTs are listed apart.
     */
    fun check(
        account: Address,
        recipient: Address,
        sentTo: Set<Address>?,
        ownAddresses: Set<Address>,
        transfers: List<TokenTransfer>,
        listed: Set<Address>,
        historyRead: Boolean = true,
    ): RecipientCheck {
        val known = sentTo.orEmpty() + ownAddresses
        if (recipient in known) return RecipientCheck.Familiar
        known.firstOrNull { AddressLookalikes.lookAlike(recipient, it) }
            ?.let { return RecipientCheck.Lookalike(recipient, it) }
        planted(account, recipient, transfers, listed)?.let { return RecipientCheck.PlantedSender(recipient, it) }
        if (sentTo == null) return RecipientCheck.SentToUnreadable
        return RecipientCheck.NewRecipient(historyRead)
    }

    /**
     * How [recipient] planted itself in [account]'s history, if it did. The recipient is not one this
     * device sent to, so no transfer from the account to it was signed here: one the log index holds
     * is a contract's claim.
     */
    private fun planted(
        account: Address,
        recipient: Address,
        transfers: List<TokenTransfer>,
        listed: Set<Address>,
    ): PlantKind? {
        val between = transfers.filterIsInstance<TokenTransfer.Fungible>().filter {
            it.from == recipient && it.to == account || it.from == account && it.to == recipient
        }
        return when {
            between.any { it.amount.isZero } -> PlantKind.ZERO_VALUE
            between.any { !it.isEth && it.token !in listed } -> PlantKind.UNLISTED_TOKEN
            between.any { it.isEth && it.to == account && it.amount <= dustWei } -> PlantKind.DUST
            else -> null
        }
    }

    companion object {
        /** 0.00001 of the network's currency, in wei. */
        val DEFAULT_DUST_WEI: UInt256 = UInt256.of(10_000_000_000_000L)
    }
}

/**
 * [CheckRecipient] kept current for the account [invoke] is asked about: what this device sent to
 * (`ObserveSentTo`, unreadable included), the wallet's accounts on the account's network, the
 * account's token transfers both ways (the log index's, [ObserveTokenTransfers]) and the ERC-20
 * tokens the wallet lists: the built-in ones their contracts do not contradict, and the ones the
 * user added that do not take a built-in token's symbol, as Activity lists them (docs/wallet-ux.md,
 * 25). Until the account's transfers are read, its first answer is [RecipientCheck.NewRecipient]
 * with `historyRead` false: a log index that is never read must not keep every send blocked.
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
        val read = history as? IndexedHistory.Read
        val listed = known.tokens
            .filter {
                when (it.origin) {
                    TokenOrigin.BUILT_IN -> it.check !is BuiltInCheck.Disagrees
                    TokenOrigin.ADDED -> !it.sharesBuiltInSymbol
                }
            }
            .mapTo(HashSet()) { it.token.address }
        check.check(
            account = account.address,
            recipient = recipient,
            sentTo = sent.addresses.takeIf { sent.readable },
            ownAddresses = own,
            transfers = read?.events.orEmpty().map { it.transfer },
            listed = listed,
            historyRead = read != null,
        )
    }.distinctUntilChanged()
}
