package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SwapKind
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CowQuoteReading
import eth.serotine.domain.repository.CowRefusal
import eth.serotine.domain.repository.CowVenueReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CowEstimatesTest {
    private val account = Address.of("0x" + "ac".repeat(20))
    private val wxdai = Token(Address.of("0x" + "ff".repeat(20)), "WXDAI", "Wrapped XDAI", 18, icon = null)
    private val bzz = Token(Address.of("0x" + "b2".repeat(20)), "BZZ", "BZZ on xDai", 16, icon = null)
    private val wxdaiAsset = TransferAsset.Erc20Token(wxdai)
    private val bzzAsset = TransferAsset.Erc20Token(bzz)
    private val light = FakeLightClient()
    private val cowChain = FakeCowChain()
    private val cowApi = FakeCowApi()
    private val estimate = EstimateCowSwap(cowChain, cowApi, light)

    // 200 BZZ for 17.58 WXDAI, CoW's fee included.
    private val twoHundredBzz = UInt256.of("2000000000000000000")
    private val cost = UInt256.of("17582823909156244536")

    @Test
    fun aBuyIsEstimatedWithWhatItSellsAndTheRateBothWays() = runTest {
        cowApi.quote = CowQuoteReading.Quoted(cost, twoHundredBzz, UInt256.of(49_869_600_000))

        val estimated = estimate(Network.GNOSIS, account, wxdaiAsset, bzzAsset, twoHundredBzz, SwapKind.BUY)

        assertEquals(
            CowEstimating.Estimated(
                kind = SwapKind.BUY,
                amountIn = cost,
                amountOut = twoHundredBzz,
                feeAmount = UInt256.of(49_869_600_000),
                // One WXDAI buys about 11.37 BZZ (16 decimals); one BZZ costs about 0.0879 WXDAI.
                rate = UInt256.of("113747371317214935"),
                inverseRate = UInt256.of("87914119545781222"),
            ),
            estimated,
        )
        assertEquals(listOf<Any>(Network.GNOSIS, wxdai.address, bzz.address, twoHundredBzz, account), cowApi.quotes.single())
        assertEquals(listOf(SwapKind.BUY), cowApi.kinds)
    }

    @Test
    fun whatCowIsNotOfferedForNeverReachesCow() = runTest {
        assertEquals(
            CowEstimating.NotOffered(CowNotOffered.SELLS_ETHER),
            estimate(Network.GNOSIS, account, TransferAsset.Native, bzzAsset, twoHundredBzz, SwapKind.SELL),
        )
        assertEquals(
            CowEstimating.NotASwap,
            estimate(Network.GNOSIS, account, bzzAsset, bzzAsset, twoHundredBzz, SwapKind.SELL),
        )
        // The wrapped currency for the currency itself, by CoW's own pin (the fakes' WETH9 is "ff…").
        assertEquals(
            CowEstimating.NotASwap,
            estimate(Network.GNOSIS, account, wxdaiAsset, TransferAsset.Native, twoHundredBzz, SwapKind.SELL),
        )
        cowChain.venue = CowVenueReading.NotPinned
        assertEquals(
            CowEstimating.NoVenue(contract = null),
            estimate(Network.GNOSIS, account, wxdaiAsset, bzzAsset, twoHundredBzz, SwapKind.SELL),
        )
        light.datesFinalizedBlocks = false
        assertEquals(
            CowEstimating.NotOffered(CowNotOffered.NOT_ON_THIS_DEVICE),
            estimate(Network.GNOSIS, account, wxdaiAsset, bzzAsset, twoHundredBzz, SwapKind.SELL),
        )
        assertTrue(cowApi.quotes.isEmpty())
    }

    @Test
    fun cowsRefusalIsSaidAsCows() = runTest {
        cowApi.quote = CowQuoteReading.Refused(CowRefusal.NO_LIQUIDITY, "NoLiquidity")
        assertEquals(
            CowEstimating.CowRefused(CowRefusal.NO_LIQUIDITY, "NoLiquidity"),
            estimate(Network.GNOSIS, account, wxdaiAsset, bzzAsset, twoHundredBzz, SwapKind.SELL),
        )
    }
}
