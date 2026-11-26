package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSignResult
import eth.serotine.domain.model.ClearSigningToken
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.ReachedContract
import eth.serotine.domain.model.TraceDisagreement
import eth.serotine.domain.model.TraceDisagreements
import eth.serotine.domain.model.TraceEffects
import eth.serotine.domain.model.TraceReader
import eth.serotine.domain.model.TypedDataMeaning
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.CallTraceReading
import eth.serotine.domain.repository.CallTraces
import eth.serotine.domain.repository.ClearSigningCatalog
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a dApp's or a Safe's transaction does when it runs at the verified head (docs/clear-signing.md,
 * *Phase B*): the engine's verified trace ([CallTraces]), its effects on the sender ([TraceReader]), and where
 * they disagree with the call's description ([TraceDisagreements]), which is warned about and leaves the call
 * signable. A Safe's transaction is traced as the Safe's own call: [trace]'s `from` is the Safe.
 *
 * The wallet waits [waitMs] for a trace, and past it says it was not traced in time ([TraceOutcome.NotInTime]).
 * The engine's run goes on to its own budget in [scope], and a trace of the same call asked for while it runs
 * waits for that run rather than starting a second one. Tokens and contracts are named by the built-in list
 * and the clear-signing catalog alone; [wrappedNative] is each network's pinned wrapped currency.
 */
class TraceCall(
    private val traces: CallTraces,
    private val tokens: TokenCatalog,
    private val catalog: ClearSigningCatalog,
    private val wrappedNative: (Network) -> Address?,
    private val scope: CoroutineScope,
    private val waitMs: Long = WAIT_MS,
) {
    private val running = mutableMapOf<Key, Deferred<CallTraceReading>>()
    private val lock = Mutex()

    /** Traces [data] sent by [from] to [to] with [value] on [network], held against its [description]. */
    suspend fun trace(
        network: Network,
        from: Address,
        to: Address,
        value: UInt256,
        data: ByteArray,
        description: ClearSignResult,
    ): TraceOutcome {
        val key = Key(network, from, to, value, data.toHex())
        val run = lock.withLock {
            running.values.removeAll { !it.isActive }
            running[key]
                ?: scope.async { traces.trace(network, from, to, value, data) }.also { running[key] = it }
        }
        val reading = withTimeoutOrNull(waitMs) { run.await() } ?: return TraceOutcome.NotInTime
        lock.withLock { if (running[key] === run) running.remove(key) }
        return when (reading) {
            is CallTraceReading.Traced -> traced(network, from, reading, description)
            is CallTraceReading.Infeasible -> TraceOutcome.Infeasible(reading.detail)
            CallTraceReading.NotTraced -> TraceOutcome.NotTraced
            is CallTraceReading.Unavailable -> TraceOutcome.Unavailable(reading.reason)
        }
    }

    private suspend fun traced(
        network: Network,
        from: Address,
        reading: CallTraceReading.Traced,
        description: ClearSignResult,
    ): TraceOutcome.Traced {
        val read = TraceReader.read(from, reading.root, wrappedNative(network), TypedDataMeaning.PERMIT2)
        val builtIn = tokens.observeTokens(network).first()
        val effects = read.effects
        val mentioned = buildSet {
            effects.moves.forEach { add(it.from); add(it.to); it.asset.contract?.let(::add) }
            effects.rights.forEach { add(it.contract); add(it.spender) }
            effects.contracts.forEach { add(it.address) }
        }
        val listed = builtIn.filter { it.address in mentioned }
        val names = mentioned.mapNotNull { address ->
            (listed.firstOrNull { it.address == address }?.name ?: catalog.deployment(network, address)?.name)?.let { address to it }
        }.toMap()
        return TraceOutcome.Traced(
            effects = effects,
            disagreements = TraceDisagreements.check(from, read, description),
            blockNumber = reading.blockNumber,
            names = names,
            tokens = listed.associate { it.address to ClearSigningToken(it.symbol, it.decimals) },
        )
    }

    private data class Key(val network: Network, val from: Address, val to: Address, val value: UInt256, val data: String)

    private companion object {
        /** How long the wallet waits for a trace (docs/clear-signing.md, *Limits*). */
        const val WAIT_MS = 30_000L
    }
}

/** What [TraceCall.trace] gives. */
sealed interface TraceOutcome {
    /**
     * Traced at [blockNumber]: its [effects] and its [disagreements] with the description. [names] is the
     * wallet's name for each address it mentions that has one (a built-in token, a pinned deployment), and
     * [tokens] each built-in token's symbol and decimals; any other is shown by its address, its amount raw.
     */
    data class Traced(
        val effects: TraceEffects,
        val disagreements: List<TraceDisagreement>,
        val blockNumber: Long,
        val names: Map<Address, String>,
        val tokens: Map<Address, ClearSigningToken>,
    ) : TraceOutcome {
        /** Whether [contract] was delegated into while the wallet cannot name it: marked, not warned about. */
        fun unnamedDelegate(contract: ReachedContract): Boolean = contract.delegatedInto && contract.address !in names
    }

    /** It cannot run within what the sender can pay ([detail], the EVM's words). */
    data class Infeasible(val detail: String) : TraceOutcome

    /** This build's engine does not trace calls. */
    data object NotTraced : TraceOutcome

    /** Not traced within the wallet's wait; the engine's run goes on, and asking again waits for it. */
    data object NotInTime : TraceOutcome

    /** No verified answer right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : TraceOutcome
}
