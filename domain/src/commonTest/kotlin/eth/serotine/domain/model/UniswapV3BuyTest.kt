package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The Uniswap v3 calls that buy an exact amount (docs/swaps.md, *Buying an exact amount*): their
 * selectors, each found in the pinned QuoterV2's and SwapRouter02's code on Gnosis Chain, Sepolia
 * and Ethereum (2026-11-23), and their ABI encoding, word by word. The tokens are Sepolia's USDC,
 * WETH9 and LINK, as in [UniswapV3Test].
 */
class UniswapV3BuyTest {
    private val usdc = Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238")
    private val weth = Address.of("0xfff9976782d46cc05630d1f6ebab18b2324d6b14")
    private val link = Address.of("0x779877a7b0d9e8603169ddbd7836e478b4624789")
    private val account = Address.of("0x70997970c51812dc3a010c7d01b50e0d17dc79c8")
    private val deployment = UniswapV3Deployment(
        factory = Address.of("0x0227628f3f023bb0b980b67d528571c95c6dac1c"),
        quoterV2 = Address.of("0xed1f6473345f45b75f8179591dd5ba1888cf2fb3"),
        swapRouter02 = Address.of("0x3bfa4769fb09eefc5a80d6e87c3b9c650f7ae48e"),
        weth9 = weth,
    )
    private val usdcToken = TransferAsset.Erc20Token(Token(usdc, "USDC", "USD Coin", 6, icon = null))
    private val linkToken = TransferAsset.Erc20Token(Token(link, "LINK", "ChainLink Token", 18, icon = null))

    private val fiveLink = UInt256.of("5000000000000000000")
    private val maximum = UInt256.of(101_000_000)
    private val deadline = 1_792_419_600L

    /** A price of one, as a Q64.96. */
    private val q96 = UInt256.of("79228162514264337593543950336")

    /** USDC → WETH at 0.05 %, then WETH → LINK at 0.3 %. */
    private val hop = SwapRoute.via(usdc, FeeTier.LOW, weth, FeeTier.MEDIUM, link)

    private fun word(address: Address) = "00".repeat(12) + address.hex.drop(2)
    private fun word(value: UInt256) = value.toBytes32().toHex()
    private fun word(value: Long) = UInt256.of(value).toBytes32().toHex()

    @Test
    fun theSelectorsAreTheSignaturesKeccakPrefixes() {
        mapOf(
            "quoteExactOutputSingle((address,address,uint256,uint24,uint160))" to "bd21704a",
            "quoteExactOutput(bytes,uint256)" to "2f80bb1d",
            "exactOutputSingle((address,address,uint24,address,uint256,uint256,uint160))" to "5023b4df",
            "exactOutput((bytes,address,uint256,uint256))" to "09b81346",
            "refundETH()" to "12210e8a",
        ).forEach { (signature, selector) -> assertEquals(selector, AbiWords.selector(signature).toHex(), signature) }
    }

    @Test
    fun aRoutesReversedPathGoesFromTheTokenBoughtBackToTheTokenSold() {
        assertEquals(
            "779877a7b0d9e8603169ddbd7836e478b4624789" + "000bb8" +
                "fff9976782d46cc05630d1f6ebab18b2324d6b14" + "0001f4" +
                "1c7d4b196cb0c7b01d743fbc6116a902379c7238",
            hop.reversedPath().toHex(),
        )
        val direct = SwapRoute.direct(usdc, link, FeeTier.LOW)
        assertEquals(link.hex.drop(2) + "0001f4" + usdc.hex.drop(2), direct.reversedPath().toHex())
    }

    @Test
    fun aDirectBuyIsQuotedWithQuoteExactOutputSingle() {
        assertEquals(
            "bd21704a" + word(usdc) + word(link) + word(fiveLink) + word(500) + word(0),
            QuoterV2.quoteToBuy(SwapRoute.direct(usdc, link, FeeTier.LOW), fiveLink).toHex(),
        )
    }

    @Test
    fun aHopsBuyIsQuotedWithQuoteExactOutputAndTheReversedPath() {
        val path = hop.reversedPath().toHex()
        assertEquals(
            "2f80bb1d" + word(0x40) + word(fiveLink) + word(66) + path + "00".repeat(30),
            QuoterV2.quoteToBuy(hop, fiveLink).toHex(),
        )
    }

    @Test
    fun aBuysQuoteIsReadAsTheAmountItTakesAndItsGas() {
        val direct = SwapRoute.direct(usdc, link, FeeTier.LOW)
        val single = word(UInt256.of(100_400_000)) + word(q96) + word(2) + word(96_000)
        assertEquals(RouteCost(direct, UInt256.of(100_400_000), 96_000), QuoterV2.costOf(direct, single))
        assertNull(QuoterV2.costOf(direct, single.dropLast(64)), "three words")
        // A hop's answer: the amount, the two arrays' offsets, the gas, then one entry per pool in each.
        val hopAnswer = word(UInt256.of(100_400_000)) + word(0x80) + word(0xe0) + word(182_000) +
            word(2) + word(q96) + word(q96) +
            word(2) + word(1) + word(1)
        assertEquals(RouteCost(hop, UInt256.of(100_400_000), 182_000), QuoterV2.costOf(hop, hopAnswer))
        assertNull(QuoterV2.costOf(hop, single), "a hop's answer has arrays")
    }

    @Test
    fun aTokenBuyInOnePoolIsExactOutputSingleInAMulticallWithTheDeadline() {
        val call = SwapRouter02.buy(
            deployment,
            sell = usdcToken,
            buy = linkToken,
            route = SwapRoute.direct(usdc, link, FeeTier.LOW),
            amountOut = fiveLink,
            amountInMaximum = maximum,
            account = account,
            deadline = deadline,
        )
        assertEquals(deployment.swapRouter02, call.to)
        assertEquals(UInt256.ZERO, call.value)
        val swap = "5023b4df" + word(usdc) + word(link) + word(500) + word(account) + word(fiveLink) +
            word(maximum) + word(0)
        assertEquals(
            "5ae401dc" + word(deadline) + word(0x40) + word(1) + word(0x20) + word(0xe4) + swap + "00".repeat(28),
            call.data.toHex(),
        )
    }

    @Test
    fun aHopsBuyIsExactOutputWithTheReversedPath() {
        val call = SwapRouter02.buy(deployment, usdcToken, linkToken, hop, fiveLink, maximum, account, deadline)
        val path = hop.reversedPath().toHex()
        val swap = "09b81346" + word(0x20) + word(0x80) + word(account) + word(fiveLink) + word(maximum) +
            word(66) + path + "00".repeat(30)
        val padding = "00".repeat((32 - (swap.length / 2) % 32) % 32)
        assertEquals(
            "5ae401dc" + word(deadline) + word(0x40) + word(1) + word(0x20) + word((swap.length / 2).toLong()) +
                swap + padding,
            call.data.toHex(),
        )
    }

    @Test
    fun sellingEtherSendsTheMaximumAsTheValueAndRefundsWhatWasNotUsed() {
        val call = SwapRouter02.buy(
            deployment,
            sell = TransferAsset.Native,
            buy = linkToken,
            route = SwapRoute.direct(weth, link, FeeTier.MEDIUM),
            amountOut = fiveLink,
            amountInMaximum = UInt256.of(10_000_000_000_000_000L),
            account = account,
            deadline = deadline,
        )
        assertEquals(UInt256.of(10_000_000_000_000_000L), call.value)
        val swap = "5023b4df" + word(weth) + word(link) + word(3_000) + word(account) + word(fiveLink) +
            word(UInt256.of(10_000_000_000_000_000L)) + word(0)
        assertEquals(
            "5ae401dc" + word(deadline) + word(0x40) + word(2) + word(0x40) + word(0x160) +
                word(0xe4) + swap + "00".repeat(28) + word(4) + "12210e8a" + "00".repeat(28),
            call.data.toHex(),
        )
    }

    @Test
    fun buyingEtherKeepsTheWethInTheRouterAndUnwrapsTheExactAmountToTheAccount() {
        val call = SwapRouter02.buy(
            deployment,
            sell = usdcToken,
            buy = TransferAsset.Native,
            route = SwapRoute.direct(usdc, weth, FeeTier.LOW),
            amountOut = fiveLink,
            amountInMaximum = maximum,
            account = account,
            deadline = deadline,
        )
        assertEquals(UInt256.ZERO, call.value)
        val swap = "5023b4df" + word(usdc) + word(weth) + word(500) + word(SwapRouter02.ADDRESS_THIS) +
            word(fiveLink) + word(maximum) + word(0)
        val unwrap = "49404b7c" + word(fiveLink) + word(account)
        assertEquals(
            "5ae401dc" + word(deadline) + word(0x40) + word(2) + word(0x40) + word(0x160) +
                word(0xe4) + swap + "00".repeat(28) + word(0x44) + unwrap + "00".repeat(28),
            call.data.toHex(),
        )
    }

    @Test
    fun aBuysRouteAndAmountsAreChecked() {
        fun buy(
            sell: TransferAsset,
            buy: TransferAsset,
            route: SwapRoute,
            out: UInt256 = fiveLink,
            most: UInt256 = maximum,
        ) = SwapRouter02.buy(deployment, sell, buy, route, out, most, account, deadline)

        assertFailsWith<IllegalArgumentException> {
            buy(linkToken, usdcToken, SwapRoute.direct(usdc, link, FeeTier.LOW))
        }
        assertFailsWith<IllegalArgumentException>("nothing bought") {
            buy(usdcToken, linkToken, hop, out = UInt256.ZERO)
        }
        assertFailsWith<IllegalArgumentException>("no maximum") { buy(usdcToken, linkToken, hop, most = UInt256.ZERO) }
    }

    @Test
    fun whatABuyTookIsReadFromTheMulticallsResultsOnly() {
        val amount = UInt256.of(100_400_000)
        val one = word(0x20) + word(1) + word(0x20) + word(0x20) + word(amount)
        // The same with refundETH's or unwrapWETH9's empty result after it.
        val two = word(0x20) + word(2) + word(0x40) + word(0x80) + word(0x20) + word(amount) + word(0)
        assertEquals(amount, SwapRouter02.amountInOf(one, sellsEther = false, buysEther = false))
        assertEquals(amount, SwapRouter02.amountInOf(two, sellsEther = true, buysEther = false))
        assertEquals(amount, SwapRouter02.amountInOf(two, sellsEther = false, buysEther = true))
        assertNull(SwapRouter02.amountInOf(one, sellsEther = true, buysEther = false), "the refund's result missing")
        assertNull(SwapRouter02.amountInOf(two, sellsEther = false, buysEther = false), "a result more")
        assertNull(SwapRouter02.amountInOf("", sellsEther = false, buysEther = false))
    }
}
