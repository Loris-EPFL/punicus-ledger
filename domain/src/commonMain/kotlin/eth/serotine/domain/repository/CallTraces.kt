package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TraceFrame
import eth.serotine.domain.model.UInt256

/**
 * The port for a verified trace (docs/clear-signing.md, *Phase B*): a transaction run by the embedded light
 * client's engine at the verified head against state it proves slot by slot (`debug_traceCall` with the
 * `callTracer` and its logs), never by any other source (CLAUDE.md, *Trust*). :data implements it over the
 * Myotis engine. A [Network] no adapter serves is refused ([ReadUnavailable.NO_ENGINE]). The engine's own run
 * is bounded by its budget; the port never throws.
 */
interface CallTraces {
    /** [data] sent by [from] to [to] with [value] on [network], run and traced at the verified head. */
    suspend fun trace(network: Network, from: Address, to: Address, value: UInt256, data: ByteArray): CallTraceReading
}

/** What [CallTraces.trace] gives. */
sealed interface CallTraceReading {
    /** Traced: [root] the transaction's frame, run against the state of [blockNumber]. */
    data class Traced(val root: TraceFrame, val blockNumber: Long) : CallTraceReading

    /** It cannot run within what the sender can pay ([detail], the EVM's words): a verified answer. */
    data class Infeasible(val detail: String) : CallTraceReading

    /** This build's engine does not trace calls (iOS for now: docs/clear-signing.md, *Limits*). */
    data object NotTraced : CallTraceReading

    /** No verified answer right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : CallTraceReading
}
