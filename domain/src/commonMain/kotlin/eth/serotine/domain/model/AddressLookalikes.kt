package eth.serotine.domain.model

/**
 * Address poisoning's tell: an attacker plants an address that starts and ends like one the user
 * deals with, so that the user copies it from the history by mistake. Two addresses are
 * look-alikes when they differ, yet share at least [MIN_PREFIX] leading and [MIN_SUFFIX] trailing
 * hex digits (the `0x` not counted). The comparison is on the 40 digits, never on their
 * EIP-55 case.
 */
object AddressLookalikes {

    /** The least number of leading hex digits two look-alikes share. */
    const val MIN_PREFIX: Int = 4

    /** The least number of trailing hex digits two look-alikes share. */
    const val MIN_SUFFIX: Int = 4

    /** Whether [a] and [b] are different addresses that share their first and last digits. */
    fun lookAlike(a: Address, b: Address): Boolean {
        if (a == b) return false
        val split = split(a, b)
        return split.prefix >= MIN_PREFIX && split.suffix >= MIN_SUFFIX
    }

    /**
     * How [a] and [b] line up: the digits both start with ([Split.prefix]), the digits both end with
     * ([Split.suffix]), never overlapping, so that the middle each has left is what differs.
     */
    fun split(a: Address, b: Address): Split {
        val x = digits(a)
        val y = digits(b)
        var prefix = 0
        while (prefix < x.length && x[prefix] == y[prefix]) prefix++
        var suffix = 0
        while (suffix < x.length - prefix && x[x.length - 1 - suffix] == y[y.length - 1 - suffix]) suffix++
        return Split(prefix, suffix)
    }

    /** The number of digits [prefix] and [suffix] both addresses share; the 40 − both between differ. */
    data class Split(val prefix: Int, val suffix: Int)

    /** The 40 hex digits of [address], lower-case, without `0x`. */
    fun digits(address: Address): String = address.hex.removePrefix("0x").lowercase()
}
