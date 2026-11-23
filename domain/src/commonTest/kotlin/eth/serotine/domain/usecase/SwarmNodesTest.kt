package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
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
import kotlinx.coroutines.test.runTest

class SwarmNodesTest {

    private val nodeAddress = Address.of("0x" + "0b".repeat(20))
    private val node = AccountId(Network.GNOSIS, nodeAddress)
    private val other = Address.of("0x" + "0c".repeat(20))
    private val chequebookAddress = Address.of("0x" + "cb".repeat(20))
    private val batchId = BatchId.of("ab".repeat(32))

    private val chequebook = Chequebook(chequebookAddress, nodeAddress, UInt256.of(10), UInt256.of(10), UInt256.of(0), false)
    private val batch = PostageBatch(batchId, nodeAddress, 22, 16, false, UInt256.of(100), UInt256.of(10))

    /** What each chequebook and batch reads as; the node's own by default. */
    private val chequebookReadings = mutableMapOf<Address, SwarmReading<ChequebookFinding>>()
    private val batchReadings = mutableMapOf<BatchId, SwarmReading<BatchFinding>>()
    private val nodesAsked = mutableListOf<Address>()

    private val contracts = object : SwarmContracts {
        override fun observeChequebook(network: Network, node: Address, chequebook: Address) =
            flowOf(chequebookReadings[chequebook] ?: SwarmReading.Verified(ChequebookFinding.Node(this@SwarmNodesTest.chequebook), 7))
                .also { nodesAsked += node }

        override fun observeBatch(network: Network, node: Address, id: BatchId) =
            flowOf(batchReadings[id] ?: SwarmReading.Verified(BatchFinding.Node(batch), 7)).also { nodesAsked += node }
    }

    private val stored = MutableStateFlow<SwarmCandidateList>(SwarmCandidateList.Loaded(emptyList(), emptyList()))
    private val added = mutableListOf<SwarmCandidate>()
    private var addAnswer = SwarmCandidateChange.CHANGED

    private val candidates = object : SwarmCandidates {
        override fun observe(node: AccountId): Flow<SwarmCandidateList> = stored.map { it }

        override suspend fun add(node: AccountId, candidate: SwarmCandidate): SwarmCandidateChange {
            added += candidate
            return addAnswer
        }

        override suspend fun remove(node: AccountId, candidate: SwarmCandidate) = SwarmCandidateChange.CHANGED
    }

    private val observe = ObserveSwarmNode(candidates, contracts)
    private val add = AddSwarmCandidate(candidates, contracts)

    @Test
    fun offGnosisChainNothingIsRead() = runTest {
        assertEquals(SwarmNodeReading.NotOnThisNetwork, observe(AccountId(Network.ETHEREUM, nodeAddress)).first())
        assertEquals(SwarmCandidateList.Loaded(emptyList(), emptyList()), stored.value)
        assertTrue(nodesAsked.isEmpty())
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
        val reading = observe(node).first() as SwarmNodeReading.Read
        assertTrue(reading.recognised)
        assertEquals(listOf(nodeAddress, nodeAddress), nodesAsked, "each is read for the node itself")
        assertEquals(chequebookAddress, reading.chequebooks.single().key)
        assertEquals(batchId, reading.batches.single().key)
    }

    @Test
    fun whatIsNotVerifiedOrAnothersNeverRecognisesIt() = runTest {
        stored.value = SwarmCandidateList.Loaded(listOf(chequebookAddress), listOf(batchId))
        chequebookReadings[chequebookAddress] = SwarmReading.Verified(ChequebookFinding.OthersIssuer(other), 7)
        batchReadings[batchId] = SwarmReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertFalse((observe(node).first() as SwarmNodeReading.Read).recognised)
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
        assertEquals(SwarmAddOutcome.NotOnThisNetwork, add(AccountId(Network.SEPOLIA, nodeAddress), SwarmCandidate.Batch(batchId)))
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
