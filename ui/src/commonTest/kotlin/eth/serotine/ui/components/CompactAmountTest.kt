package eth.serotine.ui.components

import eth.serotine.domain.model.UInt256
import kotlin.test.Test
import kotlin.test.assertEquals

class CompactAmountTest {

    private fun said(decimal: String, decimals: Int = 18): String = compactAmount(UInt256.of(decimal), decimals)

    @Test
    fun belowOneFourSignificantDigitsAfterAtMostThreeZeros() {
        assertEquals("0.25", said("250000000000000000"))
        assertEquals("0.1234", said("123456789000000000"), "truncated, never rounded up")
        assertEquals("0.0001234", said("123456789000000"))
        assertEquals("<0.0001", said("12345678900000"), "four zeros or more: smaller than said")
        assertEquals("<0.0001", said("1"), "one wei is never zero")
        assertEquals("0", said("0"))
    }

    @Test
    fun fromOneToFourPlacesAndFromAThousandToTwoGrouped() {
        assertEquals("1", said("1000000000000000000"))
        assertEquals("1.2345", said("1234567890000000000"))
        assertEquals("40.5", said("40500000", decimals = 6))
        assertEquals("1,234.56", said("1234567890", decimals = 6))
        assertEquals("999,999.99", said("999999999999", decimals = 6))
    }

    @Test
    fun fromAMillionInMillionsBillionsAndTrillions() {
        assertEquals("1.23M", said("1234567", decimals = 0))
        assertEquals("1M", said("1000000", decimals = 0))
        assertEquals("4.5B", said("4500000000", decimals = 0))
        assertEquals("12.34T", said("12345678901234", decimals = 0))
        assertEquals("12,345.67T", said("12345678901234567", decimals = 0), "beyond, still trillions")
    }
}
