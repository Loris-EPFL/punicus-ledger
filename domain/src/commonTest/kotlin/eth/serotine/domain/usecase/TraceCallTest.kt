package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSigningDeployment
import eth.serotine.domain.model.ClearSigningToken
import eth.serotine.domain.model.CodePin
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.ReachedContract
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TraceFrame
import eth.serotine.domain.model.TraceReaderTest.Companion.address
import eth.serotine.domain.model.TraceReaderTest.Companion.transfer
import eth.serotine.domain.model.TracedAsset
import eth.serotine.domain.model.TracedMove
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CallTraceReading
import eth.serotine.domain.repository.CallTraces
import eth.serotine.domain.repository.ClearSigningCatalog
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [TraceCall]: the verified trace's effects, named, and its wait (docs/clear-signing.md, *Phase B*). */
class TraceCallTest {

    private val user = address("aa")
    private val router = address("01")
    private val usdc = address("03")
    private val unknown = address("04")
    private val implementation = address("05")

    private val tokens = object : TokenCatalog {
        override fun observeTokens(network: Network) = flowOf(listOf(Token(usdc, "USDC", "USD Coin", 6, null)))
    }
    private val catalog = object : ClearSigningCatalog {
        override fun deployment(network: Network, address: Address) =
            if (address == router) ClearSigningDeployment(network, "Router", router, "0x00", CodePin.Fixed, emptyList()) else null
    }

    private val root = TraceFrame(
        TraceFrame.Type.CALL, user, router, UInt256.ZERO,
        logs = listOf(transfer(usdc, user, router, 10), transfer(unknown, router, user, 3)),
        calls = listOf(TraceFrame(TraceFrame.Type.DELEGATECALL, router, implementation, null)),
    )

    private class FakeTraces(var answer: suspend () -> CallTraceReading) : CallTraces {
        var runs = 0
        override suspend fun trace(network: Network, from: Address, to: Address, value: UInt256, data: ByteArray): CallTraceReading {
            runs++
            return answer()
        }
    }


    @Test
    fun aTraceIsReadAndNamedByTheBuiltInListAndTheCatalog() = runTest {
        val traces = FakeTraces { CallTraceReading.Traced(root, 21_000_000) }
        val use = TraceCall(traces, tokens, catalog, { null }, backgroundScope)
        val outcome = assertIs<TraceOutcome.Traced>(use.trace(Network.ETHEREUM, user, router, UInt256.ZERO, byteArrayOf(1), null))
        assertEquals(21_000_000, outcome.blockNumber)
        assertEquals(
            listOf(
                TracedMove(TracedAsset.Fungible(usdc), UInt256.of(10), user, router),
                TracedMove(TracedAsset.Fungible(unknown), UInt256.of(3), router, user),
            ),
            outcome.effects.moves,
        )
        assertEquals(mapOf(usdc to "USD Coin", router to "Router"), outcome.names)
        assertEquals(mapOf(usdc to ClearSigningToken("USDC", 6)), outcome.tokens)
        assertTrue(outcome.unnamedDelegate(ReachedContract(implementation, delegatedInto = true)))
        assertEquals(emptyList(), outcome.disagreements)
    }

    @Test
    fun whatTheEngineCannotTraceIsSaidSo() = runTest {
        val traces = FakeTraces { CallTraceReading.NotTraced }
        val use = TraceCall(traces, tokens, catalog, { null }, backgroundScope)
        assertEquals(TraceOutcome.NotTraced, use.trace(Network.ETHEREUM, user, router, UInt256.ZERO, byteArrayOf(), null))
        traces.answer = { CallTraceReading.Infeasible("insufficient funds for gas") }
        assertEquals(TraceOutcome.Infeasible("insufficient funds for gas"), use.trace(Network.ETHEREUM, user, router, UInt256.ZERO, byteArrayOf(), null))
        traces.answer = { CallTraceReading.Unavailable(ReadUnavailable.SYNCING) }
        assertEquals(TraceOutcome.Unavailable(ReadUnavailable.SYNCING), use.trace(Network.ETHEREUM, user, router, UInt256.ZERO, byteArrayOf(), null))
    }

    @Test
    fun pastTheWaitItIsNotInTimeAndAskingAgainWaitsForTheSameRun() = runTest {
        val done = CompletableDeferred<CallTraceReading>()
        val traces = FakeTraces { done.await() }
        val use = TraceCall(traces, tokens, catalog, { null }, backgroundScope, waitMs = 1_000)
        assertEquals(TraceOutcome.NotInTime, use.trace(Network.ETHEREUM, user, router, UInt256.ZERO, byteArrayOf(1), null))
        val again = async { use.trace(Network.ETHEREUM, user, router, UInt256.ZERO, byteArrayOf(1), null) }
        done.complete(CallTraceReading.Traced(root, 1))
        assertIs<TraceOutcome.Traced>(again.await())
        assertEquals(1, traces.runs)
        // Once it has ended, the next ask runs a new trace, at the head then.
        use.trace(Network.ETHEREUM, user, router, UInt256.ZERO, byteArrayOf(1), null)
        assertEquals(2, traces.runs)
    }
}
