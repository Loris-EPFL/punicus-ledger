package eth.serotine.data.ledger

import eth.serotine.domain.repository.LedgerProblem
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

/** The two HIDAPI report calls on one open handle. A read returning 0 has received nothing yet. */
internal interface LedgerReports : AutoCloseable {
    fun write(report: ByteArray): Int
    fun read(report: ByteArray, timeoutMillis: Int): Int
}

internal class LedgerFailure(val problem: LedgerProblem) : RuntimeException()

/** Ledger's USB APDU framing (LedgerHQ/ledgerjs, `devices/src/hid-framing.ts`). */
internal class LedgerHidExchange(
    private val reports: LedgerReports,
    private val channel: Int,
    private val job: Job,
) {
    private val output = ByteArray(HIDAPI_BUFFER_SIZE) // HIDAPI requires the unnumbered report ID (0).
    private val input = ByteArray(HIDAPI_BUFFER_SIZE) // Detect an unexpected ID or overlong report.

    fun exchange(command: ByteArray, responseSize: Int, timeoutMillis: Int): ByteArray {
        val start = System.nanoTime()
        var sent = 0
        var sequence = 0
        while (sent < command.size) {
            remainingMillis(start, timeoutMillis)
            output.put16(1, channel)
            output[3] = APDU_TAG.toByte()
            output.put16(4, sequence)
            val dataOffset = if (sequence == 0) {
                output.put16(6, command.size)
                8
            } else {
                6
            }
            val count = minOf(command.size - sent, output.size - dataOffset)
            command.copyInto(output, dataOffset, sent, sent + count)
            output.fill(0, dataOffset + count)
            val written = reports.write(output)
            remainingMillis(start, timeoutMillis)
            if (written < 0) throw LedgerFailure(LedgerProblem.NOT_CONNECTED)
            if (written != output.size) throw LedgerFailure(LedgerProblem.INVALID_RESPONSE)
            sent += count
            ++sequence
        }

        sequence = 0
        var response: ByteArray? = null
        var received = 0
        while (true) {
            val remaining = remainingMillis(start, timeoutMillis)
            val read = reports.read(input, minOf(remaining, READ_SLICE_MILLIS))
            remainingMillis(start, timeoutMillis)
            if (read == 0) continue // Poll the outstanding reply; never resend a command.
            if (read < 0) throw LedgerFailure(LedgerProblem.NOT_CONNECTED)
            if (read != REPORT_SIZE) throw LedgerFailure(LedgerProblem.INVALID_RESPONSE)
            if (input.uint16(0) != channel) continue // A late reply to another handle cannot confirm this request.
            if (input.u8(2) != APDU_TAG || input.uint16(3) != sequence) {
                throw LedgerFailure(LedgerProblem.INVALID_RESPONSE)
            }
            val dataOffset = if (response == null) {
                val size = input.uint16(5)
                if (size !in STATUS_BYTES..responseSize) throw LedgerFailure(LedgerProblem.INVALID_RESPONSE)
                response = ByteArray(size)
                7
            } else {
                5
            }
            val count = minOf(response.size - received, REPORT_SIZE - dataOffset)
            input.copyInto(response, received, dataOffset, dataOffset + count)
            received += count
            if (received == response.size) return response // The rest of the report is transport padding.
            ++sequence
        }
    }

    private fun remainingMillis(start: Long, timeoutMillis: Int): Int {
        job.ensureActive()
        val elapsed = (System.nanoTime() - start) / 1_000_000
        if (elapsed >= timeoutMillis) throw LedgerFailure(LedgerProblem.TIMED_OUT)
        return timeoutMillis - elapsed.toInt()
    }

    private companion object {
        const val APDU_TAG = 0x05
        const val READ_SLICE_MILLIS = 100
    }
}

private const val REPORT_SIZE = 64
internal const val HIDAPI_BUFFER_SIZE = REPORT_SIZE + 1

internal const val STATUS_BYTES = 2

internal fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff

internal fun ByteArray.uint16(offset: Int): Int = u8(offset) shl 8 or u8(offset + 1)

private fun ByteArray.put16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}
