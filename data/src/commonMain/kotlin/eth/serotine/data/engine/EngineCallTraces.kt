package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CallTraceReading
import eth.serotine.domain.repository.CallTraces
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.CoroutineDispatcher

/**
 * [CallTraces] over the engine's verified `debug_traceCall` (docs/clear-signing.md, *Phase B*), on [network],
 * the one network its engine follows. The trace is asked for at the verified head the engine's snapshot names
 * ([EngineSnapshot.Running.readableHead]), by its number, so the block it was run against is known; the engine
 * applies that block or refuses it. Asked only while reads can be verified, on [dispatcher]. An engine that
 * does not trace calls (iOS for now) answers [CallTraceReading.NotTraced]; a trace this cannot read exactly
 * ([CallTraceJson]) is no answer ([ReadUnavailable.UNPROVEN]). Any other network is refused
 * ([ReadUnavailable.NO_ENGINE]), and so is a device without an engine ([chain] null).
 */
class EngineCallTraces internal constructor(
    private val network: Network,
    private val chain: TransactingChain?,
    private val dispatcher: CoroutineDispatcher,
) : CallTraces {

    override suspend fun trace(network: Network, from: Address, to: Address, value: UInt256, data: ByteArray): CallTraceReading {
        if (network != this.network) return CallTraceReading.Unavailable(ReadUnavailable.NO_ENGINE)
        return chain.askVerified(dispatcher, CallTraceReading::Unavailable) { verified ->
            val head = (verified.snapshot() as? EngineSnapshot.Running)?.readableHead
                ?: return@askVerified CallTraceReading.Unavailable(ReadUnavailable.UNPROVEN)
            when (val answer = verified.traceCall(from, to, value.toDecimal(), data, head)) {
                // The root frame echoes what was asked: one that runs anything else is no answer.
                is TraceAnswer.Traced -> CallTraceJson.parse(answer.json)
                    ?.takeIf { it.from == from && it.to == to && (it.value ?: UInt256.ZERO) == value }
                    ?.let { CallTraceReading.Traced(it, head) }
                    ?: CallTraceReading.Unavailable(ReadUnavailable.UNPROVEN)
                is TraceAnswer.Infeasible -> CallTraceReading.Infeasible(answer.detail)
                // A request the engine refuses (a block it will not trace at, for one, the head having moved) is no
                // answer this time, as a refused call at a named block is (OneBlockCalls); "never" is NoTracer's.
                is TraceAnswer.Refused -> CallTraceReading.Unavailable(ReadUnavailable.UNPROVEN)
                TraceAnswer.NoTracer -> CallTraceReading.NotTraced
                is TraceAnswer.Unavailable -> CallTraceReading.Unavailable(ReadUnavailable.UNPROVEN)
            }
        }
    }
}
