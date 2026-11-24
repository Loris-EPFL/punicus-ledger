package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CowMinimumTest {

    private val half = SlippageTolerance.DEFAULT

    @Test
    fun theMinimumIsTheEstimateLessTheTolerance() {
        // CoW estimates 1 200: the order signs 1 194, the tolerance below CoW's own figure.
        assertEquals(UInt256.of(1_194), CowMinimum.buyAmount(UInt256.of(1_200), half))
        assertEquals(UInt256.of(995), CowMinimum.buyAmount(UInt256.of(1_000), half))
    }

    @Test
    fun itRoundsDown() {
        assertEquals(UInt256.ZERO, CowMinimum.buyAmount(UInt256.ONE, half))
    }
}
