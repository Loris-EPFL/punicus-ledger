package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Address
import io.myotis.api.CallResult
import io.myotis.api.TraceResult
import io.myotis.api.TransactionArgs
import java.math.BigInteger

/**
 * A Myotis call result as the adapters read it. `callDetailed` tells a revert (a verified
 * answer) from no answer and names the block the call ran against when it can (-1 when it
 * does not), with its hash when the engine proved it. OK and REVERTED are answers. REFUSED is a permanent refusal, and so is
 * INFEASIBLE for a plain call, whose calldata alone outran the engine's budget. UNAVAILABLE
 * is no verified answer right now.
 */
internal fun callOutcome(result: CallResult): CallOutcome? {
    val block = result.blockNumber().takeIf { it >= 0 }
    val hash = result.blockHashHex()?.lowercase()
    return when (result.status()) {
        CallResult.Status.OK -> CallOutcome.Returned((result.data() ?: ByteArray(0)).toHex(), block, hash)
        CallResult.Status.REVERTED -> CallOutcome.Reverted(block, hash)
        CallResult.Status.REFUSED, CallResult.Status.INFEASIBLE -> CallOutcome.Refused
        CallResult.Status.UNAVAILABLE, null -> null
    }
}

/**
 * The transaction a trace runs (docs/clear-signing.md, *Phase B*): its sender, recipient, value and data, and
 * nothing else (no gas limit, no fee), in both of `TransactionArgs`' views.
 */
internal fun transactionArgs(from: Address, to: Address, valueWei: String, data: ByteArray): TransactionArgs {
    val value = "0x" + BigInteger(valueWei).toString(16)
    val json = """{"from":"${from.hex}","to":"${to.hex}","input":"0x${data.toHex()}","value":"$value"}"""
    return TransactionArgs(from.bytes(), to.bytes(), data, valueWei, null, null, null, null, null, false, false, json)
}

/** Block [number] as a JSON-RPC block tag. */
internal fun blockTag(number: Long): String = "0x" + number.toString(16)

/** geth's `callTracer`, with the logs each frame emits. */
internal const val CALL_TRACER = """{"tracer":"callTracer","tracerConfig":{"withLog":true}}"""

/** A Myotis trace result as the adapter reads it. */
internal fun traceAnswer(result: TraceResult): TraceAnswer = when (result.status()) {
    TraceResult.Status.OK -> result.traceJson()?.let(TraceAnswer::Traced) ?: TraceAnswer.Unavailable("no trace")
    TraceResult.Status.INFEASIBLE -> TraceAnswer.Infeasible(result.detail() ?: "")
    TraceResult.Status.REFUSED -> TraceAnswer.Refused(result.detail() ?: "")
    TraceResult.Status.UNAVAILABLE, null -> TraceAnswer.Unavailable(result.detail())
}
