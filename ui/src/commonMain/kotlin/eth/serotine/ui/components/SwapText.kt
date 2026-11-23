package eth.serotine.ui.components

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.SwapKind
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CowRefusal
import eth.serotine.domain.repository.CowUnavailable
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.CowEstimating
import eth.serotine.domain.usecase.CowNotOffered
import eth.serotine.domain.usecase.SwapEstimating

/** Why the tolerance typed is none: what a swap's form says under it. */
internal const val TOLERANCE_ERROR = "Between 0.1 % and 5 %, with at most two decimals (0.5)."

/** Uniswap's router, as a review names it. */
internal const val ROUTER_NAME = "Uniswap's SwapRouter02"

/** A swap whose minimum, less the tolerance, rounds to nothing. */
internal const val TOO_SMALL = "This amount is too small: less the tolerance, the swap could return nothing."

/** A token's contract that does not answer as one. */
internal const val NOT_A_TOKEN = "A token's contract does not answer as a token: nothing can be swapped with it."

/**
 * [route]'s pools on [network], one step each, through the factory's [pools]: the pair in [sell]'s
 * and [buy]'s symbols (the network's wrapped currency between them, WETH or WXDAI), its fee tier and
 * the pool.
 */
internal fun routeSteps(
    route: SwapRoute,
    pools: List<Address>,
    sell: AssetOption,
    buy: AssetOption,
    network: Network,
): List<RouteStep> {
    val symbolOf = { address: Address ->
        when (address) {
            sell.contract -> sell.symbol
            buy.contract -> buy.symbol
            else -> wrappedSymbol(network)
        }
    }
    return route.fees.indices.map { i ->
        RouteStep(
            pair = "${symbolOf(route.tokens[i])} → ${symbolOf(route.tokens[i + 1])}",
            fee = feeTierText(route.fees[i].pips),
            pool = Eip55.format(pools[i]),
        )
    }
}

/** A price impact of [basisPoints] as a percentage, without its sign: "0.12", or "under 0.01" for none. */
private fun impactFigure(basisPoints: Int): String =
    if (basisPoints == 0) "under 0.01" else UInt256.of(basisPoints.toLong()).formatUnits(2)

/** The price impact of [basisPoints] (null: not known), as a review says it. */
internal fun priceImpactText(basisPoints: Int?): String = basisPoints?.let {
    "Price impact: ${impactFigure(it)} %, the quote against the pools' spot prices, their fees aside."
} ?: "Price impact: not known this time: the pools' spot prices could not be read at the quote's block, or " +
    "give no measure for this amount."

/** What the prices the wallet shows say of a quote on [network]: on a testnet, nothing (display only). */
internal fun priceHintText(network: Network): String? = if (network.testnet) {
    "${network.displayName}'s pools and Chainlink's testnet feeds do not track each other: this quote is not " +
        "held against the prices the wallet shows."
} else {
    null
}

/**
 * [sell] and [buy], one token on Uniswap on [network], as a Safe's swap says it: a wrap, which a Safe
 * cannot propose yet.
 */
internal fun notASwapText(sell: String, buy: String, network: Network): String =
    "$sell and $buy are one token on Uniswap: ${network.nativeSymbol} for ${wrappedSymbol(network)} and back is a " +
        "wrap, not a swap, and a Safe cannot propose a wrap here yet."

/** No pool for [sell] and [buy] on [network]. */
internal fun noRouteText(sell: String, buy: String, network: Network): String =
    "No Uniswap v3 pool takes $sell for $buy, directly or through ${wrappedSymbol(network)}, for this amount."

/**
 * The token that wraps [network]'s own currency, as its pinned WETH9 contract is named: WETH on
 * Ethereum and Sepolia, WXDAI on Gnosis Chain.
 */
internal fun wrappedSymbol(network: Network): String = "W" + network.nativeSymbol

/**
 * About how long a block takes to be finalized on [network], in words: two to three epochs, 13 to
 * 19 minutes where an epoch is 32 slots of 12 seconds, about 3 to 4 where it is 16 of 5 (Gnosis
 * Chain).
 */
internal fun finalityWaitText(network: Network): String = when (network) {
    Network.GNOSIS -> "a few minutes"
    Network.ETHEREUM, Network.SEPOLIA -> "a quarter of an hour"
}

/** Uniswap is no venue on [network]: not pinned, or [contract]'s code is not its pin. */
internal fun noVenueText(contract: Address?, network: Network): String = contract?.let {
    "The code at ${Eip55.format(it)}, one of Uniswap's pinned contracts, is not the code pinned with it, as the " +
        "light client proves it: Uniswap is no venue here."
} ?: "Uniswap is not pinned for ${network.displayName}, so nothing can be swapped here."

/**
 * [units] in whole units with [decimals], cut to six significant digits from one up and to four
 * below one, never rounded up: "2499.98" for 2499.987654, "0.0004123" for 0.000412345. What an
 * estimate shows; a review shows the exact amount ([exactAmount]).
 */
internal fun approxUnits(units: UInt256, decimals: Int, symbol: String): String {
    val exact = units.formatUnits(decimals)
    val whole = exact.substringBefore('.')
    val fraction = exact.substringAfter('.', missingDelimiterValue = "")
    val places = if (whole != "0") {
        (ESTIMATE_DIGITS - whole.length).coerceAtLeast(0)
    } else {
        fraction.takeWhile { it == '0' }.length + ESTIMATE_SMALL_DIGITS
    }
    val kept = fraction.take(places).trimEnd('0')
    return if (kept.isEmpty()) "$whole $symbol" else "$whole.$kept $symbol"
}

/** Significant digits an estimate keeps of an amount of one or more. */
private const val ESTIMATE_DIGITS = 6

/** Significant digits an estimate keeps of an amount below one. */
private const val ESTIMATE_SMALL_DIGITS = 4

/** The price impact, in basis points, from which the compose step warns of it: 1 %. */
private const val ESTIMATE_IMPACT_WARNING = 100

/**
 * The headline figure of an estimate of [kind] and the bound it signs at [tolerance]: for a sell,
 * what it would receive and the least it may; for a buy, what it would pay and the most it may
 * (docs/swaps.md, *Buying an exact amount*). The label first, as the compose step heads it.
 */
private fun estimateFigures(
    kind: SwapKind,
    amountIn: UInt256,
    amountOut: UInt256,
    sell: AssetOption,
    buy: AssetOption,
    tolerance: SlippageTolerance?,
): Triple<String, String, String?> = when (kind) {
    SwapKind.SELL -> {
        val bought = { units: UInt256 -> approxUnits(units, buy.decimals, buy.symbol) }
        Triple(
            "You receive",
            "≈ " + bought(amountOut),
            tolerance?.let { "At least ${bought(it.minimumOf(amountOut))} at $it tolerance" },
        )
    }
    SwapKind.BUY -> {
        val sold = { units: UInt256 -> approxUnits(units, sell.decimals, sell.symbol) }
        Triple(
            "You pay",
            "≈ " + sold(amountIn),
            tolerance?.let { at -> at.maximumOf(amountIn)?.let { "At most ${sold(it)} at $at tolerance" } },
        )
    }
}

/** The label an estimate of [kind] has before its figures are in. */
private fun estimateLabel(kind: SwapKind): String = if (kind == SwapKind.BUY) "You pay" else "You receive"

/**
 * What the compose step shows of [estimating], a verified Uniswap quote on [network] of selling
 * exactly an amount of [sell] for [buy], or of buying exactly one, as [kind] says (docs/swaps.md,
 * *The compose step's estimate*, *Buying an exact amount*), null while it is being read; at
 * [tolerance] when one is typed. Only for a swap on Uniswap: a CoW order quotes no Uniswap figure
 * (owner ruling, 2026-11-23), and has [cowEstimateItem].
 */
internal fun swapEstimateItem(
    estimating: SwapEstimating?,
    sell: AssetOption,
    buy: AssetOption,
    tolerance: SlippageTolerance?,
    network: Network,
    kind: SwapKind = SwapKind.SELL,
): SwapEstimateItem {
    val label = estimateLabel(kind)
    val bought = { units: UInt256 -> approxUnits(units, buy.decimals, buy.symbol) }
    return when (estimating) {
        null -> SwapEstimateItem(label, note = "Asking Uniswap's quoter, through the light client.", checking = true)
        is SwapEstimating.Estimated -> {
            val block = estimating.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty()
            val (heading, receive, bound) =
                estimateFigures(kind, estimating.amountIn, estimating.amountOut, sell, buy, tolerance)
            SwapEstimateItem(
                label = heading,
                receive = receive,
                minimum = bound,
                rate = estimating.rate?.let { "1 ${sell.symbol} ≈ ${bought(it)}" },
                inverseRate = estimating.inverseRate?.let {
                    "1 ${buy.symbol} ≈ ${approxUnits(it, sell.decimals, sell.symbol)}"
                },
                impact = estimating.priceImpact?.takeIf { it >= ESTIMATE_IMPACT_WARNING }?.let {
                    "Price impact ${impactFigure(it)} %: this amount moves the pools' price. " +
                        "The review says more."
                },
                note = "Uniswap's verified quote$block. The review quotes the swap again, with its network fee, " +
                    "before anything is signed.",
                simpleNote = "Checked on chain. The review checks it again before you confirm.",
            )
        }
        SwapEstimating.NotASwap -> SwapEstimateItem(label, note = notASwapText(sell.symbol, buy.symbol, network))
        SwapEstimating.NoRoute -> SwapEstimateItem(label, note = noRouteText(sell.symbol, buy.symbol, network))
        is SwapEstimating.NoVenue -> SwapEstimateItem(label, note = noVenueText(estimating.contract, network))
        is SwapEstimating.Unavailable -> SwapEstimateItem(label, note = estimateUnavailableText(estimating.reason))
    }
}

/**
 * What the compose step shows of [estimating], CoW's own estimate on [network] of selling exactly an
 * amount of [sell] for [buy], or of buying exactly one, as [kind] says, null while it is being asked
 * for; at [tolerance] when one is typed (owner ruling, 2026-11-23: the first screen shows the rate and
 * the amount). Every figure is marked as CoW's, not checked on chain.
 */
internal fun cowEstimateItem(
    estimating: CowEstimating?,
    sell: AssetOption,
    buy: AssetOption,
    tolerance: SlippageTolerance?,
    network: Network,
    kind: SwapKind,
): SwapEstimateItem {
    val label = estimateLabel(kind)
    val bought = { units: UInt256 -> approxUnits(units, buy.decimals, buy.symbol) }
    return when (estimating) {
        null -> SwapEstimateItem(
            label,
            note = "Asking CoW's servers for their estimate.",
            checking = true,
            checkingText = "Asking CoW…",
        )
        is CowEstimating.Estimated -> {
            val (heading, receive, bound) =
                estimateFigures(kind, estimating.amountIn, estimating.amountOut, sell, buy, tolerance)
            SwapEstimateItem(
                label = heading,
                receive = receive,
                minimum = bound,
                rate = estimating.rate?.let { "1 ${sell.symbol} ≈ ${bought(it)}" },
                inverseRate = estimating.inverseRate?.let {
                    "1 ${buy.symbol} ≈ ${approxUnits(it, sell.decimals, sell.symbol)}"
                },
                note = "CoW's estimate, not checked on chain: its fee of " +
                    approxUnits(estimating.feeAmount, sell.decimals, sell.symbol) + " is in the price. The " +
                    "review asks CoW again before anything is signed.",
                simpleNote = "CoW's estimate, not checked on chain. The review asks CoW again before you confirm.",
            )
        }
        is CowEstimating.NotOffered -> SwapEstimateItem(label, note = cowNotOfferedText(estimating.why, network))
        CowEstimating.NotASwap -> SwapEstimateItem(
            label,
            note = "${sell.symbol} and ${buy.symbol} are one token: there is nothing to swap.",
        )
        is CowEstimating.NoVenue -> SwapEstimateItem(
            label,
            note = estimating.contract?.let {
                "CoW's contract at ${Eip55.format(it)} is not what this wallet pins, so CoW is not offered."
            } ?: "No CoW Protocol deployment is pinned for ${network.displayName}, so CoW is not offered.",
        )
        is CowEstimating.CowRefused -> SwapEstimateItem(
            label,
            note = cowRefusalText(estimating.why, estimating.errorType, sell.symbol, buy.symbol),
        )
        is CowEstimating.CowNoAnswer -> SwapEstimateItem(label, note = cowNoAnswerText(estimating.why, network))
        is CowEstimating.Unavailable -> SwapEstimateItem(label, note = estimateUnavailableText(estimating.reason))
    }
}

/** Why CoW is not offered for a swap on [network], for [why], before CoW is asked anything. */
internal fun cowNotOfferedText(why: CowNotOffered, network: Network): String = when (why) {
    CowNotOffered.NOT_ON_THIS_DEVICE -> "CoW Protocol is not offered on this device: its light client cannot " +
        "date a finalized block yet, which proving an order's end needs. Swap on Uniswap."
    CowNotOffered.SELLS_ETHER -> "CoW Protocol is offered for tokens sold: selling ${network.nativeSymbol} would " +
        "go through CoW's ETH-flow contract, which this wallet does not use yet. Sell ${wrappedSymbol(network)} " +
        "on CoW, or swap ${network.nativeSymbol} on Uniswap."
    CowNotOffered.NOT_FOLLOWED_YET -> "This account's history is followed from a block that is not finalized " +
        "yet, so a CoW order's end could not be proven. CoW is offered once it is, in " +
        "${finalityWaitText(network)}."
}

/** CoW's refusal to quote [sell] for [buy], for [why], with CoW's own word for it, [errorType]. */
internal fun cowRefusalText(why: CowRefusal, errorType: String, sell: String, buy: String): String = when (why) {
    CowRefusal.NO_LIQUIDITY -> "CoW found no liquidity for this swap"
    CowRefusal.UNSUPPORTED_TOKEN -> "CoW does not trade $sell or $buy, or not right now"
    CowRefusal.TOO_SMALL -> "The amount does not cover CoW's fee"
    CowRefusal.FORBIDDEN -> "CoW does not serve this account"
    CowRefusal.FUNDS -> "By CoW's own check, the balance or the allowance does not cover the order"
    CowRefusal.OTHER -> "CoW refused to quote this swap"
} + " (CoW's word: $errorType)."

/** That CoW gave no usable answer on [network], for [why]. */
internal fun cowNoAnswerText(why: CowUnavailable, network: Network): String = when (why) {
    CowUnavailable.UNDECODABLE -> "CoW gave an answer this version does not understand: an update of Punicus " +
        "brings CoW back."
    CowUnavailable.NOT_PINNED -> "No CoW API is pinned for ${network.displayName}."
    CowUnavailable.TIMEOUT, CowUnavailable.NO_CONNECTION, CowUnavailable.SERVER_ERROR -> "CoW did not answer."
}

/**
 * What the compose step shows for [amount] of [sell] for [buy] when the two are one token on Uniswap
 * (ETH and its pinned WETH9): a wrap through WETH9, one for one (docs/swaps.md, *Wrapping ETH*), with
 * no pool, price or tolerance. Its network fee comes with the review.
 */
internal fun wrapEstimateItem(amount: UInt256, sell: AssetOption, buy: AssetOption): SwapEstimateItem =
    SwapEstimateItem(
        label = "You receive",
        receive = exactAmount(amount, buy.decimals, buy.symbol),
        rate = "1 ${sell.symbol} = 1 ${buy.symbol}",
        note = "A wrap through WETH9, one for one: no pool, no price and no tolerance. The review quotes its " +
            "network fee.",
        simpleNote = "A wrap, one for one. The review shows its network fee.",
    )

/** Why the compose step has no estimate when a read it needs could not be had, for [reason]. */
private fun estimateUnavailableText(reason: ReadUnavailable): String = when (reason) {
    ReadUnavailable.NO_ENGINE -> "There is no light client on this device, so the swap cannot be quoted."
    ReadUnavailable.NETWORK_OFF -> "The network's light client is not running, so the swap cannot be quoted."
    ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so the swap cannot be quoted."
    ReadUnavailable.SYNCING -> "The light client is still syncing: the swap is quoted once it has caught up."
    ReadUnavailable.UNPROVEN -> "The quote could not be verified this time: it is asked for again shortly."
    ReadUnavailable.REFUSED -> "The light client on this device cannot quote swaps."
}
