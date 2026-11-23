package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Chequebook
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PostageBatch
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BatchFinding
import eth.serotine.domain.repository.ChequebookFinding
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SwarmCandidate
import eth.serotine.domain.repository.SwarmCandidateChange
import eth.serotine.domain.repository.SwarmCandidateList
import eth.serotine.domain.repository.SwarmCandidates
import eth.serotine.domain.repository.SwarmContracts
import eth.serotine.domain.repository.SwarmReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class SwarmNodesTest {

    private val nodeAddress = Address.of("0x" + "0b".repeat(20))
    private val node = watched(Network.GNOSIS, nodeAddress)
    private val other = Address.of("0x" + "0c".repeat(20))
    private val chequebookAddress = Address.of("0x" + "cb".repeat(20))
    private val batchId = BatchId.of("ab".repeat(32))

    private val chequebook =
        Chequebook(chequebookAddress, nodeAddress, UInt256.of(10), UInt256.of(10), UInt256.of(0), bounced = false)
    private val batch = PostageBatch(batchId, nodeAddress, 22, 16, false, UInt256.of(100), UInt256.of(10))

    /** When set, what every chequebook reads as, reading after reading. */
    private var live: MutableStateFlow<SwarmReading<ChequebookFinding>>? = null

    /** What each chequebook and batch reads as; the node's own by default. */
    private val chequebookReadings = mutableMapOf<Address, SwarmReading<ChequebookFinding>>()
    private val batchReadings = mutableMapOf<BatchId, SwarmReading<BatchFinding>>()
    private val nodesAsked = mutableListOf<Address>()

    private val contracts = object : SwarmContracts {
        override fun observeChequebook(
            network: Network,
            node: Address,
            chequebook: Address,
        ): Flow<SwarmReading<ChequebookFinding>> =
            live ?: flowOf(
                chequebookReadings[chequebook]
                    ?: SwarmReading.Verified(ChequebookFinding.Node(this@SwarmNodesTest.chequebook), 7),
            ).also { nodesAsked += node }

        override fun observeBatch(network: Network, node: Address, id: BatchId) =
            flowOf(batchReadings[id] ?: SwarmReading.Verified(BatchFinding.Node(batch), 7)).also { nodesAsked += node }

        override fun deployment(network: Network) = null
    }

    private val stored = MutableStateFlow<SwarmCandidateList>(SwarmCandidateList.Loaded(emptyList(), emptyList()))
    private val added = mutableListOf<SwarmCandidate>()
    private var addAnswer = SwarmCandidateChange.CHANGED

    private val candidates = object : SwarmCandidates {
        override fun observe(node: AccountId): Flow<SwarmCandidateList> = stored.map { it }

        override fun observeNodes(network: Network) = flowOf(emptyMap<AccountId, SwarmCandidateList.Loaded>())

        override suspend fun add(node: AccountId, candidate: SwarmCandidate, byHand: Boolean): SwarmCandidateChange {
            added += candidate
            return addAnswer
        }

        override suspend fun remove(node: AccountId, candidate: SwarmCandidate) = SwarmCandidateChange.CHANGED

        override suspend fun forget(node: AccountId) = SwarmCandidateChange.NOT_FOUND
    }

    private val observe = ObserveSwarmNode(candidates, contracts)
    private val add = AddSwarmCandidate(candidates, contracts)

    /** Whether every candidate of [reading] has its first reading. */
    private fun allRead(reading: SwarmNodeReading): Boolean =
        reading is SwarmNodeReading.Read && (reading.chequebooks + reading.batches).all { it.reading != null }

    private fun watched(network: Network, address: Address, kind: AccountKind = AccountKind.EOA) =
        Account(network, address, kind, "Bee", AccountCustody.Watched)

    @Test
    fun offGnosisChainOrForASafeNothingIsRead() = runTest {
        assertEquals(SwarmNodeReading.NotASwarmAccount, observe(watched(Network.ETHEREUM, nodeAddress)).first())
        // A batch may name a Safe as its owner, but a Swarm node is an EOA (docs/swarm.md, *Recognition*).
        val safe = watched(Network.GNOSIS, nodeAddress, AccountKind.SAFE)
        assertEquals(SwarmNodeReading.NotASwarmAccount, observe(safe).first())
        assertEquals(SwarmAddOutcome.NotASwarmAccount, add(safe, SwarmCandidate.Batch(batchId)))
        assertTrue(nodesAsked.isEmpty())
        assertTrue(added.isEmpty())
    }

    @Test
    fun noCandidatesIsNoSwarmNodeYet() = runTest {
        val reading = observe(node).first() as SwarmNodeReading.Read
        assertTrue(reading.chequebooks.isEmpty() && reading.batches.isEmpty())
        assertFalse(reading.recognised)
    }

    @Test
    fun aProvenChequebookOrBatchMakesTheAccountASwarmNode() = runTest {
        stored.value = SwarmCandidateList.Loaded(listOf(chequebookAddress), listOf(batchId))
        val reading = observe(node).first(::allRead) as SwarmNodeReading.Read
        assertTrue(reading.recognised)
        assertEquals(listOf(nodeAddress, nodeAddress), nodesAsked, "each is read for the node itself")
        assertEquals(chequebookAddress, reading.chequebooks.single().key)
        assertEquals(batchId, reading.batches.single().key)
    }

    @Test
    fun eachCandidateIsToldAtOnceAsNotReadYet() = runTest {
        stored.value = SwarmCandidateList.Loaded(listOf(chequebookAddress), listOf(batchId))
        val first = observe(node).first() as SwarmNodeReading.Read
        assertEquals(listOf(SwarmItem<Address, ChequebookFinding>(chequebookAddress, null)), first.chequebooks)
        assertEquals(listOf(SwarmItem<BatchId, BatchFinding>(batchId, null)), first.batches)
        assertFalse(first.recognised, "what is not read yet never counts")
    }

    @Test
    fun whatIsNotVerifiedOrAnothersNeverRecognisesIt() = runTest {
        stored.value = SwarmCandidateList.Loaded(listOf(chequebookAddress), listOf(batchId))
        chequebookReadings[chequebookAddress] = SwarmReading.Verified(ChequebookFinding.OthersIssuer(other), 7)
        batchReadings[batchId] = SwarmReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertFalse((observe(node).first(::allRead) as SwarmNodeReading.Read).recognised)
    }

    @Test
    fun aVerifiedReadingStandsAsHeldOverAReadThatDidNotAnswer() = runTest {
        val readings = MutableStateFlow<SwarmReading<ChequebookFinding>>(
            SwarmReading.Verified(ChequebookFinding.Node(chequebook), 7),
        )
        live = readings
        stored.value = SwarmCandidateList.Loaded(listOf(chequebookAddress), emptyList())
        val seen = mutableListOf<SwarmItem<Address, ChequebookFinding>>()
        backgroundScope.launch {
            observe(node).collect { (it as? SwarmNodeReading.Read)?.chequebooks?.singleOrNull()?.let(seen::add) }
        }
        runCurrent()
        readings.value = SwarmReading.Unavailable(ReadUnavailable.UNPROVEN)
        runCurrent()
        val verified: SwarmReading<ChequebookFinding> = SwarmReading.Verified(ChequebookFinding.Node(chequebook), 7)
        assertEquals(SwarmItem(chequebookAddress, verified, held = true), seen.last())
        // A reason that stands replaces it at once.
        readings.value = SwarmReading.Unavailable(ReadUnavailable.REFUSED)
        runCurrent()
        val refused: SwarmReading<ChequebookFinding> = SwarmReading.Unavailable(ReadUnavailable.REFUSED)
        assertEquals(SwarmItem(chequebookAddress, refused), seen.last())
    }

    @Test
    fun aHeldReadingStandsThroughAChangeOfTheList() = runTest {
        val readings = MutableStateFlow<SwarmReading<ChequebookFinding>>(
            SwarmReading.Verified(ChequebookFinding.Node(chequebook), 7),
        )
        live = readings
        stored.value = SwarmCandidateList.Loaded(listOf(chequebookAddress), emptyList())
        var last: SwarmNodeReading.Read? = null
        backgroundScope.launch { observe(node).collect { last = it as? SwarmNodeReading.Read } }
        runCurrent()
        readings.value = SwarmReading.Unavailable(ReadUnavailable.UNPROVEN)
        runCurrent()
        // A batch is added while the chequebook's reads do not answer: the chequebook stays held.
        stored.value = SwarmCandidateList.Loaded(listOf(chequebookAddress), listOf(batchId))
        runCurrent()
        val verified: SwarmReading<ChequebookFinding> = SwarmReading.Verified(ChequebookFinding.Node(chequebook), 7)
        assertEquals(SwarmItem(chequebookAddress, verified, held = true), last?.chequebooks?.single())
        assertEquals(batchId, last?.batches?.single()?.key)
    }

    @Test
    fun unreadableCandidatesSaySo() = runTest {
        stored.value = SwarmCandidateList.Unreadable
        assertEquals(SwarmNodeReading.Unreadable, observe(node).first())
    }

    @Test
    fun aCandidateIsKeptOnlyOnceProvenTheNodes() = runTest {
        assertEquals(SwarmAddOutcome.Added, add(node, SwarmCandidate.ChequebookAt(chequebookAddress)))
        assertEquals(listOf<SwarmCandidate>(SwarmCandidate.ChequebookAt(chequebookAddress)), added)

        chequebookReadings[other] = SwarmReading.Verified(ChequebookFinding.NotAChequebook, 7)
        assertEquals(SwarmAddOutcome.NotAChequebook, add(node, SwarmCandidate.ChequebookAt(other)))

        val elsewhere = BatchId.of("cd".repeat(32))
        batchReadings[elsewhere] = SwarmReading.Verified(BatchFinding.OthersOwner(other), 7)
        assertEquals(SwarmAddOutcome.OthersBatch(other), add(node, SwarmCandidate.Batch(elsewhere)))

        val gone = BatchId.of("ef".repeat(32))
        batchReadings[gone] = SwarmReading.Verified(BatchFinding.Gone, 7)
        assertEquals(SwarmAddOutcome.NoSuchBatch, add(node, SwarmCandidate.Batch(gone)))

        assertEquals(1, added.size, "nothing refused is kept")
    }

    @Test
    fun nothingIsKeptOnAnUnverifiedReading() = runTest {
        batchReadings[batchId] = SwarmReading.Syncing
        assertEquals(SwarmAddOutcome.NotVerified(ReadUnavailable.SYNCING), add(node, SwarmCandidate.Batch(batchId)))
        batchReadings[batchId] = SwarmReading.Unavailable(ReadUnavailable.REFUSED)
        assertEquals(SwarmAddOutcome.NotVerified(ReadUnavailable.REFUSED), add(node, SwarmCandidate.Batch(batchId)))
        assertEquals(
            SwarmAddOutcome.NotASwarmAccount,
            add(watched(Network.SEPOLIA, nodeAddress), SwarmCandidate.Batch(batchId)),
        )
        assertTrue(added.isEmpty())
    }

    @Test
    fun aCandidateKeptAlreadyOrNotSavedSaysSo() = runTest {
        addAnswer = SwarmCandidateChange.ALREADY_PRESENT
        assertEquals(SwarmAddOutcome.AlreadyAdded, add(node, SwarmCandidate.Batch(batchId)))
        addAnswer = SwarmCandidateChange.NOT_SAVED
        assertEquals(SwarmAddOutcome.NotSaved, add(node, SwarmCandidate.Batch(batchId)))
    }
}
