package eth.serotine.ui.swap

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.SwapKind
import eth.serotine.domain.model.SwapVenue

/**
 * What Swap opens with filled in, beside the account and the asset sold: the asset it buys (the
 * token at [buy], null: the network's currency), the [amount] as text, the exact one of [kind] (in
 * the sold asset's units for a sell, the bought asset's for a buy), and the [venue]. All of it
 * still the user's to change, and the swap theirs to review: the same swap again after a CoW order
 * that did not fill, on Uniswap (docs/swaps.md, *The user's choice of venue*).
 */
data class SwapFill(
    val buy: Address?,
    val amount: String,
    val venue: SwapVenue,
    val kind: SwapKind = SwapKind.SELL,
)
