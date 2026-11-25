package eth.serotine.domain.usecase

import eth.serotine.domain.model.AbiDecoder
import eth.serotine.domain.model.AbiFunction
import eth.serotine.domain.model.AbiType
import eth.serotine.domain.model.AbiValue
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSignResult
import eth.serotine.domain.model.ClearSignedField
import eth.serotine.domain.model.ClearSigningCodeReads
import eth.serotine.domain.model.ClearSigningDeployment
import eth.serotine.domain.model.ClearSigningDescriptor
import eth.serotine.domain.model.ClearSigningField
import eth.serotine.domain.model.ClearSigningFormat
import eth.serotine.domain.model.ClearSigningPath
import eth.serotine.domain.model.CodePin
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.FieldFormat
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NotCoveredReason
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Visibility
import eth.serotine.domain.model.hexToBytes
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.ClearSigningCatalog
import eth.serotine.domain.repository.CodeReading
import eth.serotine.domain.repository.ContractCodes
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenCatalog
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** [DescribeClearSigning]'s steps, each failing closed (docs/clear-signing.md, A3, A4), with fake ports. */
class DescribeClearSigningTest {

    private val user = Address.of("0x" + "aa".repeat(20))
    private val pool = Address.of("0x" + "01".repeat(20))
    private val other = Address.of("0x" + "02".repeat(20))
    private val usdc = Address.of("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")
    private val codeHash = "0x" + "11".repeat(32)

    private val supply = AbiFunction.parse("supply(address asset,uint256 amount,bytes call)")!!

    private val descriptor = ClearSigningDescriptor(
        source = "test", sha256 = "00", owner = "Pool DAO", kind = ClearSigningDescriptor.Kind.CALLDATA,
        domainName = null, domainVersion = null, enums = emptyMap(),
        formats = listOf(
            ClearSigningFormat(
                key = "supply(address asset,uint256 amount,bytes call)", function = supply, selector = supply.selector,
                encodeType = null, typeHash = null, primaryType = null, intent = "Supply", interpolatedIntent = null,
                fields = listOf(
                    ClearSigningField(
                        ClearSigningPath.parse("amount"), "Amount",
                        FieldFormat.TokenAmount(ClearSigningPath.parse("asset"), null, emptySet(), null, null), Visibility.Shown,
                    ),
                    ClearSigningField(ClearSigningPath.parse("call"), "Then", FieldFormat.Calldata(ClearSigningPath.parse("asset")!!), Visibility.Shown),
                ),
            ),
        ),
    )

    private fun deployment(address: Address) =
        ClearSigningDeployment(Network.ETHEREUM, "Pool", address, codeHash, CodePin.Fixed, listOf(descriptor))

    private val catalog = object : ClearSigningCatalog {
        override fun deployment(network: Network, address: Address) =
            if (network == Network.ETHEREUM && address in setOf(pool, usdc)) deployment(address) else null
    }

    private val codeHashes = mutableMapOf<Address, CodeReading>()
    private val codes = object : ContractCodes {
        override suspend fun read(network: Network, address: Address, pin: CodePin) =
            codeHashes[address] ?: CodeReading.Read(ClearSigningCodeReads(codeHash, null, null))
    }

    private val tokens = object : TokenCatalog {
        override fun observeTokens(network: Network) = flowOf(listOf(Token(usdc, "USDC", "USD Coin", 6, null)))
    }

    private val kinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address) = flowOf<KindReading>(KindReading.Syncing)
    }

    private val describe = DescribeClearSigning(catalog, codes, tokens, kinds)

    private fun supplyData(asset: Address, then: ByteArray = ByteArray(0)): ByteArray =
        supply.selector.removePrefix("0x").hexToBytes() + AbiDecoder.encode(
            AbiType.TupleType(supply.params),
            AbiValue.TupleValue(
                listOf(
                    "asset" to AbiValue.AddressValue(asset),
                    "amount" to AbiValue.UintValue(UInt256.of(1_500_000), 256),
                    "call" to AbiValue.BytesValue(then.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }, fixed = false),
                ),
            ),
        )

    @Test
    fun whatAHandWrittenDecoderClaimsIsLeftToIt() = runTest {
        val claimed = ClearSignResult.NotCovered(NotCoveredReason.ClaimedByAppDecoder)
        assertEquals(claimed, describe.call(Network.ETHEREUM, user, pool, UInt256.ZERO, Erc20.transferData(other, UInt256.ONE)))
        assertEquals(claimed, describe.call(Network.ETHEREUM, user, pool, UInt256.ZERO, Erc20.approveData(other, UInt256.ONE)))
        assertEquals(claimed, describe.call(Network.ETHEREUM, user, pool, UInt256.ONE, ByteArray(0)))
    }

    @Test
    fun aContractTheCatalogLacksIsNotDescribed() = runTest {
        assertEquals(
            ClearSignResult.NotCovered(NotCoveredReason.NoDescriptor),
            describe.call(Network.ETHEREUM, user, other, UInt256.ZERO, supplyData(usdc)),
        )
        assertEquals(
            ClearSignResult.NotCovered(NotCoveredReason.NoDescriptor),
            describe.call(Network.GNOSIS, user, pool, UInt256.ZERO, supplyData(usdc)),
        )
    }

    @Test
    fun codeNotProvenOrChangedIsNotDescribed() = runTest {
        codeHashes[pool] = CodeReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(
            ClearSignResult.NotCovered(NotCoveredReason.CodeNotProven),
            describe.call(Network.ETHEREUM, user, pool, UInt256.ZERO, supplyData(usdc)),
        )
        codeHashes[pool] = CodeReading.Read(ClearSigningCodeReads("0x" + "22".repeat(32), null, null))
        assertEquals(
            ClearSignResult.NotCovered(NotCoveredReason.ContractChanged),
            describe.call(Network.ETHEREUM, user, pool, UInt256.ZERO, supplyData(usdc)),
        )
    }

    @Test
    fun aBoundContractsCallIsDescribedWithTokensFromTheBuiltInList() = runTest {
        val signed = assertIs<ClearSignResult.ClearSigned>(describe.call(Network.ETHEREUM, user, pool, UInt256.ZERO, supplyData(usdc)))
        assertEquals("Supply", signed.intent)
        assertEquals("1.5 USDC", (signed.fields[0] as ClearSignedField.Value).shown)
    }

    @Test
    fun aNestedCallsCalleeIsBoundItself() = runTest {
        codeHashes[usdc] = CodeReading.Read(ClearSigningCodeReads("0x" + "33".repeat(32), null, null))
        val signed = assertIs<ClearSignResult.ClearSigned>(
            describe.call(Network.ETHEREUM, user, pool, UInt256.ZERO, supplyData(usdc, then = supplyData(usdc))),
        )
        val nested = signed.fields[1] as ClearSignedField.Nested
        assertEquals(ClearSignResult.NotCovered(NotCoveredReason.ContractChanged), nested.result)
        codeHashes.remove(usdc)
        val bound = assertIs<ClearSignResult.ClearSigned>(
            describe.call(Network.ETHEREUM, user, pool, UInt256.ZERO, supplyData(usdc, then = supplyData(usdc))),
        )
        assertIs<ClearSignResult.ClearSigned>((bound.fields[1] as ClearSignedField.Nested).result)
    }
}
