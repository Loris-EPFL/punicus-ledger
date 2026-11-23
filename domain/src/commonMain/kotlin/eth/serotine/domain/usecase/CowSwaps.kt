package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowDeployment
import eth.serotine.domain.model.CowMinimum
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.GPv2Settlement
import eth.serotine.domain.model.GPv2Signing
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CowApi
import eth.serotine.domain.repository.CowChain
import eth.serotine.domain.repository.CowOrderList
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.CowOrders
import eth.serotine.domain.repository.CowQuoteReading
import eth.serotine.domain.repository.CowRefusal
import eth.serotine.domain.repository.CowUnavailable
import eth.serotine.domain.repository.CowVenueReading
import eth.serotine.domain.repository.FinalizedBlock
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.HistoryStartList
import eth.serotine.domain.repository.HistoryStarts
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first

/**
 * How long a CoW order is valid: five minutes past the verified head's time it was quoted at
 * (owner ruling, 2026-10-22, docs/swaps.md decision 4).
 */
const val COW_ORDER_VALIDITY_SECONDS: Long = 5 * 60

/**
 * How old a CoW quote may be, by the verified head's clock, at the tap that signs it: a minute, as
 * for every quote (docs/swaps.md, *What both venues share*, freshness).
 */
const val QUOTE_MAX_AGE_SECONDS: Long = 60

/**
 * A CoW swap from an account this wallet holds, quoted for review (docs/swaps.md, *CoW
 * Protocol*): [order], selling exactly [amountIn] of [sell] for at least [minimumOut] of [buy],
 * paid to [from], on [deployment]; [digest] is what [from] signs and [uid] the order's UID.
 *
 * - [estimate] is CoW's own estimate of what the order buys, net of [cowFee] (of the sold token)
 *   that CoW takes out of the trade: not verified. The order's minimum is that estimate less
 *   [tolerance] ([CowMinimum]): it rests on CoW's word, and no other venue is read for it (owner
 *   ruling, 2026-11-23).
 * - The sold token's verified balance [sellBefore] (at [tokenBalanceBlock]), what this wallet's
 *   own transactions not mined yet still move of it ([pendingSell]) and what is left after the
 *   order ([sellAfter]).
 * - [headTimestamp] is the verified head's time the order's `validTo` counts from, and
 *   [rangeStart] the block finalized then, from which its end is proven.
 *
 * Only [QuoteCowSwap] makes one, and nothing copies it: what [PlaceCowOrder] signs is [digest].
 */
@ConsistentCopyVisibility
data class CowSwapQuote internal constructor(
    val from: Address,
    val network: Network,
    val sell: TransferAsset,
    val buy: TransferAsset,
    val amountIn: UInt256,
    val tolerance: SlippageTolerance,
    val deployment: CowDeployment,
    val order: CowOrder,
    val digest: Digest,
    val uid: CowOrderUid,
    val estimate: UInt256,
    val cowFee: UInt256,
    val sellBefore: UInt256,
    val pendingSell: UInt256,
    val sellAfter: UInt256,
    val tokenBalanceBlock: Long?,
    val headTimestamp: Long,
    val rangeStart: Long,
) {
    /** The least the order buys, as signed. */
    val minimumOut: UInt256
        get() = order.buyAmount
}

/**
 * Quotes a CoW swap from an account this wallet holds (docs/swaps.md, *CoW Protocol*), the venue
 * the user chose (*The user's choice of venue*). Everything that can refuse it on verified state
 * is read first, side by side, and only then is CoW's API asked, so that a swap refused here never
 * reaches CoW (CLAUDE.md, *Data sources*: CoW learns only the swaps sent to it). The order is built
 * here, never taken from CoW, and its domain separator is checked against the settlement
 * contract's own before it is offered. Its minimum is CoW's estimate less the tolerance: no other
 * venue is quoted for it (owner ruling, 2026-11-23).
 *
 * CoW is not offered ([CowSwapQuoting.NotOffered]) for the network's own currency sold, or an
 * account whose history the log index follows only from after the block finalized now (its
 * order's end could not be proven). One CoW order per sold token is live at a time: two would draw on the one
 * allowance of the vault relayer. What this wallet's own transactions not mined yet move of the
 * token is not the order's to sell. On [network] only. [clock] is in unix seconds.
 */
class QuoteCowSwap(
    private val cowChain: CowChain,
    private val cowApi: CowApi,
    private val inputs: TransactionInputs,
    private val tokenBalances: TokenBalances,
    private val sentTransfers: SentTransfers,
    private val cowOrders: CowOrders,
    private val historyStarts: HistoryStarts,
    private val lightClient: LightClient,
    private val network: Network,
    /** What the account has queued in lenient mode, which holds a new quote back (docs/lenient-mode.md). */
    private val outbox: Outbox,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock, outbox)

    /** Exactly [amountIn] of [sell] for [buy] from [from], at [tolerance]. */
    suspend operator fun invoke(
        from: Address,
        sell: TransferAsset,
        buy: TransferAsset,
        amountIn: UInt256,
        tolerance: SlippageTolerance,
    ): CowSwapQuoting = cancellingReads {
        if (amountIn.isZero) return@cancellingReads CowSwapQuoting.NothingToSwap
        if (!lightClient.datesFinalizedBlocks) {
            return@cancellingReads CowSwapQuoting.NotOffered(CowNotOffered.NOT_ON_THIS_DEVICE)
        }
        val soldToken = (sell as? TransferAsset.Erc20Token)?.token
            ?: return@cancellingReads CowSwapQuoting.NotOffered(CowNotOffered.SELLS_ETHER)
        val buyToken = (buy as? TransferAsset.Erc20Token)?.token?.address ?: CowOrder.NATIVE_BUY_TOKEN
        if (buyToken == soldToken.address) return@cancellingReads CowSwapQuoting.NotASwap
        // An earlier transaction maybe still on its way: refused before any other read, as a Uniswap
        // swap is. One that sells the same token could be mined beside this order.
        quoter.heldBack(from)?.let { return@cancellingReads it.asCowSwapQuoting() }

        val balance = async { tokenBalances.observeBalance(network, from, soldToken.address).first() }
        val head = async { lightClient.observeStatus(network).first() }
        val starts = async { historyStarts.observe().first() }
        val orders = async { cowOrders.observe().first() }
        val records = async { unminedOf(network, sentTransfers, inputs, from, clock) }
        val deployment = when (val venue = cowChain.venue(network)) {
            is CowVenueReading.Verified -> venue.deployment
            CowVenueReading.NotPinned -> return@cancellingReads CowSwapQuoting.NoVenue(contract = null)
            is CowVenueReading.CodeMismatch -> return@cancellingReads CowSwapQuoting.NoVenue(venue.contract)
            is CowVenueReading.Unavailable -> return@cancellingReads CowSwapQuoting.Unavailable(venue.reason)
        }
        // The wrapped currency sold for the currency itself is an unwrap, one for one, never an order.
        if (buy == TransferAsset.Native && soldToken.address == deployment.wrappedNative) {
            return@cancellingReads CowSwapQuoting.NotASwap
        }

        val synced = head.await() as? LightClientStatus.Synced ?: return@cancellingReads CowSwapQuoting.Syncing
        // A head or a finalized block whose time is not known gives no validity, nor a first
        // block to prove the order from.
        val headTimestamp = synced.headTimestamp
        val finalized = synced.finalized
        if (headTimestamp == null || finalized == null) {
            return@cancellingReads CowSwapQuoting.Unavailable(ReadUnavailable.UNPROVEN)
        }
        when (val read = starts.await()) {
            is HistoryStartList.Loaded -> if (!followedBy(read, AccountId(network, from), finalized)) {
                return@cancellingReads CowSwapQuoting.NotOffered(CowNotOffered.NOT_FOLLOWED_YET)
            }
            HistoryStartList.Unreadable -> return@cancellingReads CowSwapQuoting.HistoryUnreadable
        }
        val validTo = headTimestamp + COW_ORDER_VALIDITY_SECONDS
        if (validTo > CowOrder.UINT32_MAX) return@cancellingReads CowSwapQuoting.Unavailable(ReadUnavailable.UNPROVEN)
        when (val read = orders.await()) {
            is CowOrderList.Loaded -> read.orders
                .filter { it.account == from && it.order.sellToken == soldToken.address }
                .lastOrNull { it.canStillTake(finalized) }
                ?.let { return@cancellingReads CowSwapQuoting.EarlierOrderLive(it.uid) }
            CowOrderList.Unreadable -> return@cancellingReads CowSwapQuoting.OrdersUnreadable
        }
        val reading = balance.await()
        val sellBefore = when (reading) {
            is TokenBalanceReading.Verified -> UInt256.parse(reading.units.decimal)
                ?: return@cancellingReads CowSwapQuoting.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.BALANCE)
            TokenBalanceReading.Syncing, is TokenBalanceReading.LastKnown ->
                return@cancellingReads CowSwapQuoting.Syncing
            TokenBalanceReading.NotAToken -> return@cancellingReads CowSwapQuoting.NotAToken
            is TokenBalanceReading.Unavailable ->
                return@cancellingReads CowSwapQuoting.Unavailable(reading.reason, QuoteFigure.BALANCE)
        }
        val earlier = when (val read = records.await()) {
            is Unmined.Read -> read.transfers
            is Unmined.Unavailable -> return@cancellingReads CowSwapQuoting.Unavailable(read.reason, QuoteFigure.NONCE)
        }
        earlier.lastOrNull { it.approves(soldToken, deployment.vaultRelayer) }
            ?.let { return@cancellingReads CowSwapQuoting.ApprovalPending(it.hash) }
        val pendingSell = earlier.mapNotNull { it.tokenAmount(soldToken.address) }.sumOrNull()
            ?: return@cancellingReads CowSwapQuoting.InsufficientToken
        val sellAvailable = sellBefore.minusOrNull(pendingSell)?.takeIf { it >= amountIn }
            ?: return@cancellingReads CowSwapQuoting.InsufficientToken

        val allowance = when (val read = readAllowance(network, inputs, from, soldToken, deployment.vaultRelayer)) {
            is AllowanceRead.Read -> read.allowance
            AllowanceRead.NotAToken -> return@cancellingReads CowSwapQuoting.NotAToken
            AllowanceRead.Refused -> return@cancellingReads CowSwapQuoting.Refused
            is AllowanceRead.Unavailable ->
                return@cancellingReads CowSwapQuoting.Unavailable(read.reason, QuoteFigure.ALLOWANCE)
        }
        if (allowance < amountIn) {
            return@cancellingReads CowSwapQuoting.NeedsApproval(soldToken, deployment.vaultRelayer, allowance)
        }
        when (val check = domainSeparatorHolds(deployment)) {
            DomainCheck.Holds -> Unit
            DomainCheck.Differs -> return@cancellingReads CowSwapQuoting.NoVenue(deployment.settlement)
            is DomainCheck.Unavailable -> return@cancellingReads CowSwapQuoting.Unavailable(check.reason)
        }

        // Only now does anything reach CoW.
        val quoted = cowApi.quote(network, soldToken.address, buyToken, amountIn, from)
        val (estimate, cowFee) = when (quoted) {
            is CowQuoteReading.Quoted -> quoted.buyAmount to quoted.feeAmount
            is CowQuoteReading.Refused -> return@cancellingReads CowSwapQuoting.CowRefused(quoted.why, quoted.errorType)
            is CowQuoteReading.Unavailable -> return@cancellingReads CowSwapQuoting.CowNoAnswer(quoted.why)
        }
        val buyAmount = CowMinimum.buyAmount(estimate, tolerance)
        if (buyAmount.isZero) return@cancellingReads CowSwapQuoting.TooSmall
        val order = CowOrder(
            sellToken = soldToken.address,
            buyToken = buyToken,
            receiver = from,
            sellAmount = amountIn,
            buyAmount = buyAmount,
            validTo = validTo,
        )
        val digest = order.digest(network.chainId, deployment.settlement)
        CowSwapQuoting.Quoted(
            CowSwapQuote(
                from = from,
                network = network,
                sell = sell,
                buy = buy,
                amountIn = amountIn,
                tolerance = tolerance,
                deployment = deployment,
                order = order,
                digest = digest,
                uid = CowOrderUid.of(digest, from, validTo),
                estimate = estimate,
                cowFee = cowFee,
                sellBefore = sellBefore,
                pendingSell = pendingSell,
                sellAfter = sellAvailable - amountIn,
                tokenBalanceBlock = (reading as TokenBalanceReading.Verified).blockNumber,
                headTimestamp = headTimestamp,
                rangeStart = finalized.number,
            ),
        )
    }

    /** Whether the settlement contract's own `domainSeparator()` is the one computed here. */
    private suspend fun domainSeparatorHolds(deployment: CowDeployment): DomainCheck =
        when (val run = cowChain.call(network, deployment.settlement, GPv2Settlement.domainSeparator())) {
            is Simulation.Succeeded -> {
                val onChain = GPv2Settlement.domainSeparatorOf(run.returnHex)
                val here = GPv2Signing.domainSeparator(network.chainId, deployment.settlement)
                if (onChain != null && onChain.contentEquals(here)) DomainCheck.Holds else DomainCheck.Differs
            }
            // The pinned settlement's getter does not revert: what did is no answer of it.
            is Simulation.Reverted -> DomainCheck.Differs
            Simulation.Refused -> DomainCheck.Unavailable(ReadUnavailable.REFUSED)
            is Simulation.Unavailable -> DomainCheck.Unavailable(run.reason)
        }

    private sealed interface DomainCheck {
        data object Holds : DomainCheck
        data object Differs : DomainCheck
        data class Unavailable(val reason: ReadUnavailable) : DomainCheck
    }
}

/**
 * Whether the log index follows [account] from [finalized] or earlier ([starts], its history's
 * start): its order's range, which starts there, must not reach below the account's entry
 * (docs/swaps.md, *Proving an order's end*). Not yet when the start is only noted.
 */
private fun followedBy(starts: HistoryStartList.Loaded, account: AccountId, finalized: FinalizedBlock): Boolean {
    val start = (starts.starts[account] as? HistoryStart.Fixed)?.block
    return start != null && start <= finalized.number
}

/** This refusal, before any read, as the CoW swap's. */
private fun CallRefusal.asCowSwapQuoting(): CowSwapQuoting = when (this) {
    is CallRefusal.EarlierMaybeSent -> CowSwapQuoting.EarlierMaybeSent(hash, queued)
    is CallRefusal.Unavailable -> CowSwapQuoting.Unavailable(reason, figure)
    CallRefusal.Syncing -> CowSwapQuoting.Syncing
    CallRefusal.Refused -> CowSwapQuoting.Refused
    // heldBack answers none of these; mapped all the same.
    CallRefusal.InsufficientFunds, is CallRefusal.Infeasible, is CallRefusal.Reverts ->
        CowSwapQuoting.Unavailable(ReadUnavailable.UNPROVEN)
}

/** Why CoW is not offered for a swap (docs/swaps.md, *The user's choice of venue*). */
enum class CowNotOffered {
    /**
     * This device's light client gives no finalized block's own time, which proving an order's end
     * needs ([LightClient.datesFinalizedBlocks]): no order is offered here.
     */
    NOT_ON_THIS_DEVICE,

    /** The network's own currency is sold: that takes CoW's ETH-flow contract, not pinned yet. */
    SELLS_ETHER,

    /**
     * The log index follows the account only from after the block finalized now (an account added
     * minutes ago): the order's end could not be proven.
     */
    NOT_FOLLOWED_YET,
}

/** The outcome of [QuoteCowSwap]. */
sealed interface CowSwapQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: CowSwapQuote) : CowSwapQuoting

    /** The amount is zero: there is nothing to swap. */
    data object NothingToSwap : CowSwapQuoting

    /** The two assets are one token: the same one, or ETH and WETH. */
    data object NotASwap : CowSwapQuoting

    /** CoW is not offered for this swap, for [why]; Uniswap may be. */
    data class NotOffered(val why: CowNotOffered) : CowSwapQuoting

    /** Where the account's history starts cannot be read: whether its order could be proven is not known. */
    data object HistoryUnreadable : CowSwapQuoting

    /** An earlier transaction from the sender, [hash], may still be on its way: retried, not replaced. */
    data class EarlierMaybeSent(val hash: String, val queued: Boolean = false) : CowSwapQuoting

    /**
     * CoW is no venue here: no deployment is pinned for the network ([contract] null), or the
     * contract at [contract] is not what is pinned (its code, or the settlement's domain separator).
     */
    data class NoVenue(val contract: Address?) : CowSwapQuoting

    /** An order of the account selling the same token, [uid], may still fill: one at a time. */
    data class EarlierOrderLive(val uid: CowOrderUid) : CowSwapQuoting

    /** The record of signed orders cannot be read: no order is known not to be live. */
    data object OrdersUnreadable : CowSwapQuoting

    /**
     * The vault relayer may take only [allowance] of [token], less than the amount: an approval of
     * [spender] (the vault relayer) for the amount comes first, its own transaction
     * ([QuoteTokenApproval]).
     */
    data class NeedsApproval(val token: Token, val spender: Address, val allowance: UInt256) : CowSwapQuoting

    /** The approval this order needs, [hash], is sent and not mined yet: the order waits for its receipt. */
    data class ApprovalPending(val hash: String) : CowSwapQuoting

    /** The order's minimum rounds down to nothing: an order that may buy nothing is not signed. */
    data object TooSmall : CowSwapQuoting

    /** The sender holds less of the token than the amount, with what its own sends still move. */
    data object InsufficientToken : CowSwapQuoting

    /** The token's address answers as no token. */
    data object NotAToken : CowSwapQuoting

    /** The engine will not run a read on this build. */
    data object Refused : CowSwapQuoting

    /** The light client has no verified head yet. */
    data object Syncing : CowSwapQuoting

    /** A verified figure could not be had right now, for [reason]; [figure] says which (null: not said). */
    data class Unavailable(val reason: ReadUnavailable, val figure: QuoteFigure? = null) : CowSwapQuoting

    /** CoW refused to quote ([why]; [errorType], CoW's own word): the same swap may go to Uniswap. */
    data class CowRefused(val why: CowRefusal, val errorType: String) : CowSwapQuoting

    /** CoW gave no usable answer ([why]): the same swap may go to Uniswap. */
    data class CowNoAnswer(val why: CowUnavailable) : CowSwapQuoting
}

/**
 * Signs and places a quoted CoW order (docs/swaps.md, *An order, step by step*): the digest signed
 * by the account's key ([Signer]), the order recorded as attempted before anything is sent
 * ([CowOrders]), and only then handed to CoW. An order that could not be recorded is not sent, and
 * its signature goes with it: no POST was attempted, so nothing can fill it. Once recorded,
 * whatever CoW answers, the order is live until the chain proves its end ([ProveCowOrderEnds]).
 */
class PlaceCowOrder(
    private val signer: Signer,
    private val cowOrders: CowOrders,
    private val cowApi: CowApi,
    private val lightClient: LightClient,
) {
    /** [quote] signed with [unlock] and placed. */
    suspend operator fun invoke(quote: CowSwapQuote, unlock: Unlock): CowOrderPlacing {
        // Never signed stale: at most a minute old by the verified head's own clock, so that the
        // order keeps at least four of its five minutes.
        val synced = lightClient.observeStatus(quote.network).first() as? LightClientStatus.Synced
            ?: return CowOrderPlacing.QuoteStale
        val now = synced.headTimestamp ?: return CowOrderPlacing.QuoteStale
        if (now > quote.headTimestamp + QUOTE_MAX_AGE_SECONDS) return CowOrderPlacing.QuoteStale
        // One order per sold token, read again: another review may have placed one meanwhile.
        when (val read = cowOrders.observe().first()) {
            is CowOrderList.Loaded -> read.orders
                .filter { it.account == quote.from && it.order.sellToken == quote.order.sellToken }
                .lastOrNull { it.canStillTake(synced.finalized) }
                ?.let { return CowOrderPlacing.EarlierOrderLive(it.uid) }
            CowOrderList.Unreadable -> return CowOrderPlacing.OrdersUnreadable
        }
        val signature = when (val signed = signer.sign(quote.from, quote.digest, unlock)) {
            is Signing.Signed -> signed.signature
            else -> return CowOrderPlacing.NotSigned(signed)
        }
        val record = CowOrderRecord(
            account = quote.from,
            network = quote.network,
            settlement = quote.deployment.settlement,
            order = quote.order,
            uid = quote.uid,
            signature = signature,
            sell = quote.sell,
            buy = quote.buy,
            signedAt = quote.headTimestamp,
            rangeStart = quote.rangeStart,
        )
        if (!cowOrders.record(record)) return CowOrderPlacing.NotRecorded
        val placement = cowApi.place(quote.network, quote.order, quote.from, signature, quote.uid)
        val placed = record.copy(placement = placement)
        // The order is live already: a placement that could not be written is CoW's answer lost,
        // not the order, which stays recorded as attempted.
        cowOrders.record(placed)
        return CowOrderPlacing.Placed(placed)
    }
}

/** The outcome of [PlaceCowOrder]. */
sealed interface CowOrderPlacing {

    /**
     * Signed, recorded and handed to CoW: [record], with CoW's answer as its placement. Live until
     * its end is proven, whatever that answer was.
     */
    data class Placed(val record: CowOrderRecord) : CowOrderPlacing

    /** The quote is over a minute old by the verified head's clock, or the head's time is not known: quote again. */
    data object QuoteStale : CowOrderPlacing

    /** An order of the account selling the same token, [uid], may still fill: one at a time. */
    data class EarlierOrderLive(val uid: CowOrderUid) : CowOrderPlacing

    /** The record of signed orders cannot be read: no order is known not to be live. */
    data object OrdersUnreadable : CowOrderPlacing

    /** Not signed, for the reason [signing] gives: nothing left the device. */
    data class NotSigned(val signing: Signing) : CowOrderPlacing

    /** Signed, but the record could not be saved: the order was not sent, and nothing can fill it. */
    data object NotRecorded : CowOrderPlacing
}

/**
 * What the account's CoW orders on [network] may still take of the token at [token]: the sum of the
 * sell amounts of those that [CowOrderRecord.canStillTake] at [finalized], but for those the
 * settlement already counts as done at the head (docs/swaps.md, *A signed order is live until it is
 * proven dead*). Null when the sum does not fit a uint256.
 *
 * Done at the head: [cowChain]'s verified `filledAmount(uid)` reads the sell amount or more. The
 * order filled, and the balance already shows it, so holding it back would count it twice; or it
 * was cancelled on chain. Before `validTo` that read is sound, as nothing frees an order's storage
 * until then. After it, freed storage reads zero and keeps the order held, as does a read that
 * gives no answer.
 */
internal suspend fun List<CowOrderRecord>.holding(
    network: Network,
    account: Address,
    token: Address,
    finalized: FinalizedBlock?,
    cowChain: CowChain,
): UInt256? {
    val selling = filter { it.network == network && it.account == account && it.order.sellToken == token }
    return selling.filter { it.canStillTake(finalized) && !cowChain.doneAtHead(it) }
        .map { it.order.sellAmount }
        .sumOrNull()
}

/** Whether the settlement's `filledAmount` of [order] at the head reads its sell amount or more. */
private suspend fun CowChain.doneAtHead(order: CowOrderRecord): Boolean {
    val run = call(order.network, order.settlement, GPv2Settlement.filledAmount(order.uid)) as? Simulation.Succeeded
        ?: return false
    val filled = GPv2Settlement.filledAmountOf(run.returnHex) ?: return false
    return filled >= order.order.sellAmount
}

/** Whether this transaction is an approval of [spender] for [token]. */
private fun SentTransfer.approves(token: Token, spender: Address): Boolean =
    (action as? SentAction.TokenApproval)?.let { it.token.address == token.address && it.spender == spender } == true
