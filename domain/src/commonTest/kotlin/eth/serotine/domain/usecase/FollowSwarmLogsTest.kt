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
import eth.serotine.domain.repository.ConnectionCost
import eth.serotine.domain.repository.IndexedLog
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.LogCoverage
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import eth.serotine.domain.repository.ReadUnavailable
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

        override suspend fun add(node: AccountId, candidate: SwarmCandidate, byHand: Boolean): SwarmCandidateChange {
            val list = kept.value[node] ?: SwarmCandidateList.Loaded(emptyList(), emptyList())
            if (!byHand && candidate in list.dismissed) return SwarmCandidateChange.DISMISSED
            val next = when (candidate) {
                is SwarmCandidate.ChequebookAt -> list.copy(chequebooks = list.chequebooks + candidate.address)
                is SwarmCandidate.Batch -> list.copy(batches = list.batches + candidate.id)
            }
            kept.value = kept.value + (node to next)
            return SwarmCandidateChange.CHANGED
        }

        override suspend fun remove(node: AccountId, candidate: SwarmCandidate) = SwarmCandidateChange.NOT_FOUND

        override suspend fun forget(node: AccountId): SwarmCandidateChange {
            if (node !in kept.value) return SwarmCandidateChange.NOT_FOUND
            kept.value = kept.value - node
            return SwarmCandidateChange.CHANGED
        }
    }

    /** Proofs that cannot be had yet: each answers unverified, once. */
    private val unverifiedOnce = mutableSetOf<Address>()

    /** Each batch's owner, as the fake PostageStamp proves it. */
    private val batchOwners = mutableMapOf(batch to bee.address)

    /** Chequebooks not deployed yet: each proves as no chequebook. */
    private val notYet = mutableSetOf<Address>()

    /** Only [chequebook] is a chequebook, the node's; only [batch] is a batch, owned by [bee]. */
    private val contracts = object : SwarmContracts {
        override fun observeChequebook(
            network: Network,
            node: Address,
            chequebook: Address,
        ): Flow<SwarmReading<ChequebookFinding>> {
            proven += node to chequebook
            if (unverifiedOnce.remove(chequebook)) return flowOf(SwarmReading.Unavailable(ReadUnavailable.UNPROVEN))
            val deployed = chequebook == this@FollowSwarmLogsTest.chequebook && chequebook !in notYet
            val finding = if (deployed && node == bee.address) {
                val one = UInt256.of(1)
                ChequebookFinding.Node(Chequebook(chequebook, node, one, one, UInt256.of(0), bounced = false))
            } else {
                ChequebookFinding.NotAChequebook
            }
            return flowOf(SwarmReading.Verified(finding, 7))
        }

        override fun observeBatch(network: Network, node: Address, id: BatchId) = flowOf(
            SwarmReading.Verified(
                if (batchOwners[id] == node) {
                    BatchFinding.Node(PostageBatch(id, node, 22, 16, false, UInt256.of(10), UInt256.of(1)))
                } else {
                    BatchFinding.OthersOwner(bee.address)
                },
                7,
            ),
        ).also { proven += node to id }

        override fun deployment(network: Network) =
            SwarmDeployment(eth.serotine.domain.model.Token(bzz, "BZZ", "BZZ on xDai", 16, icon = "bzz"), stamp)

        override suspend fun postage(network: Network) = eth.serotine.domain.repository.PostageReading.NotPinned
    }

    private fun eoa(byte: String, label: String, custody: AccountCustody) =
        Account(Network.GNOSIS, Address.of("0x" + byte.repeat(20)), AccountKind.EOA, label, custody)

    private val synced = LightClientStatus.Synced(headBlock = 1_000, headTimestamp = 1_800_000_000, finalized = null)

    private fun word(address: Address) = "0x" + address.hex.removePrefix("0x").lowercase().padStart(64, '0')

    private fun transfer(
        from: Address,
        to: Address,
        token: Address = bzz,
        block: Long = 150,
        transaction: String = "0x" + "cc".repeat(32),
    ) = IndexedLog(
        ReceiptLog(token, listOf(FollowSwarmLogs.TRANSFER, word(from), word(to)), "0x" + "0".repeat(63) + "1"),
        block,
        "0x" + "bb".repeat(32),
        transaction,
        0,
    )

    private fun batchCreated(
        owner: Address,
        id: BatchId = batch,
        transaction: String = "0x" + "cc".repeat(32),
    ) = IndexedLog(
        ReceiptLog(
            stamp,
            listOf(FollowSwarmLogs.BATCH_CREATED, "0x" + id.hex),
            "0x" + "0".repeat(64) + "0".repeat(64) + word(owner).removePrefix("0x") + "0".repeat(62) + "16" +
                "0".repeat(62) + "10" + "0".repeat(64),
        ),
        300,
        "0x" + "bb".repeat(32),
        transaction,
        1,
    )

    private fun coverage(vararg entries: Pair<LogWatchKey, LogCoverage>) {
        index.coverage.value = LogCoverageReading.Read(entries.toMap())
    }

    /** The device's connection: unmetered unless a test says otherwise. */
    private val meter = FakeConnectionMeter()

    private fun TestScope.follow() {
        val follower = FollowSwarmLogs(
            accounts,
            candidates,
            contracts,
            lightClient,
            index,
            ObserveHeavyDownloads(meter, MemoryMeteredDownloads()),
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

        // The head moves on: only the new blocks are read, with the top 64 read before, and the
        // stranger is not proven again.
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(100, 100L..260L))
        runCurrent()
        assertEquals(1, proven.size)
        assertEquals(listOf(137L..260L), index.queries.drop(1).map { it.fromBlock..it.toBlock })
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
        candidates.add(bee.id, SwarmCandidate.ChequebookAt(chequebook), byHand = true)
        runCurrent()
        assertEquals(mapOf<LogWatchKey, Long>(LogWatchKey.Emitter(stamp) to 500), index.watched)

        // The last node's candidates gone: the entry is dropped.
        kept.value = emptyMap()
        runCurrent()
        assertTrue(index.watched.isEmpty())
        assertEquals(listOf(LogWatchKey.Emitter(stamp)), index.asked.last().second)
    }

    @Test
    fun aBatchTheWalletPaidForIsFound() = runTest {
        accounts.accounts.value = listOf(bee, alice)
        kept.value = mapOf(bee.id to SwarmCandidateList.Loaded(listOf(chequebook), emptyList()))
        coverage(
            LogWatchKey.Mention(bee.address) to LogCoverage(200, 200L..1_000L),
            LogWatchKey.Mention(alice.address) to LogCoverage(200, 200L..1_000L),
            LogWatchKey.Emitter(stamp) to LogCoverage(200, 200L..1_000L),
        )
        val bought = "0x" + "a1".repeat(32)
        index.answer = { query ->
            when (query.key) {
                // Alice paid for a batch the node owns, in one transaction.
                LogWatchKey.Mention(alice.address) ->
                    LogsReading.Logs(listOf(transfer(alice.address, stamp, transaction = bought)))
                // One for the node in that transaction; one for someone not in the wallet, never proven.
                LogWatchKey.Emitter(stamp) -> LogsReading.Logs(
                    listOf(
                        batchCreated(bee.address, transaction = bought),
                        batchCreated(stranger, BatchId.of("cd".repeat(32))),
                    ),
                )
                else -> LogsReading.Logs(emptyList())
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
        // A Safe's own payments are read (it may pay for a batch), but it is no Swarm node.
        assertTrue(proven.isEmpty(), "a Safe is no Swarm node")
        assertEquals(emptyMap(), kept.value)
    }

    @Test
    fun aCandidateThatCouldNotBeVerifiedIsTriedAgainNextRound() = runTest {
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(100, 100L..200L))
        index.answer = { query ->
            LogsReading.Logs(if (query.fromBlock == 100L) listOf(transfer(bee.address, chequebook)) else emptyList())
        }
        unverifiedOnce += chequebook
        follow()
        lightClient.status.value = synced
        runCurrent()
        assertEquals(null, kept.value[bee.id], "nothing kept on an unverified reading")

        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(100, 100L..201L))
        runCurrent()
        assertEquals(listOf(chequebook), kept.value[bee.id]?.chequebooks)
    }

    @Test
    fun aCandidateTheUserRemovedIsNotAddedBack() = runTest {
        val removed = setOf<SwarmCandidate>(SwarmCandidate.ChequebookAt(chequebook))
        kept.value = mapOf(bee.id to SwarmCandidateList.Loaded(emptyList(), emptyList(), dismissed = removed))
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(100, 100L..200L))
        index.answer = { LogsReading.Logs(listOf(transfer(bee.address, chequebook))) }
        follow()
        lightClient.status.value = synced
        runCurrent()
        assertTrue(proven.isEmpty())
        assertEquals(emptyList(), kept.value[bee.id]?.chequebooks)
        assertTrue(index.watched.isEmpty(), "a removal alone makes no Swarm node")
    }

    @Test
    fun manyRecipientsAreAllTriedOverRoundsTheNewestFirst() = runTest {
        // The chequebook was funded first, then 20 others were paid: more than a round proves.
        val others = (1..20).map { Address.of("0x" + it.toString(16).padStart(2, '0').repeat(20)) }
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(100, 100L..200L))
        index.answer = { query ->
            LogsReading.Logs(
                if (query.fromBlock == 100L) {
                    listOf(transfer(bee.address, chequebook, block = 101)) +
                        others.mapIndexed { i, to -> transfer(bee.address, to, block = 110L + i) }
                } else {
                    emptyList()
                },
            )
        }
        follow()
        lightClient.status.value = synced
        runCurrent()
        assertEquals(FollowSwarmLogs.MAX_PROOFS_PER_ROUND, proven.size)
        assertEquals(bee.address to others.last(), proven.first(), "the newest first")
        assertEquals(null, kept.value[bee.id])

        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(100, 100L..201L))
        runCurrent()
        assertEquals(listOf(chequebook), kept.value[bee.id]?.chequebooks, "the oldest is reached in the next round")
    }

    @Test
    fun anAccountAddedAgainWithANewStartIsReadAgain() = runTest {
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(100, 100L..200L))
        index.answer = { LogsReading.Logs(emptyList()) }
        follow()
        lightClient.status.value = synced
        runCurrent()

        accounts.accounts.value = emptyList()
        runCurrent()
        accounts.accounts.value = listOf(bee)
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(500, 500L..600L))
        index.answer = { query ->
            val logs =
                if (query.fromBlock == 500L) listOf(transfer(bee.address, chequebook, block = 550)) else emptyList()
            LogsReading.Logs(logs)
        }
        runCurrent()
        assertEquals(
            LogQuery(LogWatchKey.Mention(bee.address), 500, 600, listOf(FollowSwarmLogs.TRANSFER), 1),
            index.queries.last(),
        )
        assertEquals(listOf(chequebook), kept.value[bee.id]?.chequebooks)
    }

    @Test
    fun aBatchAStrangerCreatedForTheNodeIsNotKept() = runTest {
        // Owner's decision, 2026-11-23: only a batch an account of the wallet paid for is found.
        kept.value = mapOf(bee.id to SwarmCandidateList.Loaded(listOf(chequebook), emptyList()))
        coverage(
            LogWatchKey.Mention(bee.address) to LogCoverage(200, 200L..1_000L),
            LogWatchKey.Emitter(stamp) to LogCoverage(200, 200L..1_000L),
        )
        index.answer = { query ->
            if (query.key == LogWatchKey.Emitter(stamp)) {
                LogsReading.Logs(listOf(batchCreated(bee.address, transaction = "0x" + "5e".repeat(32))))
            } else {
                LogsReading.Logs(emptyList())
            }
        }
        follow()
        lightClient.status.value = synced
        runCurrent()
        assertTrue(proven.isEmpty())
        assertEquals(emptyList(), kept.value[bee.id]?.batches)
    }

    @Test
    fun theTopBlocksReadBeforeAreReadAgainAsTheSpanGrows() = runTest {
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(100, 100L..200L))
        index.answer = { LogsReading.Logs(emptyList()) }
        follow()
        lightClient.status.value = synced
        runCurrent()
        // A reorganisation replaced block 190 between two polls: its new log is found.
        index.answer = { query ->
            val holds190 = 190L in query.fromBlock..query.toBlock
            LogsReading.Logs(if (holds190) listOf(transfer(bee.address, chequebook, block = 190)) else emptyList())
        }
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(100, 100L..201L))
        runCurrent()
        assertEquals(137L..201L, index.queries.last().let { it.fromBlock..it.toBlock })
        assertEquals(listOf(chequebook), kept.value[bee.id]?.chequebooks)
    }

    @Test
    fun aRefusalIsLiftedByALogOfALaterBlock() = runTest {
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(100, 100L..200L))
        index.answer = { query ->
            val first = query.fromBlock == 100L
            LogsReading.Logs(if (first) listOf(transfer(bee.address, chequebook, block = 150)) else emptyList())
        }
        unverifiedOnce += chequebook
        // Proven not the node's at first: the fake says no chequebook while this is set.
        notYet += chequebook
        follow()
        lightClient.status.value = synced
        runCurrent()
        assertEquals(null, kept.value[bee.id])
        notYet.clear()
        // The same log read again (the top blocks) does not lift it; one of a later block does.
        index.answer = { query ->
            val later = query.toBlock >= 250L
            LogsReading.Logs(if (later) listOf(transfer(bee.address, chequebook, block = 250)) else emptyList())
        }
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(100, 100L..260L))
        runCurrent()
        assertEquals(listOf(chequebook), kept.value[bee.id]?.chequebooks)
    }

    @Test
    fun anEoaThatJoinsHasThePostageStampsLogsMatchedAgain() = runTest {
        kept.value = mapOf(bee.id to SwarmCandidateList.Loaded(listOf(chequebook), emptyList()))
        val bought = "0x" + "a1".repeat(32)
        val aliceBatch = BatchId.of("cd".repeat(32))
        batchOwners[aliceBatch] = alice.address
        coverage(
            LogWatchKey.Mention(bee.address) to LogCoverage(200, 200L..1_000L),
            LogWatchKey.Emitter(stamp) to LogCoverage(200, 200L..1_000L),
        )
        index.answer = { query ->
            when (query.key) {
                LogWatchKey.Mention(alice.address) ->
                    LogsReading.Logs(listOf(transfer(alice.address, stamp, transaction = bought)))
                LogWatchKey.Emitter(stamp) -> LogsReading.Logs(listOf(batchCreated(alice.address, aliceBatch, bought)))
                else -> LogsReading.Logs(emptyList())
            }
        }
        follow()
        lightClient.status.value = synced
        runCurrent()
        assertEquals(null, kept.value[alice.id], "Alice is not in the wallet yet")

        // Alice is added, her entry already covering the block her batch was bought in.
        accounts.accounts.value = listOf(bee, alice)
        coverage(
            LogWatchKey.Mention(bee.address) to LogCoverage(200, 200L..1_000L),
            LogWatchKey.Mention(alice.address) to LogCoverage(200, 200L..1_000L),
            LogWatchKey.Emitter(stamp) to LogCoverage(200, 200L..1_000L),
        )
        runCurrent()
        assertEquals(listOf(aliceBatch), kept.value[alice.id]?.batches)
    }

    @Test
    fun anAccountThatPaidThePostageStampHasItFollowed() = runTest {
        // No chequebook known: the node's own payment for a batch is the sign.
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(300, 300L..1_000L))
        index.answer = { query ->
            val own = query.key == LogWatchKey.Mention(bee.address)
            LogsReading.Logs(if (own) listOf(transfer(bee.address, stamp)) else emptyList())
        }
        follow()
        lightClient.status.value = synced
        runCurrent()
        assertEquals(mapOf<LogWatchKey, Long>(LogWatchKey.Emitter(stamp) to 300), index.watched)
    }

    @Test
    fun eachChangeCarriesThePauseOfTheHeavyDownloads() = runTest {
        meter.cost.value = ConnectionCost.METERED
        kept.value = mapOf(bee.id to SwarmCandidateList.Loaded(listOf(chequebook), emptyList()))
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(300, 300L..1_000L))
        index.answer = { LogsReading.Logs(emptyList()) }
        follow()
        lightClient.status.value = synced
        runCurrent()
        assertEquals(listOf(true), index.pauses, "a metered connection the user did not allow pauses the walk")
    }

    @Test
    fun aChangeTheEngineDidNotTakeIsAskedAgain() = runTest {
        index.takes = false
        kept.value = mapOf(bee.id to SwarmCandidateList.Loaded(listOf(chequebook), emptyList()))
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(300, 300L..1_000L))
        index.answer = { LogsReading.Logs(emptyList()) }
        follow()
        lightClient.status.value = synced
        runCurrent()
        assertEquals(1, index.asked.size)
        index.takes = true
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(300, 300L..1_001L))
        runCurrent()
        assertEquals(mapOf<LogWatchKey, Long>(LogWatchKey.Emitter(stamp) to 300), index.watched)
    }

    @Test
    fun theStartIsLoweredForANodeWithAnEarlierOneAndEverythingIsAskedAgainAfterOff() = runTest {
        accounts.accounts.value = listOf(bee, alice)
        kept.value = mapOf(bee.id to SwarmCandidateList.Loaded(listOf(chequebook), emptyList()))
        coverage(
            LogWatchKey.Mention(bee.address) to LogCoverage(500, 500L..1_000L),
            LogWatchKey.Mention(alice.address) to LogCoverage(100, 100L..1_000L),
        )
        index.answer = { LogsReading.Logs(emptyList()) }
        follow()
        lightClient.status.value = synced
        runCurrent()
        assertEquals(500L, index.watched[LogWatchKey.Emitter(stamp)])

        kept.value = kept.value + (alice.id to SwarmCandidateList.Loaded(emptyList(), listOf(batch)))
        runCurrent()
        assertEquals(100L, index.watched[LogWatchKey.Emitter(stamp)])

        // Turned off and on: the engine's chain is new, and the PostageStamp is asked for again.
        val asked = index.asked.size
        lightClient.status.value = LightClientStatus.Off
        runCurrent()
        lightClient.status.value = synced
        runCurrent()
        assertEquals(asked + 1, index.asked.size)
    }

    @Test
    fun anAccountThatLeftTheWalletTakesItsCandidatesWithIt() = runTest {
        kept.value = mapOf(
            bee.id to SwarmCandidateList.Loaded(listOf(chequebook), emptyList()),
            alice.id to SwarmCandidateList.Loaded(emptyList(), listOf(batch)),
        )
        coverage(LogWatchKey.Mention(bee.address) to LogCoverage(300, 300L..1_000L))
        index.answer = { LogsReading.Logs(emptyList()) }
        follow()
        lightClient.status.value = synced
        runCurrent()
        assertEquals(setOf(bee.id), kept.value.keys, "Alice is not in the wallet")
    }

    @Test
    fun aBatchCreatedLogIsDecodedFromItsData() {
        assertEquals(bee.address to batch, FollowSwarmLogs.batchCreated(batchCreated(bee.address)))
        val truncated = batchCreated(bee.address).let { it.copy(log = it.log.copy(data = "0x00")) }
        assertEquals(null, FollowSwarmLogs.batchCreated(truncated))
    }
}
