package eth.serotine.data.swaps

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network

/**
 * Each network's wrapped currency (WETH, WXDAI) as this release pins it ([CowDeployments], the WETH9 the
 * Uniswap pins name too): what a verified trace reads the `Deposit` and `Withdrawal` of (docs/clear-signing.md,
 * *Phase B*). The hosts hand it to the use cases.
 */
object WrappedCurrency {
    /** The wrapped currency pinned on [network], or null when none is. */
    fun of(network: Network): Address? = CowDeployments.of(network)?.deployment?.wrappedNative
}
