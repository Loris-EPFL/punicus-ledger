package eth.serotine.domain.model

/**
 * The least a CoW order may buy, as docs/swaps.md, *The signed minimum*, sets it: CoW's own
 * estimate less the tolerance; and the most a buy order may sell, that estimate plus it. No other venue is quoted for it (owner ruling, 2026-11-23): the
 * minimum rests on CoW's estimate, which the light client cannot prove, and the review says so.
 * Pure arithmetic over amounts in the bought token's units; no price enters it.
 */
object CowMinimum {

    /** The order's `buyAmount`: CoW's [estimate] less [tolerance], rounded down. */
    fun buyAmount(estimate: UInt256, tolerance: SlippageTolerance): UInt256 = tolerance.minimumOf(estimate)

    /**
     * A buy order's `sellAmount`, the most it may sell: CoW's [estimate] of what it sells, its fee
     * included, plus [tolerance], rounded up (docs/swaps.md, *Buying an exact amount*); null when
     * that does not fit a uint256.
     */
    fun sellMaximum(estimate: UInt256, tolerance: SlippageTolerance): UInt256? = tolerance.maximumOf(estimate)
}
