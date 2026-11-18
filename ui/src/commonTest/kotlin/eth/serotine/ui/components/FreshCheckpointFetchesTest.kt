package eth.serotine.ui.components

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.FreshCheckpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FreshCheckpointFetches] on its own, with a fake for the servers: a fetch asks them, one at a time
 * per network, and every way it ends is said.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FreshCheckpointFetchesTest {

    private val asked = mutableListOf<Pair<Network, Boolean>>()
    private val applied = mutableListOf<String>()

    @Test
    fun aFetchAsksTheServersAndSaysTheirAnswer() = runTest {
        var answer: FreshCheckpoint = FreshCheckpoint.Refused("one server named another epoch")
        val fetches = FreshCheckpointFetches(
            backgroundScope,
            askServers = { network, useUnconfirmed -> asked += network to useUnconfirmed; answer },
            onApplied = { _, said -> applied += said },
        )

        fetches.fetch(Network.SEPOLIA, useUnconfirmed = false)
        runCurrent()
        assertEquals(listOf(Network.SEPOLIA to false), asked, "the servers are asked")
        assertEquals(
            CheckpointFetchState.Refused("one server named another epoch", unconfirmed = false),
            fetches.states.value[Network.SEPOLIA],
        )

        answer = FreshCheckpoint.Applied(epoch = 42, operators = listOf("a.io", "b.org"), confirmedBy = emptyList())
        fetches.fetch(Network.SEPOLIA, useUnconfirmed = true)
        runCurrent()
        assertEquals(Network.SEPOLIA to true, asked.last(), "a refusal may be asked again")
        assertEquals(CheckpointFetchState.Applied, fetches.states.value[Network.SEPOLIA])
        assertEquals(1, applied.size)

        fetches.fetch(Network.SEPOLIA, useUnconfirmed = false)
        runCurrent()
        assertEquals(2, asked.size, "nothing more once one is applied")

        fetches.settled(Network.SEPOLIA)
        assertNull(fetches.states.value[Network.SEPOLIA], "forgotten once the light client resumes")
    }

    @Test
    fun aSecondTapWhileOneRunsAsksNothing() = runTest {
        val answer = CompletableDeferred<FreshCheckpoint>()
        val fetches = FreshCheckpointFetches(
            backgroundScope,
            askServers = { network, useUnconfirmed -> asked += network to useUnconfirmed; answer.await() },
            onApplied = { _, _ -> },
        )

        fetches.fetch(Network.SEPOLIA, useUnconfirmed = false)
        runCurrent()
        fetches.fetch(Network.SEPOLIA, useUnconfirmed = false)
        fetches.settled(Network.SEPOLIA)
        runCurrent()
        assertEquals(1, asked.size)
        assertEquals(CheckpointFetchState.Fetching, fetches.states.value[Network.SEPOLIA], "a running fetch stays")

        answer.complete(FreshCheckpoint.NotRefused)
        runCurrent()
        assertNull(fetches.states.value[Network.SEPOLIA], "not refused: nothing to say")
    }

    @Test
    fun aFetchThatFailsIsARefusalAndOneThatEndsWithoutAnOutcomeIsNotLeftFetching() = runTest {
        var failure: Throwable = IllegalStateException("no route to host")
        val fetches = FreshCheckpointFetches(
            backgroundScope,
            askServers = { _, _ -> throw failure },
            onApplied = { _, _ -> },
        )

        fetches.fetch(Network.SEPOLIA, useUnconfirmed = false)
        runCurrent()
        assertEquals(
            CheckpointFetchState.Refused("the checkpoint servers could not be asked (no route to host)", false),
            fetches.states.value[Network.SEPOLIA],
        )

        failure = IllegalStateException()
        fetches.fetch(Network.SEPOLIA, useUnconfirmed = false)
        runCurrent()
        assertEquals(
            CheckpointFetchState.Refused("the checkpoint servers could not be asked", false),
            fetches.states.value[Network.SEPOLIA],
            "no class name on screen",
        )

        // An Error is not caught as a refusal; the fetch still does not stay "Fetching".
        failure = AssertionError("the native library is gone")
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            backgroundScope.coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, e -> errors += e },
        )
        val failing = FreshCheckpointFetches(scope, askServers = { _, _ -> throw failure }, onApplied = { _, _ -> })
        failing.fetch(Network.SEPOLIA, useUnconfirmed = false)
        runCurrent()
        assertTrue(errors.isNotEmpty(), "the Error still surfaces")
        assertNull(failing.states.value[Network.SEPOLIA], "and the network may be asked again")
    }
}
