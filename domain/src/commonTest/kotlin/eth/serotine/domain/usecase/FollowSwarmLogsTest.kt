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
import eth.serotine.domain.model.SwarmDeployment
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BatchFinding
import eth.serotine.domain.repository.ChequebookFinding
import eth.serotine.domain.repository.IndexedLog
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.LogCoverage
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import eth.serotine.domain.repository.ReceiptLog
import eth.serotine.domain.repository.SwarmCandidate
import eth.serotine.domain.repository.SwarmCandidateChange
import eth.serotine.domain.repository.SwarmCandidateList
import eth.serotine.domain.repository.SwarmCandidates
import eth.serotine.domain.repository.SwarmContracts
import eth.serotine.domain.repository.SwarmReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** [FollowSwarmLogs] over fake ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowSwarmLogsTest {

    private val bzz = Address.of("0x" + "b2".repeat(20))
    private val stamp = Address.of("0x" + "57".repeat(20))
    private val bee = eoa("0b", "Bee", AccountCustody.Watched)
    private val alice = eoa("a1", "Alice", AccountCustody.InKeyStore)
    private val chequebook = Address.of("0x" + "cb".repeat(20))
    private val stranger = Address.of("0x" + "5e".repeat(20))
    private val batch = BatchId.of("ab".repeat(32))

    private val accounts = ListAccountRepository(bee)
    private val lightClient = FakeLightClient()
    private val index = RecordingLogIndex()
    private val kept = MutableStateFlow<Map<AccountId, SwarmCandidateList.Loaded>>(emptyMap())
    private val proven = mutableListOf<Pair<Address, Any>>()

    private val candidates = object : SwarmCandidates {
        override fun observe(node: AccountId): Flow<SwarmCandidateList> =
            kept.map { it[node] ?: SwarmCandidateList.Loaded(emptyList(), emptyList()) }

        override fun observeNodes(network: Network): Flow<Map<AccountId, SwarmCandidateList.Loaded>?> = kept

        override suspend fun add(node: AccountId, candidate: SwarmCandidate): SwarmCandidateChange {
            val list = kept.value[node] ?: SwarmCandidateList.Loaded(emptyList(), emptyList())
            kept.value = kept.value + (
                node to when (candidate) {
                    is SwarmCandidate.ChequebookAt -> list.copy(chequebooks = list.chequebooks + candidate.address)
                    is SwarmCandidate.Batch -> list.copy(batches = list.batches + candidate.id)
                }
                )
            return SwarmCandidateChange.CHANGED
        }

        override suspend fun remove(node: AccountId, candidate: SwarmCandidate) = SwarmCandidateChange.NOT_FOUND
    }

    /** Only [chequebook] is a chequebook, the node's; only [batch] is a batch, owned by [bee]. */
    private val contracts = object : SwarmContracts {
        override fun observeChequebook(network: Network, node: Address, chequebook: Address) = flowOf(
            SwarmReading.Verified(
                if (chequebook == this@FollowSwarmLogsTest.chequebook && node == bee.address) {
                    val one = UInt256.of(1)
                    ChequebookFinding.Node(Chequebook(chequebook, node, one, one, UInt256.of(0), bounced = false))
                } else {
                    ChequebookFinding.NotAChequebook
                },
                7,
            ),
        ).also { proven += node to chequebook }

        override fun observeBatch(network: Network, node: Address, id: BatchId) = flowOf(
            SwarmReading.Verified(
                if (id == batch && node == bee.address) {
                    BatchFinding.Node(PostageBatch(id, node, 22, 16, false, UInt256.of(10), UInt256.of(1)))
                } else {
                    BatchFinding.OthersOwner(bee.address)
                },
                7,
            ),
        ).also { proven += node to id }

        override fun deployment(network: Network) = SwarmDeployment(bzz, stamp)
    }

    private fun eoa(byte: String, label: String, custody: AccountCustody) =
        Account(Network.GNOSIS, Address.of("0x" + byte.repeat(20)), AccountKind.EOA, label, custody)

    private val synced = LightClientStatus.Synced(headBlock = 1_000, headTimestamp = 1_800_000_000, finalized = null)

    private fun word(address: Address) = "0x" + address.hex.removePrefix("0x").lowercase().padStart(64, '0')

    private fun transfer(from: Address, to: Address, token: Address = bzz, block: Long = 150) = IndexedLog(
        ReceiptLog(token, listOf(FollowSwarmLogs.TRANSFER, word(from), word(to)), "0x" + "0".repeat(63) + "1"),
        block,
        "0x" + "bb".repeat(32),
        "0x" + "cc".repeat(32),
        0,
    )

    private fun batchCreated(owner: Address, id: BatchId = batch) = IndexedLog(
        ReceiptLog(
            stamp,
            listOf(FollowSwarmLogs.BATCH_CREATED, "0x" + id.hex),
            "0x" + "0".repeat(64) + "0".repeat(64) + word(owner).removePrefix("0x") + "0".repeat(62) + "16" +
                "0".repeat(62) + "10" + "0".repeat(64),
        ),
        300,
        "0x" + "bb".repeat(32),
        "0x" + "cc".repeat(32),
        1,
    )

    private fun coverage(vararg entries: Pair<LogWatchKey, LogCoverage>) {
        index.coverage.value = LogCoverageReading.Read(entries.toMap())
    }

    private fun TestScope.follow() {
        val follower = FollowSwarmLogs(
            accounts,
            candidates,
            contracts,
            lightClient,
            index,
            ObserveHeavyDownloads(FakeConnectionMeter(), MemoryMeteredDownloads()),
        )
        backgroundScope.launch { follower() }
        runCurrent()
    }

    @Test
    fun theChequebookIsFoundFromTheNodesOwnXbzzTransfers() = runTest {
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(100, 100L..200L))
        index.answer = { query ->
            if (query.key == LogWatchKey.Mention(bee.address)) {
                LogsReading.Logs(
                    listOf(
                        transfer(bee.address, chequebook),
                        // To the PostageStamp: a batch's payment, no chequebook.
                        transfer(bee.address, stamp),
                        // Another token: no chequebook either.
                        transfer(bee.address, stranger, token = Address.of("0x" + "70".repeat(20))),
                    ),
                )
            } else {
                LogsReading.Logs(emptyList())
            }
        }
        follow()
        lightClient.status.value = synced
        runCurrent()

        assertEquals(listOf(chequebook), kept.value[bee.id]?.chequebooks)
        assertEquals(listOf<Pair<Address, Any>>(bee.address to chequebook), proven, "nothing else is proven")
        val asked = index.queries.single { it.key == LogWatchKey.Mention(bee.address) }
        assertEquals(LogQuery(LogWatchKey.Mention(bee.address), 100, 200, listOf(FollowSwarmLogs.TRANSFER), 1), asked)
    }

    @Test
    fun aRecipientProvenNoChequebookIsNotTriedAgainAndWhatWasReadIsNotReadAgain() = runTest {
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(100, 100L..200L))
        index.answer = { query ->
            LogsReading.Logs(if (query.fromBlock == 100L) listOf(transfer(bee.address, stranger)) else emptyList())
        }
        follow()
        lightClient.status.value = synced
        runCurrent()
        assertEquals(listOf<Pair<Address, Any>>(bee.address to stranger), proven)

        // The head moves on: only the new blocks are read, and the stranger is not proven again.
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(100, 100L..260L))
        runCurrent()
        assertEquals(1, proven.size)
        assertEquals(listOf(201L..260L), index.queries.drop(1).map { it.fromBlock..it.toBlock })
        assertEquals(null, kept.value[bee.id])
    }

    @Test
    fun thePostageStampIsFollowedOnlyWhileASwarmNodeIsInTheWallet() = runTest {
        accounts.accounts.value = listOf(bee, alice)
        coverage(
            LogWatchKey.Mention(bee.address) to LogCoverage(500, 500L..1_000L),
            LogWatchKey.Mention(alice.address) to LogCoverage(100, 100L..1_000L),
        )
        index.answer = { LogsReading.Logs(emptyList()) }
        follow()
        lightClient.status.value = synced
        runCurrent()
        assertTrue(index.watched.isEmpty(), "no Swarm node yet: the PostageStamp is not followed")

        // The node's chequebook is added by hand: followed from the node's start, not Alice's.
        candidates.add(bee.id, SwarmCandidate.ChequebookAt(chequebook))
        runCurrent()
        assertEquals(mapOf<LogWatchKey, Long>(LogWatchKey.Emitter(stamp) to 500), index.watched)

        // The last node's candidates gone: the entry is dropped.
        kept.value = emptyMap()
        runCurrent()
        assertTrue(index.watched.isEmpty())
        assertEquals(listOf(LogWatchKey.Emitter(stamp)), index.asked.last().second)
    }

    @Test
    fun aBatchCreatedForAnEoaOfTheWalletIsFound() = runTest {
        accounts.accounts.value = listOf(bee, alice)
        kept.value = mapOf(bee.id to SwarmCandidateList.Loaded(listOf(chequebook), emptyList()))
        coverage(
            LogWatchKey.Mention(bee.address) to LogCoverage(200, 200L..1_000L),
            LogWatchKey.Emitter(stamp) to LogCoverage(200, 200L..1_000L),
        )
        index.answer = { query ->
            if (query.key == LogWatchKey.Emitter(stamp)) {
                // One for the node, one for someone not in the wallet, whose batch is never proven.
                LogsReading.Logs(listOf(batchCreated(bee.address), batchCreated(stranger, BatchId.of("cd".repeat(32)))))
            } else {
                LogsReading.Logs(emptyList())
            }
        }
        follow()
        lightClient.status.value = synced
        runCurrent()

        assertEquals(listOf(batch), kept.value[bee.id]?.batches)
        assertEquals(listOf<Pair<Address, Any>>(bee.address to batch), proven)
        assertEquals(
            listOf(FollowSwarmLogs.BATCH_CREATED),
            index.queries.single { it.key == LogWatchKey.Emitter(stamp) }.topic0s,
        )
    }

    @Test
    fun nothingIsReadUntilTheLightClientIsSyncedNorForASafe() = runTest {
        val safe = Account(Network.GNOSIS, stranger, AccountKind.SAFE, "Vault", AccountCustody.Watched)
        accounts.accounts.value = listOf(safe)
        coverage(LogWatchKey.Mention(stranger) to LogCoverage(100, 100L..200L))
        index.answer = { LogsReading.Logs(listOf(transfer(stranger, chequebook))) }
        follow()
        lightClient.status.value = LightClientStatus.Starting
        runCurrent()
        assertTrue(index.queries.isEmpty())
        lightClient.status.value = synced
        runCurrent()
        assertTrue(index.queries.isEmpty(), "a Safe is no Swarm node")
        assertTrue(proven.isEmpty())
    }

    @Test
    fun aBatchCreatedLogIsDecodedFromItsData() {
        assertEquals(bee.address to batch, FollowSwarmLogs.batchCreated(batchCreated(bee.address)))
        val truncated = batchCreated(bee.address).let { it.copy(log = it.log.copy(data = "0x00")) }
        assertEquals(null, FollowSwarmLogs.batchCreated(truncated))
    }
}
