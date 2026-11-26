package eth.serotine.data.ledger

import eth.serotine.domain.repository.LedgerProblem
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LedgerHidExchangeTest {
    @Test
    fun splitsAnUnnumberedOutputAndReassemblesInputAcrossReports() {
        val command = ByteArray(58) { (it + 1).toByte() }
        val response = ByteArray(116) { (it + 1).toByte() }
        val reports = ReportScript({ replies(CHANNEL, response) })
        val result = LedgerHidExchange(reports, CHANNEL, Job()).exchange(command, response.size, 1_000)

        assertContentEquals(response, result)
        assertEquals(2, reports.writes.size)
        assertContentEquals("000101050000003a".hexToByteArray() + command.copyOfRange(0, 57), reports.writes[0])
        assertContentEquals("0001010500013a".hexToByteArray() + ByteArray(58), reports.writes[1])
    }

    @Test
    fun ignoresForeignChannelAndEmptyPollsWithoutSendingAgain() {
        val response = "9000".hexToByteArray()
        val reports = ReportScript({ listOf(byteArrayOf()) + replies(CHANNEL + 1, response) + replies(CHANNEL, response) })
        val result = LedgerHidExchange(reports, CHANNEL, Job()).exchange(byteArrayOf(1), response.size, 1_000)

        assertContentEquals(response, result)
        assertEquals(1, reports.writes.size)
        assertEquals(3, reports.reads)
    }

    @Test
    fun ignoresInputPaddingOutsideTheDeclaredLength() {
        val response = "9000".hexToByteArray()
        val reports = ReportScript({ replies(CHANNEL, response).onEach { it.fill(0x21, 9) } })
        val result = LedgerHidExchange(reports, CHANNEL, Job()).exchange(byteArrayOf(1), response.size, 1_000)
        assertContentEquals(response, result)
    }

    @Test
    fun rejectsMalformedCurrentChannelReports() {
        val response = ByteArray(58)
        val (first, second) = replies(CHANNEL, response)
        val scripts = listOf(
            listOf(first.copyOf(63)),
            listOf(first.copyOf(65)),
            listOf(first.copyOf().apply { this[2] = 4 }),
            listOf(first.copyOf().apply { this[4] = 1 }),
            listOf(first.copyOf().apply { this[6] = 1 }),
            listOf(first.copyOf().apply { this[6] = 59 }),
            listOf(first, second.copyOf().apply { this[4] = 2 }),
        )
        for (script in scripts) {
            val reports = ReportScript({ script })
            val failure = assertFailsWith<LedgerFailure> {
                LedgerHidExchange(reports, CHANNEL, Job()).exchange(byteArrayOf(1), response.size, 1_000)
            }
            assertEquals(LedgerProblem.INVALID_RESPONSE, failure.problem)
            assertEquals(1, reports.writes.size)
        }
    }

    @Test
    fun stopsOnShortWriteAndWhenDeadlineHasPassed() {
        val reports = ReportScript().apply { writeCount = 64 }
        val exchange = LedgerHidExchange(reports, CHANNEL, Job())
        val short = assertFailsWith<LedgerFailure> { exchange.exchange(byteArrayOf(1), 2, 1_000) }
        val timeout = assertFailsWith<LedgerFailure> { exchange.exchange(byteArrayOf(1), 2, 0) }
        assertEquals(LedgerProblem.INVALID_RESPONSE, short.problem)
        assertEquals(LedgerProblem.TIMED_OUT, timeout.problem)
        assertEquals(1, reports.writes.size)
        assertEquals(0, reports.reads)
    }

    private companion object {
        const val CHANNEL = 0x0101
    }
}

/** A script provides the input reports for each APDU, echoing the channel just sent. */
internal class ReportScript(vararg apdus: (Int) -> List<ByteArray>) : LedgerReports {
    private val scripts = ArrayDeque(apdus.asList())
    private val pending = ArrayDeque<ByteArray>()
    val writes = mutableListOf<ByteArray>()
    var writeCount = 65
    var reads = 0
        private set
    var closed = false
        private set

    override fun write(report: ByteArray): Int {
        writes += report.copyOf()
        return writeCount
    }

    override fun read(report: ByteArray, timeoutMillis: Int): Int {
        assertTrue(timeoutMillis in 1..100)
        reads++
        if (pending.isEmpty()) pending.addAll(scripts.removeFirst()(writes.last().uint16(1)))
        val next = pending.removeFirst()
        next.copyInto(report)
        return next.size
    }

    override fun close() {
        closed = true
    }
}

/** Device's numbered sequence, without a report ID prefix, as returned by HIDAPI. */
internal fun replies(channel: Int, reply: ByteArray): List<ByteArray> = buildList {
    val stream = byteArrayOf((reply.size shr 8).toByte(), reply.size.toByte()) + reply
    var offset = 0
    while (offset < stream.size) {
        val end = minOf(offset + 59, stream.size)
        val sequence = size
        add(ByteArray(64).apply {
            this[0] = (channel shr 8).toByte()
            this[1] = channel.toByte()
            this[2] = 5
            this[3] = (sequence shr 8).toByte()
            this[4] = sequence.toByte()
            stream.copyInto(this, 5, offset, end)
        })
        offset = end
    }
}
