package eth.serotine.ui.components

import eth.serotine.domain.model.UInt256

/**
 * [units] at [decimals] decimal places as a list says an amount at a glance, never more than it is: truncated, never
 * rounded up, trailing zeros dropped, the whole part grouped by thousands ("1,234.56").
 * - From 1,000,000 on, in millions, billions or trillions, to two places ("1.23M", "4.5B", "12,345T").
 * - From 1,000 on, to two decimal places ("1,234.56"); from 1 on, to four ("1.2345").
 * - Below 1, to four significant digits, after at most three zeros ("0.0001234"); anything smaller is "<0.0001",
 *   never zero.
 *
 * The exact amount stays one tap away, on the row's detail.
 */
internal fun compactAmount(units: UInt256, decimals: Int): String {
    val exact = units.formatUnits(decimals)
    val whole = exact.substringBefore('.')
    val fraction = exact.substringAfter('.', "")
    return when {
        whole.length > MILLION_DIGITS -> {
            val unit = minOf((whole.length - 1) / 3 - 2, SUFFIXES.lastIndex)
            val shift = 3 * (unit + 2)
            withFraction(digitGroups(whole.dropLast(shift)), whole.takeLast(shift).take(2)) + SUFFIXES[unit]
        }
        whole.length > THOUSAND_DIGITS -> withFraction(digitGroups(whole), fraction.take(2))
        whole != "0" -> withFraction(whole, fraction.take(SIGNIFICANT))
        units.isZero -> "0"
        else -> {
            val zeros = fraction.takeWhile { it == '0' }.length
            if (zeros > MAX_LEADING_ZEROS) {
                "<0.${"0".repeat(MAX_LEADING_ZEROS)}1"
            } else {
                withFraction("0", fraction.take(zeros + SIGNIFICANT))
            }
        }
    }
}

/** [whole] and the [fraction]'s digits, trailing zeros dropped: "12", "12.5". */
private fun withFraction(whole: String, fraction: String): String =
    fraction.trimEnd('0').let { if (it.isEmpty()) whole else "$whole.$it" }

/** A decimal integer's digits grouped by thousands: "1234567" as "1,234,567". */
private fun digitGroups(digits: String): String = digits.reversed().chunked(3).joinToString(",").reversed()

/** Up to this many digits the whole part is said as it is ("999,999"); from one more on, in millions. */
private const val MILLION_DIGITS = 6

/** Up to this many digits the whole part keeps four decimal places; from one more on, two. */
private const val THOUSAND_DIGITS = 3

/** The digits said of an amount below 1 after its leading zeros, and the places of one from 1 on. */
private const val SIGNIFICANT = 4

/** The most zeros said after the decimal point before an amount's first digit: smaller is "<0.0001". */
private const val MAX_LEADING_ZEROS = 3

/** Millions, billions, trillions; anything larger is said in trillions, grouped. */
private val SUFFIXES = listOf("M", "B", "T")
