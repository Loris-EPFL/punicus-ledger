package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import io.myotis.api.TraceResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a trace asks the JVM engine ([transactionArgs], [blockTag], [CALL_TRACER]) and how its answer is read. */
class TraceAnswersTest {

    private val from = Address.of("0x" + "aa".repeat(20))
    private val to = Address.of("0x" + "01".repeat(20))

    @Test
    fun theTransactionCarriesItsFourFieldsInBothViewsAndNothingElse() {
        val args = transactionArgs(from, to, "16", byteArrayOf(0x12, 0x34))
        assertContentEquals(from.bytes(), args.from())
        assertContentEquals(to.bytes(), args.to())
        assertContentEquals(byteArrayOf(0x12, 0x34), args.data())
        assertEquals("16", args.valueWei())
        assertNull(args.gas())
        assertNull(args.gasPriceWei())
        assertNull(args.maxFeePerGasWei())
        assertNull(args.maxPriorityFeePerGasWei())
        assertNull(args.nonce())
        assertEquals(false, args.hasExtendedFields())
        assertEquals("""{"from":"${from.hex}","to":"${to.hex}","input":"0x1234","value":"0x10"}""", args.json())
        assertEquals("""{"from":"${from.hex}","to":"${to.hex}","input":"0x","value":"0x0"}""", transactionArgs(from, to, "0", ByteArray(0)).json())
    }

    @Test
    fun theBlockIsNamedByItsNumberAndTheTracerByItsLogs() {
        assertEquals("0x15361c5", blockTag(22_241_733))
        assertEquals("""{"tracer":"callTracer","tracerConfig":{"withLog":true}}""", CALL_TRACER)
    }

    @Test
    fun everyTraceStatusIsToldApart() {
        assertEquals(TraceAnswer.Traced("{}"), traceAnswer(TraceResult.ok("{}")))
        assertEquals(TraceAnswer.Infeasible("insufficient funds"), traceAnswer(TraceResult.infeasible("insufficient funds")))
        assertEquals(TraceAnswer.Refused("no prestate"), traceAnswer(TraceResult.refused("no prestate")))
        assertEquals(TraceAnswer.Unavailable("no peer"), traceAnswer(TraceResult.unavailable("no peer")))
    }
}
