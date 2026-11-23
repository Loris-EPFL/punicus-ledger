package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlippageToleranceTest {

    @Test
    fun theDefaultIsHalfAPercent() {
        assertEquals(50, SlippageTolerance.DEFAULT.basisPoints)
        assertEquals("0.5", SlippageTolerance.DEFAULT.percent)
        assertEquals("0.5 %", SlippageTolerance.DEFAULT.toString())
    }

    @Test
    fun theToleranceIsBetweenATenthOfAPercentAndFivePercent() {
        assertEquals(10, SlippageTolerance.ofBasisPoints(10)?.basisPoints)
        assertEquals(500, SlippageTolerance.ofBasisPoints(500)?.basisPoints)
        assertNull(SlippageTolerance.ofBasisPoints(9))
        assertNull(SlippageTolerance.ofBasisPoints(501))
        assertNull(SlippageTolerance.ofBasisPoints(0))
    }

    @Test
    fun aPercentageIsTypedWithAtMostTwoDecimals() {
        assertEquals(50, SlippageTolerance.parsePercent("0.5")?.basisPoints)
        assertEquals(25, SlippageTolerance.parsePercent("0.25")?.basisPoints)
        assertEquals(100, SlippageTolerance.parsePercent("1")?.basisPoints)
        assertEquals(500, SlippageTolerance.parsePercent("5")?.basisPoints)
        assertEquals("1", SlippageTolerance.parsePercent("1.00")?.percent)
        assertNull(SlippageTolerance.parsePercent("0.125"), "a third decimal")
        assertNull(SlippageTolerance.parsePercent("0.05"), "below 0.1 %")
        assertNull(SlippageTolerance.parsePercent("5.01"), "above 5 %")
        assertNull(SlippageTolerance.parsePercent("99999999999999999999999"), "far above")
        assertNull(SlippageTolerance.parsePercent("-1"))
        assertNull(SlippageTolerance.parsePercent(".5"))
        assertNull(SlippageTolerance.parsePercent(""))
    }

    @Test
    fun theMinimumIsTheQuoteLessTheToleranceRoundedDown() {
        val half = SlippageTolerance.DEFAULT
        assertEquals(UInt256.of(995), half.minimumOf(UInt256.of(1_000)))
        // 4.31 LINK at 0.5 %: 4.28845 LINK, exactly.
        assertEquals(UInt256.of("4288450000000000000"), half.minimumOf(UInt256.of("4310000000000000000")))
        // 199 × 0.995 = 198.005: rounded down, never up.
        assertEquals(UInt256.of(198), half.minimumOf(UInt256.of(199)))
        assertEquals(UInt256.ZERO, half.minimumOf(UInt256.ONE))
        assertEquals(UInt256.ZERO, half.minimumOf(UInt256.ZERO))
        assertEquals(UInt256.of(9_500), SlippageTolerance.ofBasisPoints(500)!!.minimumOf(UInt256.of(10_000)))
    }

    @Test
    fun theMinimumOfTheLargestQuoteDoesNotOverflow() {
        val max = UInt256.fromBigEndian(ByteArray(32) { 0xff.toByte() })
        // (2^256 - 1) × 9950 / 10000, rounded down, worked out exactly.
        assertEquals(
            UInt256.of("115213128791129614446453130083644468314003634742312361219260296087873563991735"),
            SlippageTolerance.DEFAULT.minimumOf(max),
        )
    }

    @Test
    fun theMaximumIsTheQuotePlusTheToleranceRoundedUp() {
        val half = SlippageTolerance.DEFAULT
        assertEquals(UInt256.of(1_005), half.maximumOf(UInt256.of(1_000)))
        // 1.005 and 0.0050…: rounded up, never down, so the maximum never falls below the quote's own.
        assertEquals(UInt256.of(2), half.maximumOf(UInt256.ONE))
        // 10 001 × 1.005 = 10 051.005.
        assertEquals(UInt256.of(10_052), half.maximumOf(UInt256.of(10_001)))
        assertEquals(UInt256.ZERO, half.maximumOf(UInt256.ZERO))
        assertEquals(UInt256.of(1_050), SlippageTolerance.ofBasisPoints(500)!!.maximumOf(UInt256.of(1_000)))
        // A quote past 2^256 / 1.005 leaves 256 bits.
        assertNull(half.maximumOf(UInt256.MAX))
    }
}
