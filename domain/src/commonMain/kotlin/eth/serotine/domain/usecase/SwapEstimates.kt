package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceImpact
import eth.serotine.domain.model.SwapKind
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.ReadUnavailable

/**
 * What a swap being composed would return on Uniswap, before it is reviewed (docs/swaps.md,
 * *The compose step's estimate*; docs/simple-ui.md, W1): the best route's verified QuoterV2 quote
 * for selling exactly an amount of one asset for another, with the rate it comes to, both ways.
 *
 * It is [FindUniswapRoute] and nothing more: chain data read with verified calls, never a price,
 * and it signs nothing. It is not the review's quote either: no balance, allowance or earlier
 * transaction of the account is read, the swap is not run from it, and the review quotes
 * everything again (screen 23). The route is ranked as [ranking] says, as the review ranks it: net
 * of fees for an account's swap, which reads the fee market to rank the routes, as the review
 * does; gross for a Safe's.
 */
class EstimateSwap(
    private val findRoute: FindUniswapRoute,
    private val ranking: RouteRanking,
) {
    /**
     * Exactly [amountIn] (not zero) of [sell] for [buy] on [network], a sell; or, [kind] being
     * [SwapKind.BUY], exactly [amountIn] of [buy] bought (docs/swaps.md, *Buying an exact
     * amount*): the route that sells the least for it ([FindUniswapRoute.buying], ranked gross),
     * whose estimate's [SwapEstimating.Estimated.amountIn] is what it takes.
     */
    suspend operator fun invoke(
        network: Network,
        sell: TransferAsset,
        buy: TransferAsset,
        amountIn: UInt256,
        kind: SwapKind = SwapKind.SELL,
    ): SwapEstimating {
        require(!amountIn.isZero) { "a swap moves something" }
        val finding = when (kind) {
            SwapKind.SELL -> findRoute(network, sell, buy, amountIn, ranking)
            SwapKind.BUY -> findRoute.buying(network, sell, buy, amountOut = amountIn)
        }
        return when (val found = finding) {
            is RouteFinding.Found -> {
                val sold = found.amountIn
                val out = found.quote.amountOut
                SwapEstimating.Estimated(
                    amountIn = sold,
                    amountOut = out,
                    rate = rateOf(out, sold, decimalsOf(sell)),
                    inverseRate = rateOf(sold, out, decimalsOf(buy)),
                    priceImpact = found.spotOut?.let { PriceImpact.basisPoints(it, out) },
                    blockNumber = found.blockNumber,
                    kind = kind,
                )
            }
            RouteFinding.NotASwap -> SwapEstimating.NotASwap
            RouteFinding.NoRoute -> SwapEstimating.NoRoute
            RouteFinding.NotPinned -> SwapEstimating.NoVenue(contract = null)
            is RouteFinding.CodeMismatch -> SwapEstimating.NoVenue(found.contract)
            is RouteFinding.Unavailable -> SwapEstimating.Unavailable(found.reason)
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
         * What one whole unit of the asset [given] is in comes to in units of the asset [got] is
         * in: `got × 10^givenDecimals / given`, rounded down; null when [given] is zero or the
         * quotient leaves 256 bits.
         */
        fun rateOf(got: UInt256, given: UInt256, givenDecimals: Int): UInt256? {
            val one = UInt256.parseUnits("1", givenDecimals) ?: return null
            return got.mulDivOrNull(one, given)
        }
    }
}

/** What [EstimateSwap] comes to. */
sealed interface SwapEstimating {

    /**
     * Selling [amountIn] returns [amountOut] by the best route's verified quote, run against the
     * state of [blockNumber] (null: not said). [rate] is what one whole unit of the asset sold
     * returns, in the bought asset's smallest units; [inverseRate] what one whole unit of the
     * asset bought costs, in the sold asset's smallest units; each null when it cannot be had (a
     * quote of nothing). [priceImpact] is how far the quote falls below the route's pools' spot
     * prices, in basis points ([PriceImpact.basisPoints]); null when not known. [kind] says which
     * amount was asked for: [amountIn] for a sell, [amountOut] for a buy, the other the quote's.
     */
    data class Estimated(
        val amountIn: UInt256,
        val amountOut: UInt256,
        val rate: UInt256?,
        val inverseRate: UInt256?,
        val priceImpact: Int?,
        val blockNumber: Long?,
        val kind: SwapKind = SwapKind.SELL,
    ) : SwapEstimating

    /** The two assets are one token on Uniswap: the same one, or ETH and WETH, a wrap. */
    data object NotASwap : SwapEstimating

    /** No pool takes the pair, directly or through WETH, or none takes this amount. */
    data object NoRoute : SwapEstimating

    /** Uniswap is no venue on the network: not pinned (null [contract]), or [contract]'s code is not its pin. */
    data class NoVenue(val contract: Address?) : SwapEstimating

    /** A read the quote needs could not be had right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : SwapEstimating
}
