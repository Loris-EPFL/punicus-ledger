package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSigningCodeReads
import eth.serotine.domain.model.CodePin
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.CodeReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [VerifiedContractCodes] against [FakeChain]: the code, the pointer and the implementation, each proven. */
class VerifiedContractCodesTest {

    private val chain = FakeChain()
    private val proxy = Address.of("0x" + "aa".repeat(20))
    private val implementation = Address.of("0x" + "bb".repeat(20))
    private val word = "0x" + "0".repeat(24) + "bb".repeat(20)
    private val slot = "0x" + "36".repeat(32)

    private fun TestScope.codes() = VerifiedContractCodes(Network.ETHEREUM, chain, StandardTestDispatcher(testScheduler))

    @Test
    fun aContractsCodeIsProvenAndHashed() = runTest {
        chain.snapshot = running()
        chain.codes[proxy] = ProvenCode("6001", 100)
        assertEquals(
            CodeReading.Read(ClearSigningCodeReads("0x" + codeHash("6001"), null, null)),
            codes().read(Network.ETHEREUM, proxy, CodePin.Fixed),
        )
    }

    @Test
    fun aSlotProxysPointerAndItsImplementationAreReadToo() = runTest {
        chain.snapshot = running()
        chain.codes[proxy] = ProvenCode("6001", 100)
        chain.codes[implementation] = ProvenCode("6002", 100)
        chain.storage[proxy to slot.removePrefix("0x")] = word.removePrefix("0x")
        assertEquals(
            CodeReading.Read(ClearSigningCodeReads("0x" + codeHash("6001"), word, "0x" + codeHash("6002"))),
            codes().read(Network.ETHEREUM, proxy, CodePin.Slot(slot, implementation, "0x00")),
        )
    }

    @Test
    fun aCallProxysPointerIsHeldToTheBlockItsCodeWasProvenAt() = runTest {
        chain.snapshot = running()
        chain.codes[proxy] = ProvenCode("6001", 100)
        chain.codes[implementation] = ProvenCode("6002", 100)
        val pin = CodePin.Call("0x5c60da1b", word, "0x00")
        chain.callsAt[Triple(proxy, "5c60da1b", 100L)] = CallOutcome.Returned(word, 100, null)
        assertEquals(
            CodeReading.Read(ClearSigningCodeReads("0x" + codeHash("6001"), word, "0x" + codeHash("6002"))),
            codes().read(Network.ETHEREUM, proxy, pin),
        )
        chain.callsAt[Triple(proxy, "5c60da1b", 100L)] = CallOutcome.Returned(word, 101, null)
        assertEquals(CodeReading.Unavailable(ReadUnavailable.UNPROVEN), codes().read(Network.ETHEREUM, proxy, pin), "another block")
    }

    @Test
    fun whatIsNotProvenIsNoAnswer() = runTest {
        chain.snapshot = running()
        assertEquals(CodeReading.Unavailable(ReadUnavailable.UNPROVEN), codes().read(Network.ETHEREUM, proxy, CodePin.Fixed))
        chain.codes[proxy] = ProvenCode("6001", 100)
        assertEquals(
            CodeReading.Unavailable(ReadUnavailable.UNPROVEN),
            codes().read(Network.ETHEREUM, proxy, CodePin.Slot(slot, implementation, "0x00").also {
                chain.storage[proxy to slot.removePrefix("0x")] = word.removePrefix("0x")
            }),
            "the implementation's code not proven",
        )
    }

    @Test
    fun anotherNetworkOrNoEngineIsRefused() = runTest {
        chain.snapshot = running()
        assertEquals(CodeReading.Unavailable(ReadUnavailable.NO_ENGINE), codes().read(Network.GNOSIS, proxy, CodePin.Fixed))
        assertEquals(
            CodeReading.Unavailable(ReadUnavailable.NO_ENGINE),
            VerifiedContractCodes(Network.ETHEREUM, null, StandardTestDispatcher(testScheduler)).read(Network.ETHEREUM, proxy, CodePin.Fixed),
        )
    }
}
