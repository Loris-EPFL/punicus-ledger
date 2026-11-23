package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SwapKind
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CowApi
import eth.serotine.domain.repository.CowChain
import eth.serotine.domain.repository.CowQuoteReading
import eth.serotine.domain.repository.CowRefusal
import eth.serotine.domain.repository.CowUnavailable
import eth.serotine.domain.repository.CowVenueReading
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.ReadUnavailable

/**
 * What a swap being composed with CoW chosen would come to, before it is reviewed (docs/swaps.md,
 * *The compose step's estimate*; owner ruling, 2026-11-23: the first screen shows the rate and the
 * amount): CoW's own quote, not verified, for selling exactly an amount ([SwapKind.SELL]) or
 * buying exactly one ([SwapKind.BUY]), with the rate it comes to, both ways.
 *
 * It asks CoW's API, so CoW learns the pair and the amount composed, whether the swap is then sent
 * or not (the same ruling). It reads nothing of Uniswap, signs nothing and decides nothing: the
 * review quotes everything again ([QuoteCowSwap]). What can refuse CoW without asking it is said
 * first and never reaches CoW: this device, the network's own currency sold, one token twice, an
 * unwrap (CoW's own pin of the wrapped currency), and no verified CoW deployment.
 */
class EstimateCowSwap(
    private val cowChain: CowChain,
    private val cowApi: CowApi,
    private val lightClient: LightClient,
) {
    /**
     * [sell] for [buy] from [from] on [network]: exactly [amount] (not zero) of [sell] sold, or of
     * [buy] bought, as [kind] says.
     */
    suspend operator fun invoke(
        network: Network,
        from: Address,
        sell: TransferAsset,
        buy: TransferAsset,
        amount: UInt256,
        kind: SwapKind,
    ): CowEstimating {
        require(!amount.isZero) { "a swap moves something" }
        if (!lightClient.datesFinalizedBlocks) return CowEstimating.NotOffered(CowNotOffered.NOT_ON_THIS_DEVICE)
        val soldToken = (sell as? TransferAsset.Erc20Token)?.token?.address
            ?: return CowEstimating.NotOffered(CowNotOffered.SELLS_ETHER)
        val buyToken = (buy as? TransferAsset.Erc20Token)?.token?.address ?: CowOrder.NATIVE_BUY_TOKEN
        if (buyToken == soldToken) return CowEstimating.NotASwap
        val deployment = when (val venue = cowChain.venue(network)) {
            is CowVenueReading.Verified -> venue.deployment
            CowVenueReading.NotPinned -> return CowEstimating.NoVenue(contract = null)
            is CowVenueReading.CodeMismatch -> return CowEstimating.NoVenue(venue.contract)
            is CowVenueReading.Unavailable -> return CowEstimating.Unavailable(venue.reason)
        }
        if (buy == TransferAsset.Native && soldToken == deployment.wrappedNative) return CowEstimating.NotASwap
        return when (val quoted = cowApi.quote(network, soldToken, buyToken, amount, from, kind)) {
            is CowQuoteReading.Quoted -> CowEstimating.Estimated(
                kind = kind,
                amountIn = quoted.sellAmount,
                amountOut = quoted.buyAmount,
                feeAmount = quoted.feeAmount,
                rate = wholeUnitRate(quoted.buyAmount, quoted.sellAmount, decimalsOf(sell)),
                inverseRate = wholeUnitRate(quoted.sellAmount, quoted.buyAmount, decimalsOf(buy)),
            )
            is CowQuoteReading.Refused -> CowEstimating.CowRefused(quoted.why, quoted.errorType)
            is CowQuoteReading.Unavailable -> CowEstimating.CowNoAnswer(quoted.why)
        }
    }

    private companion object {
        /** Decimals of every EVM chain's native currency. */
        const val NATIVE_DECIMALS = 18

        fun decimalsOf(asset: TransferAsset): Int = when (asset) {
            TransferAsset.Native -> NATIVE_DECIMALS
            is TransferAsset.Erc20Token -> asset.token.decimals
        }

        /**
         * What one whole unit of the asset [given] is in comes to in units of the asset [got] is in:
         * `got × 10^givenDecimals / given`, rounded down; null when [given] is zero or the quotient
         * leaves 256 bits.
         */
        fun wholeUnitRate(got: UInt256, given: UInt256, givenDecimals: Int): UInt256? {
            val one = UInt256.parseUnits("1", givenDecimals) ?: return null
            return got.mulDivOrNull(one, given)
        }
    }
}

/** What [EstimateCowSwap] comes to. */
sealed interface CowEstimating {

    /**
     * CoW's estimate, not verified: an order of [kind] would sell [amountIn] (its [feeAmount]
     * included) for [amountOut]; of a sell, [amountIn] is the amount typed, of a buy [amountOut] is.
     * [rate] is what one whole unit sold returns, in the bought asset's smallest units;
     * [inverseRate] what one whole unit bought costs, in the sold asset's; each null when it cannot
     * be had.
     */
    data class Estimated(
        val kind: SwapKind,
        val amountIn: UInt256,
        val amountOut: UInt256,
        val feeAmount: UInt256,
        val rate: UInt256?,
        val inverseRate: UInt256?,
    ) : CowEstimating

    /** CoW is not offered for this swap, for [why]; CoW was not asked. */
    data class NotOffered(val why: CowNotOffered) : CowEstimating

    /** The two assets are one token, or the wrapped currency against the currency: a wrap, not asked. */
    data object NotASwap : CowEstimating

    /** CoW is no venue here: not pinned (null [contract]), or [contract]'s code is not its pin. */
    data class NoVenue(val contract: Address?) : CowEstimating

    /** CoW refused to quote ([why]; [errorType], CoW's own word). */
    data class CowRefused(val why: CowRefusal, val errorType: String) : CowEstimating

    /** CoW gave no usable answer ([why]). */
    data class CowNoAnswer(val why: CowUnavailable) : CowEstimating

    /** The CoW deployment's code could not be proven right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : CowEstimating
}
