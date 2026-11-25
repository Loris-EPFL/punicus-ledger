package eth.serotine.domain.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The formatter of docs/clear-signing.md, A3, against vectors of the ERC-7730 registry (its `testsv2` files
 * for Uniswap's SwapRouter02 and Permit2, at the commit scripts/clear-signing pins), with the descriptors
 * written out by hand as the catalog's reader will build them.
 */
class ClearSignFormatterTest {

    private fun path(text: String) = ClearSigningPath.parse(text)!!

    private val weth = Address.of("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2")
    private val usdt = Address.of("0xdac17f958d2ee523a2206206994597c13d831ec7")
    private val usdc = Address.of("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")
    private val router = Address.of("0x68b3465833fb72A70ecDF485E0e4C7bD8665Fc45")
    private val sender = Address.of("0x" + "aa".repeat(20))

    private val exactInputSingle = ClearSigningFormat(
        key = "exactInputSingle((address tokenIn, address tokenOut, uint24 fee, address recipient, uint256 amountIn, " +
            "uint256 amountOutMinimum, uint160 sqrtPriceLimitX96) params)",
        function = AbiFunction.parse(
            "exactInputSingle((address tokenIn, address tokenOut, uint24 fee, address recipient, uint256 amountIn, " +
                "uint256 amountOutMinimum, uint160 sqrtPriceLimitX96) params)",
        ),
        selector = "0x04e45aaf",
        encodeType = null,
        typeHash = null,
        primaryType = null,
        intent = "swap",
        interpolatedIntent = "Swap {params.amountIn} for at least {params.amountOutMinimum}",
        fields = listOf(
            field("params.amountIn", "Send", FieldFormat.TokenAmount(path("params.tokenIn"), null, emptySet(), null, null)),
            field("params.amountOutMinimum", "Minimum to Receive", FieldFormat.TokenAmount(path("params.tokenOut"), null, emptySet(), null, null)),
            field("params.fee", "Uniswap fee", FieldFormat.Unit("%", 4, prefix = false)),
            field("params.recipient", "Beneficiary", FieldFormat.AddressName(emptySet())),
            field("params.sqrtPriceLimitX96", "Price Limit", FieldFormat.Raw, Visibility.MustMatch(setOf("0"))),
        ),
    )

    private fun field(path: String, label: String, format: FieldFormat, visibility: Visibility = Visibility.Shown) =
        ClearSigningField(path(path), label, format, visibility)

    private val swapRouter = ClearSigningDescriptor(
        source = "registry/uniswap/calldata-UniswapV3Router02.json",
        sha256 = "00",
        owner = "Uniswap Labs",
        kind = ClearSigningDescriptor.Kind.CALLDATA,
        domainName = null,
        domainVersion = null,
        enums = emptyMap(),
        formats = listOf(exactInputSingle),
    )

    // The registry's vector "swap - chain 1" (tx 0xb25281ab…0b30): its call data.
    private val swapData = (
        "04e45aaf000000000000000000000000c02aaa39b223fe8d0a0e5c4f27ead9083c756cc2000000000000000000000000dac17f958d2e" +
            "e523a2206206994597c13d831ec70000000000000000000000000000000000000000000000000000000000000bb8000000000000000000" +
            "000000eced4025456b6c2987fac2e4c829889e681986a70000000000000000000000000000000000000000000000000016fdae8fefba69" +
            "0000000000000000000000000000000000000000000000000000000000d41da00000000000000000000000000000000000000000000000" +
            "000000000000000000"
        ).hexToBytes()

    private class Lookups(
        val tokens: Map<Address, ClearSigningToken>,
        val nested: ClearSignResult = ClearSignResult.NotCovered(NotCoveredReason.NoDescriptor),
    ) : ClearSigningLookups {
        val nestedCalls = mutableListOf<Pair<Address, Int>>()
        override fun token(network: Network, token: Address) = tokens[token]
        override fun addressName(network: Network, address: Address): String? = null
        override suspend fun collectionName(network: Network, collection: Address): String? = null
        override suspend fun nested(network: Network, from: Address, callee: Address, value: UInt256, data: ByteArray, depth: Int): ClearSignResult {
            nestedCalls += callee to depth
            return nested
        }
    }

    private val listed = Lookups(mapOf(weth to ClearSigningToken("WETH", 18), usdt to ClearSigningToken("USDT", 6)))

    private suspend fun swap(lookups: ClearSigningLookups = listed, data: ByteArray = swapData) =
        ClearSignFormatter.formatCall(swapRouter, Network.ETHEREUM, sender, router, UInt256.ZERO, data, lookups)

    @Test
    fun theRegistrysSwapVectorReadsAsItsTestExpects() = runTest {
        val signed = assertIs<ClearSignResult.ClearSigned>(swap())
        assertEquals("swap", signed.intent)
        assertEquals("Uniswap Labs", signed.owner)
        assertEquals(
            listOf(
                "Send" to "0.006471375668623977 WETH",
                "Minimum to Receive" to "13.901216 USDT",
                "Uniswap fee" to "0.3%",
                "Beneficiary" to "0xEceD4025456B6c2987faC2e4c829889e681986a7",
            ),
            signed.fields.map { it.label to (it as ClearSignedField.Value).shown },
        )
        assertEquals("Swap 0.006471375668623977 WETH for at least 13.901216 USDT", signed.interpolatedIntent)
        assertEquals(false, signed.grantsApproval)
    }

    @Test
    fun eachValueKeepsItsRawFormAndNothingIsHidden() = runTest {
        val signed = assertIs<ClearSignResult.ClearSigned>(swap())
        assertEquals("6471375668623977", (signed.fields[0] as ClearSignedField.Value).raw)
        assertEquals("3000", (signed.fields[2] as ClearSignedField.Value).raw)
        // The tokens, shown by no field, and the price limit, which `mustMatch` keeps off the fields.
        assertEquals(
            listOf(
                OtherParameter("params.tokenIn", "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"),
                OtherParameter("params.tokenOut", "0xdAC17F958D2ee523a2206206994597C13D831ec7"),
                OtherParameter("params.sqrtPriceLimitX96", "0"),
            ),
            signed.otherParameters,
        )
    }

    @Test
    fun aTokenOutsideTheBuiltInListIsShownByItsAddressInRawUnits() = runTest {
        val signed = assertIs<ClearSignResult.ClearSigned>(swap(Lookups(mapOf(weth to ClearSigningToken("WETH", 18)))))
        val minimum = signed.fields[1] as ClearSignedField.Value
        assertEquals("13901216 (raw units) of 0xdAC17F958D2ee523a2206206994597C13D831ec7", minimum.shown)
        assertEquals(usdt, minimum.notInTokenList)
    }

    @Test
    fun aValueOutsideWhatTheDescriptorWasWrittenForIsNotCovered() = runTest {
        val limited = swapData.copyOf().also { it[it.size - 1] = 1 }
        assertEquals(
            ClearSignResult.NotCovered(NotCoveredReason.ValueOutsideDescriptor("Price Limit")),
            swap(data = limited),
        )
    }

    @Test
    fun bytesThatAreNotExactlyTheCallAreNotCovered() = runTest {
        assertEquals(ClearSignResult.NotCovered(NotCoveredReason.NotDecodable), swap(data = swapData + byteArrayOf(0)))
        assertEquals(ClearSignResult.NotCovered(NotCoveredReason.TooShort), swap(data = byteArrayOf(0, 0, 0)))
        assertEquals(
            ClearSignResult.NotCovered(NotCoveredReason.NoFormat("0xa9059cbb")),
            swap(data = Erc20.transferData(sender, UInt256.ONE)),
        )
    }

    private val permitSingle = ClearSigningFormat(
        key = PERMIT_SINGLE,
        function = null,
        selector = null,
        encodeType = PERMIT_SINGLE,
        typeHash = "0xf3841cd1ff0085026a6327b620b67997ce40f282c88a8e905a7a5626e310f3d0",
        primaryType = "PermitSingle",
        intent = "Authorize spending of token",
        interpolatedIntent = null,
        fields = listOf(
            field("spender", "Spender", FieldFormat.Raw),
            field(
                "details.amount", "Amount allowance",
                FieldFormat.TokenAmount(path("details.token"), null, emptySet(), UInt256.fromBigEndian(ByteArray(20) { 0xff.toByte() }), "Unlimited"),
            ),
            field("details.expiration", "Approval expires", FieldFormat.Date(blockHeight = false)),
            field("sigDeadline", "Sig Deadline", FieldFormat.Raw, Visibility.Hidden),
        ),
    )

    private val permit2 = ClearSigningDescriptor(
        source = "registry/uniswap/eip712-uniswap-permit2.json",
        sha256 = "00",
        owner = "Uniswap Labs",
        kind = ClearSigningDescriptor.Kind.EIP712,
        domainName = "Permit2",
        domainVersion = null,
        enums = emptyMap(),
        formats = listOf(permitSingle),
    )

    private fun permitData(amount: String, chainId: String? = "1", verifier: String? = "0x000000000022D473030F116dDEE9F6B43aC78BA3") =
        TypedData(
            types = mapOf(
                "EIP712Domain" to listOf(TypedField("name", "string"), TypedField("chainId", "uint256"), TypedField("verifyingContract", "address")),
                "PermitDetails" to listOf(
                    TypedField("token", "address"), TypedField("amount", "uint160"),
                    TypedField("expiration", "uint48"), TypedField("nonce", "uint48"),
                ),
                "PermitSingle" to listOf(TypedField("details", "PermitDetails"), TypedField("spender", "address"), TypedField("sigDeadline", "uint256")),
            ),
            primaryType = "PermitSingle",
            domain = buildMap {
                put("name", TypedValue.Text("Permit2"))
                chainId?.let { put("chainId", TypedValue.Number(it)) }
                verifier?.let { put("verifyingContract", TypedValue.Text(it)) }
            },
            message = mapOf(
                "details" to TypedValue.Struct(
                    mapOf(
                        "token" to TypedValue.Text("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"),
                        "amount" to TypedValue.Text(amount),
                        "expiration" to TypedValue.Number("1782864000"),
                        "nonce" to TypedValue.Number("7"),
                    ),
                ),
                "spender" to TypedValue.Text("0xE592427A0AEce92De3Edee1F18E0157C05861564"),
                "sigDeadline" to TypedValue.Number("1774915200"),
            ),
        )

    private val withUsdc = Lookups(mapOf(usdc to ClearSigningToken("USDC", 6)))

    @Test
    fun theRegistrysPermitVectorReadsAsItsTestExpects() = runTest {
        val signed = assertIs<ClearSignResult.ClearSigned>(
            ClearSignFormatter.formatTypedData(permit2, Network.ETHEREUM, sender, permitData("2500000000"), withUsdc),
        )
        assertEquals("Authorize spending of token", signed.intent)
        assertEquals(
            listOf(
                "Spender" to "0xE592427A0AEce92De3Edee1F18E0157C05861564",
                "Amount allowance" to "2500 USDC",
                "Approval expires" to "2026-07-01 00:00:00Z",
            ),
            signed.fields.map { it.label to (it as ClearSignedField.Value).shown },
        )
        assertTrue(signed.grantsApproval)
        assertEquals(
            listOf("details.token", "details.nonce", "sigDeadline"),
            signed.otherParameters.map { it.location },
        )
    }

    @Test
    fun anAmountAtTheThresholdIsShownAsItsMessage() = runTest {
        val max = UInt256.fromBigEndian(ByteArray(20) { 0xff.toByte() }).toDecimal()
        val signed = assertIs<ClearSignResult.ClearSigned>(
            ClearSignFormatter.formatTypedData(permit2, Network.ETHEREUM, sender, permitData(max), withUsdc),
        )
        assertEquals("Unlimited USDC", (signed.fields[1] as ClearSignedField.Value).shown)
    }

    @Test
    fun aDomainWithoutItsChainOrContractIsNotCovered() = runTest {
        assertEquals(
            ClearSignResult.NotCovered(NotCoveredReason.DomainWithoutChainId),
            ClearSignFormatter.formatTypedData(permit2, Network.ETHEREUM, sender, permitData("1", chainId = null), withUsdc),
        )
        assertEquals(
            ClearSignResult.NotCovered(NotCoveredReason.DomainWithoutVerifyingContract),
            ClearSignFormatter.formatTypedData(permit2, Network.ETHEREUM, sender, permitData("1", verifier = null), withUsdc),
        )
        assertEquals(
            ClearSignResult.NotCovered(NotCoveredReason.OtherChain),
            ClearSignFormatter.formatTypedData(permit2, Network.GNOSIS, sender, permitData("1"), withUsdc),
        )
    }

    private val multicall = ClearSigningDescriptor(
        source = "test",
        sha256 = "00",
        owner = "Test",
        kind = ClearSigningDescriptor.Kind.CALLDATA,
        domainName = null,
        domainVersion = null,
        enums = mapOf("mode" to mapOf("2" to "variable")),
        formats = listOf(
            ClearSigningFormat(
                key = "call(address target,bytes data,uint8 mode,uint64 until)",
                function = AbiFunction.parse("call(address target,bytes data,uint8 mode,uint64 until)"),
                selector = AbiFunction.parse("call(address target,bytes data,uint8 mode,uint64 until)")!!.selector,
                encodeType = null, typeHash = null, primaryType = null,
                intent = "Call",
                interpolatedIntent = null,
                fields = listOf(
                    field("data", "Call", FieldFormat.Calldata(path("target"))),
                    field("mode", "Mode", FieldFormat.Enum("mode")),
                    field("until", "Until", FieldFormat.Duration),
                ),
            ),
        ),
    )

    private fun callData(mode: Long, inner: ByteArray): ByteArray {
        val function = multicall.formats.single().function!!
        return function.selector.removePrefix("0x").hexToBytes() + AbiDecoder.encode(
            AbiType.TupleType(function.params),
            AbiValue.TupleValue(
                listOf(
                    "target" to AbiValue.AddressValue(usdc),
                    "data" to AbiValue.BytesValue(inner.toHex(), fixed = false),
                    "mode" to AbiValue.UintValue(UInt256.of(mode), 8),
                    "until" to AbiValue.UintValue(UInt256.of(5951 * 3600L), 64),
                ),
            ),
        )
    }

    @Test
    fun aNestedCallIsAskedOfItsCalleeAndAnApprovalInsideIsFlagged() = runTest {
        val approval = ClearSignResult.ClearSigned("Inner", emptyList(), "Approve", null, emptyList(), emptyList(), grantsApproval = true)
        val lookups = Lookups(emptyMap(), nested = approval)
        val signed = assertIs<ClearSignResult.ClearSigned>(
            ClearSignFormatter.formatCall(multicall, Network.ETHEREUM, sender, router, UInt256.ZERO, callData(2, byteArrayOf(1, 2, 3, 4)), lookups),
        )
        assertEquals(listOf(usdc to 1), lookups.nestedCalls)
        assertEquals(approval, (signed.fields[0] as ClearSignedField.Nested).result)
        assertEquals("variable", (signed.fields[1] as ClearSignedField.Value).shown)
        assertEquals("5951:00:00", (signed.fields[2] as ClearSignedField.Value).shown)
        assertTrue(signed.grantsApproval)
    }

    @Test
    fun aValueItsEnumHasNoNameForIsNotCovered() = runTest {
        assertEquals(
            ClearSignResult.NotCovered(NotCoveredReason.UnknownEnumValue("Mode")),
            ClearSignFormatter.formatCall(multicall, Network.ETHEREUM, sender, router, UInt256.ZERO, callData(9, byteArrayOf()), listed),
        )
    }

    @Test
    fun callsNestedTooDeepAreNotFormatted() = runTest {
        assertEquals(
            ClearSignResult.NotCovered(NotCoveredReason.TooDeep),
            ClearSignFormatter.formatCall(multicall, Network.ETHEREUM, sender, router, UInt256.ZERO, callData(2, byteArrayOf()), listed, depth = 4),
        )
    }

    @Test
    fun datesAndDurationsAreWrittenAsTheRegistrysVectorsWriteThem() {
        assertEquals("1970-01-01 00:00:00Z", ClearSignFormatter.utc(0))
        assertEquals("2026-03-31 00:00:00Z", ClearSignFormatter.utc(1_774_915_200))
        assertEquals("2000-02-29 23:59:59Z", ClearSignFormatter.utc(951_868_799))
        assertEquals("8784:00:00", ClearSignFormatter.duration(8784 * 3600L))
        assertEquals("0:01:05", ClearSignFormatter.duration(65))
    }

    private val pinned = ClearSigningDeployment(
        network = Network.ETHEREUM,
        name = "Pool",
        address = router,
        codeHash = "0x" + "11".repeat(32),
        codePin = CodePin.Slot("0x" + "36".repeat(32), usdc, "0x" + "22".repeat(32)),
        descriptors = emptyList(),
    )

    @Test
    fun aDescriptorBindsOnlyWhileTheCodeAndItsImplementationAreThePinnedOnes() {
        val word = "0x" + "0".repeat(24) + usdc.hex.removePrefix("0x")
        assertNull(ClearSigningBinding.check(pinned, ClearSigningCodeReads("0x" + "11".repeat(32), word, "0x" + "22".repeat(32))))
        assertEquals(
            NotCoveredReason.ContractChanged,
            ClearSigningBinding.check(pinned, ClearSigningCodeReads("0x" + "33".repeat(32), word, "0x" + "22".repeat(32))),
        )
        assertEquals(
            NotCoveredReason.ContractChanged,
            ClearSigningBinding.check(pinned, ClearSigningCodeReads("0x" + "11".repeat(32), "0x" + "00".repeat(32), "0x" + "22".repeat(32))),
            "upgraded to another implementation",
        )
        assertEquals(
            NotCoveredReason.ContractChanged,
            ClearSigningBinding.check(pinned, ClearSigningCodeReads("0x" + "11".repeat(32), word, "0x" + "44".repeat(32))),
        )
    }

    private companion object {
        const val PERMIT_SINGLE = "PermitSingle(PermitDetails details,address spender,uint256 sigDeadline)" +
            "PermitDetails(address token,uint160 amount,uint48 expiration,uint48 nonce)"
    }
}
