package eth.serotine.domain.model

/**
 * Which amount of a swap is exact (docs/swaps.md, *Buying an exact amount*; owner's request,
 * 2026-11-23), on either venue.
 */
enum class SwapKind {
    /** Sell exactly the amount sold, for at least a minimum of the token bought. */
    SELL,

    /** Buy exactly the amount bought, for at most a maximum of the token sold. */
    BUY,
}
