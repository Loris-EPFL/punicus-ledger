package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TraceFrame
import eth.serotine.domain.model.TraceLog
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CallTraceReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [CallTraceJson] and [EngineCallTraces]: the engine's `callTracer` answer, read exactly (docs/clear-signing.md, *Phase B*). */
class CallTracesTest {

    private val user = Address.of("0x" + "aa".repeat(20))
    private val router = Address.of("0x" + "01".repeat(20))
    private val token = Address.of("0x" + "02".repeat(20))
    private val topic = "dd".repeat(32)

    private val json = """
        {"type":"CALL","from":"${user.hex}","to":"${router.hex}","value":"0x10","gas":"0x5208","gasUsed":"0x5208",
         "input":"0x","output":"0x",
         "calls":[
           {"type":"DELEGATECALL","from":"${router.hex}","to":"${token.hex}","gas":"0x1","gasUsed":"0x1","input":"0x",
            "logs":[{"address":"${router.hex}","topics":["0x$topic"],"data":"0x01","position":"0x0"}]},
           {"type":"CREATE2","from":"${router.hex}","value":"0x0","error":"execution reverted","revertReason":"no"}
         ]}
    """.trimIndent()

    @Test
    fun aCallTracerAnswerIsReadFrameByFrame() {
        assertEquals(
            TraceFrame(
                TraceFrame.Type.CALL, user, router, UInt256.of(16),
                calls = listOf(
                    TraceFrame(TraceFrame.Type.DELEGATECALL, router, token, null, logs = listOf(TraceLog(router, listOf(topic), "01"))),
                    TraceFrame(TraceFrame.Type.CREATE2, router, null, UInt256.ZERO, error = "execution reverted", revertReason = "no"),
                ),
            ),
            CallTraceJson.parse(json),
        )
    }

    @Test
    fun anythingNotExactlyATraceIsNoAnswer() {
        assertNull(CallTraceJson.parse("not json"))
        assertNull(CallTraceJson.parse(json.replace("DELEGATECALL", "TELEPORT")))
        assertNull(CallTraceJson.parse(json.replace("\"value\":\"0x10\"", "\"value\":\"16\"")))
        assertNull(CallTraceJson.parse(json.replace("0x$topic", "0x01")))
        // A failure is said in words, and a frame that moves value says how much.
        assertNull(CallTraceJson.parse(json.replace("\"error\":\"execution reverted\"", "\"error\":{\"code\":3}")))
        assertNull(CallTraceJson.parse(json.replace("\"value\":\"0x10\",", "")))
        // Only a creation may have no recipient.
        assertNull(CallTraceJson.parse("""{"type":"CALL","from":"${user.hex}"}"""))
        assertNull(CallTraceJson.parse(json.replace("\"calls\":[", "\"calls\":{\"a\":[") + "}"))
        assertNull(CallTraceJson.parse(json.replace(user.hex, "0x1234")))
        assertNull(CallTraceJson.parse(json.replace("\"data\":\"0x01\"", "\"data\":7")))
        // A creation with no address that does not say it failed.
        assertNull(CallTraceJson.parse(json.replace(",\"error\":\"execution reverted\",\"revertReason\":\"no\"", "")))
        assertNull(CallTraceJson.parse(" ".repeat((16 shl 20) + 1)))
    }

    private val chain = FakeTransactingChain()

    private fun TestScope.traces() = EngineCallTraces(Network.ETHEREUM, chain, StandardTestDispatcher(testScheduler))

    private suspend fun TestScope.trace() = traces().trace(Network.ETHEREUM, user, router, UInt256.of(16), byteArrayOf())

    @Test
    fun aTraceIsAskedAtTheVerifiedHeadByItsNumber() = runTest {
        chain.base.snapshot = running(head = 1_234)
        chain.trace = TraceAnswer.Traced(json)
        val traced = trace()
        assertEquals(1_234, (traced as CallTraceReading.Traced).blockNumber)
        assertEquals(listOf(1_234L), chain.tracedAt)
    }

    @Test
    fun eachAnswerOfTheEngineIsSaidSo() = runTest {
        chain.base.snapshot = running()
        chain.trace = TraceAnswer.NoTracer
        assertEquals(CallTraceReading.NotTraced, trace())
        chain.trace = TraceAnswer.Infeasible("insufficient funds")
        assertEquals(CallTraceReading.Infeasible("insufficient funds"), trace())
        chain.trace = TraceAnswer.Refused("block out of range")
        assertEquals(CallTraceReading.Unavailable(ReadUnavailable.UNPROVEN), trace())
        // A trace of another call than the one asked for is no answer.
        chain.trace = TraceAnswer.Traced(json.replace("\"value\":\"0x10\"", "\"value\":\"0x11\""))
        assertEquals(CallTraceReading.Unavailable(ReadUnavailable.UNPROVEN), trace())
        chain.trace = TraceAnswer.Unavailable("no peer")
        assertEquals(CallTraceReading.Unavailable(ReadUnavailable.UNPROVEN), trace())
        chain.trace = TraceAnswer.Traced("{}")
        assertEquals(CallTraceReading.Unavailable(ReadUnavailable.UNPROVEN), trace())
    }

    @Test
    fun nothingIsTracedOffAFreshHeadOrOnAnotherNetwork() = runTest {
        chain.base.snapshot = running(headAgeMs = 600_000)
        chain.trace = TraceAnswer.Traced(json)
        assertEquals(CallTraceReading.Unavailable(ReadUnavailable.UNPROVEN), trace())
        assertEquals(
            CallTraceReading.Unavailable(ReadUnavailable.NO_ENGINE),
            traces().trace(Network.GNOSIS, user, router, UInt256.ZERO, byteArrayOf()),
        )
        assertEquals(emptyList(), chain.tracedAt)
    }
}
