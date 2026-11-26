package eth.serotine.domain.usecase

import eth.serotine.domain.model.AbiDecoder
import eth.serotine.domain.model.AbiFunction
import eth.serotine.domain.model.AbiType
import eth.serotine.domain.model.AbiValue
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSignResult
import eth.serotine.domain.model.ClearSigningCodeReads
import eth.serotine.domain.model.CodePin
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NotCoveredReason
import eth.serotine.domain.model.NotNamedReason
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.VerifiedAbiResult
import eth.serotine.domain.model.VerifiedCode
import eth.serotine.domain.model.VerifiedContract
import eth.serotine.domain.model.hexToBytes
import eth.serotine.domain.repository.CodeReading
import eth.serotine.domain.repository.ContractCodes
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.VerifiedAbiCatalog
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [NameVerifiedCall]'s steps (docs/clear-signing.md, *Phase C*, *At runtime*), each failing closed, with fake ports. */
class NameVerifiedCallTest {

    private val multiSendAt = Address.of("0x38869bf66a61cf6bdb996a6ae40d5853fd43b526")
    private val delayAt = Address.of("0x" + "dd".repeat(20))
    private val mastercopy = Address.of("0x22d903fd45f441f51bcad198d14eba8a75ea1ef0")
    private val token = Address.of("0x" + "0a".repeat(20))

    private val multiSend = AbiFunction.parse("multiSend(bytes transactions)")!!
    private val exec = AbiFunction.parse("execTransactionFromModule(address to,uint256 value,bytes data,uint8 operation)")!!

    private val multiSendContract = VerifiedContract(Network.GNOSIS, "MultiSend", "0x" + "11".repeat(32), listOf(multiSend))
    private val delay = VerifiedContract(Network.GNOSIS, "Delay", "0x" + "22".repeat(32), listOf(exec))
    private val proxyHash = "0x" + "33".repeat(32)

    private val codeAt = mutableMapOf(
        multiSendAt to multiSendContract.codeHash,
        delayAt to proxyHash,
        mastercopy to delay.codeHash,
    )

    private val catalog = object : VerifiedAbiCatalog {
        override fun byCodeHash(network: Network, codeHash: String) = when {
            network != Network.GNOSIS -> null
            codeHash == multiSendContract.codeHash -> VerifiedCode.Contract(multiSendContract)
            codeHash == proxyHash -> VerifiedCode.Proxy(mastercopy, delay)
            else -> null
        }
    }
    private val codes = object : ContractCodes {
        override suspend fun read(network: Network, address: Address, pin: CodePin): CodeReading =
            codeAt[address]?.let { CodeReading.Read(ClearSigningCodeReads(it, null, null)) }
                ?: CodeReading.Unavailable(ReadUnavailable.UNPROVEN)
    }
    private val name = NameVerifiedCall(catalog, codes)

    private val noDescriptor = ClearSignResult.NotCovered(NotCoveredReason.NoDescriptor)

    private fun call(function: AbiFunction, vararg values: AbiValue): ByteArray =
        function.selector.removePrefix("0x").hexToBytes() +
            AbiDecoder.encode(AbiType.TupleType(function.params), AbiValue.TupleValue(values.map { null to it }))

    @Test
    fun aListedContractsCallIsNamedByItsVerifiedSource() = runTest {
        val data = call(multiSend, AbiValue.BytesValue("00", fixed = false))
        val named = assertIs<VerifiedAbiResult.Named>(name.call(Network.GNOSIS, multiSendAt, data, noDescriptor))
        assertEquals("MultiSend", named.contract)
        assertEquals("multiSend", named.function)
        assertEquals(listOf("transactions" to "bytes"), named.parameters.map { it.name to it.type })
        assertEquals("0x00", named.parameters.single().raw)
    }

    @Test
    fun whatADecoderOrADescriptorSaysStands() = runTest {
        val data = call(multiSend, AbiValue.BytesValue("00", fixed = false))
        for (description in listOf(
            ClearSignResult.NotCovered(NotCoveredReason.ClaimedByAppDecoder),
            ClearSignResult.NotCovered(NotCoveredReason.ContractChanged),
            ClearSignResult.NotCovered(NotCoveredReason.CodeNotProven),
        )) {
            assertEquals(VerifiedAbiResult.NotNamed(NotNamedReason.SPOKEN_FOR), name.call(Network.GNOSIS, multiSendAt, data, description))
        }
        assertIs<VerifiedAbiResult.Named>(
            name.call(Network.GNOSIS, multiSendAt, data, ClearSignResult.NotCovered(NotCoveredReason.NoFormat("0x8d80ff0a"))),
        )
    }

    @Test
    fun aMinimalProxyIsNamedOnlyWhileItsImplementationIsThePinnedCode() = runTest {
        val approve = "095ea7b3" + "00".repeat(12) + "bb".repeat(20) + "ff".repeat(32)
        val data = call(
            exec, AbiValue.AddressValue(token), AbiValue.UintValue(UInt256.ZERO, 256),
            AbiValue.BytesValue(approve, fixed = false), AbiValue.UintValue(UInt256.ZERO, 8),
        )
        val named = assertIs<VerifiedAbiResult.Named>(name.call(Network.GNOSIS, delayAt, data, noDescriptor))
        assertEquals("execTransactionFromModule", named.function)
        // An approve inside its data: flagged, as far as an ABI lets the flag reach.
        assertTrue(named.approvalInBytes)
        assertTrue(named.approval)
        codeAt[mastercopy] = "0x" + "44".repeat(32)
        assertEquals(VerifiedAbiResult.NotNamed(NotNamedReason.NOT_ON_LIST), name.call(Network.GNOSIS, delayAt, data, noDescriptor))
    }

    @Test
    fun anythingElseIsNotNamedAndSaysWhy() = runTest {
        val data = call(multiSend, AbiValue.BytesValue("00", fixed = false))
        assertEquals(VerifiedAbiResult.NotNamed(NotNamedReason.NOT_ON_LIST), name.call(Network.ETHEREUM, multiSendAt, data, noDescriptor))
        assertEquals(VerifiedAbiResult.NotNamed(NotNamedReason.NOT_ON_LIST), name.call(Network.GNOSIS, token, data, noDescriptor))
        assertEquals(
            VerifiedAbiResult.NotNamed(NotNamedReason.NO_FUNCTION),
            name.call(Network.GNOSIS, multiSendAt, "12345678".hexToBytes(), noDescriptor),
        )
        assertEquals(
            VerifiedAbiResult.NotNamed(NotNamedReason.NOT_DECODABLE),
            name.call(Network.GNOSIS, multiSendAt, data + byteArrayOf(0), noDescriptor),
        )
        assertEquals(
            VerifiedAbiResult.NotNamed(NotNamedReason.NOT_DECODABLE),
            name.call(Network.GNOSIS, multiSendAt, byteArrayOf(1), noDescriptor),
        )
    }
}
