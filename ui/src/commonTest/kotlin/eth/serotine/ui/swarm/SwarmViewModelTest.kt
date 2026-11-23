package eth.serotine.ui.swarm

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
import eth.serotine.domain.usecase.AddSwarmCandidate
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveSwarmNode
import eth.serotine.domain.usecase.RemoveSwarmCandidate
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.MemoryNetworkSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SwarmViewModelTest {

    private val bee = Account(
        Network.GNOSIS,
        Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"),
        AccountKind.EOA,
        "Bee node",
        AccountCustody.Watched,
    )
    private val chequebookAddress = Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359")
    private val stranger = Address.of("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb")
    private val batchId = BatchId.of("ab".repeat(32))

    private val accounts = FakeAccounts(bee)
    private val networks = MemoryNetworkSettings(setOf(Network.GNOSIS))

    /** What a removal answers: saved by default. */
    private var removeSaves = true
    private val kept = MutableStateFlow(SwarmCandidateList.Loaded(emptyList(), emptyList()))
    private val removed = mutableListOf<SwarmCandidate>()

    private val candidates = object : SwarmCandidates {
        override fun observe(node: AccountId): Flow<SwarmCandidateList> = kept.map { it }

        override fun observeNodes(network: Network) = flowOf(mapOf(bee.id to kept.value))

        override suspend fun add(node: AccountId, candidate: SwarmCandidate, byHand: Boolean): SwarmCandidateChange {
            val list = kept.value
            kept.value = when (candidate) {
                is SwarmCandidate.ChequebookAt -> list.copy(chequebooks = list.chequebooks + candidate.address)
                is SwarmCandidate.Batch -> list.copy(batches = list.batches + candidate.id)
            }
            return SwarmCandidateChange.CHANGED
        }

        override suspend fun remove(node: AccountId, candidate: SwarmCandidate): SwarmCandidateChange {
            if (!removeSaves) return SwarmCandidateChange.NOT_SAVED
            removed += candidate
            return SwarmCandidateChange.CHANGED
        }

        override suspend fun forget(node: AccountId) = SwarmCandidateChange.NOT_FOUND
    }

    /** When set, a batch's reading waits for it: a slow proof. */
    private var slowBatch: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** What the light client proves the chequebook to be; the node's by default. */
    private var chequebookReading: SwarmReading<ChequebookFinding> = SwarmReading.Verified(
        ChequebookFinding.Node(
            Chequebook(
                chequebookAddress,
                bee.address,
                balance = UInt256.of(12_000_000_000_000_000),
                liquidBalance = UInt256.of(10_000_000_000_000_000),
                totalPaidOut = UInt256.of(0),
                bounced = true,
            ),
        ),
        43_000_512,
    )

    private val contracts = object : SwarmContracts {
        override fun observeChequebook(network: Network, node: Address, chequebook: Address) =
            flowOf(chequebookReading)

        override fun observeBatch(network: Network, node: Address, id: BatchId) = kotlinx.coroutines.flow.flow {
            slowBatch?.await()
            emit(batchReading(id))
        }

        private fun batchReading(id: BatchId): SwarmReading<BatchFinding> = (
            if (id == batchId) {
                SwarmReading.Verified(
                    BatchFinding.Node(
                        PostageBatch(
                            id,
                            bee.address,
                            depth = 22,
                            bucketDepth = 16,
                            immutable = true,
                            remainingPerChunk = UInt256.of(5_730_943_641),
                            lastPrice = UInt256.of(206_979),
                        ),
                    ),
                    43_000_512,
                )
            } else {
                SwarmReading.Verified(BatchFinding.OthersOwner(stranger), 43_000_512)
            }
        )

        override fun deployment(network: Network) = null

        override suspend fun chequebook(
            network: eth.serotine.domain.model.Network,
            node: eth.serotine.domain.model.Address,
            chequebook: eth.serotine.domain.model.Address,
        ): eth.serotine.domain.repository.SwarmReading<eth.serotine.domain.repository.ChequebookFinding> =
            eth.serotine.domain.repository.SwarmReading.NotPinned

        override suspend fun postage(network: Network, node: Address?, id: BatchId?) =
            eth.serotine.domain.repository.PostageReading.NotPinned
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): SwarmViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = SwarmViewModel(
            bee.id,
            ObserveAccount(accounts),
            ObserveSwarmNode(candidates, contracts),
            AddSwarmCandidate(candidates, contracts),
            RemoveSwarmCandidate(candidates),
            ObserveEnabledNetworks(networks),
            SetNetworkEnabled(networks),
            ObserveAccounts(accounts),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    private val SwarmViewModel.ready: SwarmUiState.Ready
        get() = assertIs<SwarmUiState.Ready>(uiState.value)

    @Test
    fun theChequebookAndTheBatchAreShownDisplayReady() = runTest {
        kept.value = SwarmCandidateList.Loaded(listOf(chequebookAddress), listOf(batchId))
        val viewModel = viewModel()
        runCurrent()
        val ready = viewModel.ready
        assertEquals("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", ready.address)
        val chequebook = ready.chequebooks.single()
        assertEquals("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359", chequebook.address)
        assertEquals(listOf("Balance 1.2 BZZ", "Free to withdraw 1 BZZ", "Paid out 0 BZZ"), chequebook.lines)
        assertEquals(SwarmTexts.BOUNCED, chequebook.warning)
        assertEquals("Verified at block 43,000,512", chequebook.status)
        val batch = ready.batches.single()
        assertEquals("Depth 22 · 7.73 GB of 17.18 GB theoretical", batch.capacity)
        // 27,688 blocks of 5 s: a day and a half.
        assertEquals("About 38 hours left at the current price", batch.timeLeft)
        assertNull(ready.chequebookNote)
    }

    @Test
    fun theBatchActionsAreGreyedOutSayingWhatIsMissing() = runTest {
        kept.value = SwarmCandidateList.Loaded(emptyList(), listOf(batchId))
        val viewModel = viewModel()
        runCurrent()
        // A watched node and no key held here on Gnosis Chain: nothing can pay, and nothing signs for the node.
        assertEquals(SwarmAction(SwarmTexts.NO_PAYER), viewModel.ready.buy)
        assertEquals(SwarmAction(SwarmTexts.NO_PAYER), viewModel.ready.batches.single().topUp)
        assertEquals(SwarmAction(SwarmTexts.NODE_KEY_NOT_HELD), viewModel.ready.batches.single().dilute)

        // Another account's key held here pays; the node's own key still signs a dilution only.
        accounts.add(
            Account(Network.GNOSIS, stranger, AccountKind.EOA, "Savings", AccountCustody.InKeyStore),
        )
        runCurrent()
        assertEquals(SwarmAction(), viewModel.ready.buy)
        assertEquals(SwarmAction(), viewModel.ready.batches.single().topUp)
        assertEquals(SwarmAction(SwarmTexts.NODE_KEY_NOT_HELD), viewModel.ready.batches.single().dilute)

        accounts.setCustody(bee.id, AccountCustody.InKeyStore)
        runCurrent()
        assertEquals(SwarmAction(), viewModel.ready.batches.single().dilute)

        // Gnosis Chain off: each says so, with Turn on.
        networks.setEnabled(Network.GNOSIS, false)
        runCurrent()
        assertEquals(SwarmAction(SwarmTexts.NETWORK_OFF_ACTION, turnOn = true), viewModel.ready.buy)
        assertEquals(SwarmAction(SwarmTexts.NETWORK_OFF_ACTION, turnOn = true), viewModel.ready.batches.single().dilute)
    }

    @Test
    fun withdrawIsTheNodesOwnKeysAndTakesSomethingFree() = runTest {
        kept.value = SwarmCandidateList.Loaded(listOf(chequebookAddress), emptyList())
        val viewModel = viewModel()
        runCurrent()
        assertEquals(
            SwarmAction(SwarmTexts.NODE_KEY_NOT_WITHDRAWING),
            viewModel.ready.chequebooks.single().withdraw,
        )
        accounts.setCustody(bee.id, AccountCustody.InKeyStore)
        runCurrent()
        assertEquals(SwarmAction(), viewModel.ready.chequebooks.single().withdraw)
        networks.setEnabled(Network.GNOSIS, false)
        runCurrent()
        assertEquals(
            SwarmAction(SwarmTexts.NETWORK_OFF_ACTION, turnOn = true),
            viewModel.ready.chequebooks.single().withdraw,
        )
    }

    @Test
    fun candidatesSetAsideSaySo() = runTest {
        kept.value = SwarmCandidateList.Loaded(emptyList(), emptyList(), setAside = true)
        val viewModel = viewModel()
        runCurrent()
        assertEquals(SwarmTexts.SET_ASIDE, viewModel.ready.note)
    }

    @Test
    fun noneFoundSaysHowToAddOne() = runTest {
        val viewModel = viewModel()
        runCurrent()
        assertEquals(SwarmTexts.NO_CHEQUEBOOK, viewModel.ready.chequebookNote)
        assertEquals(SwarmTexts.NO_BATCH, viewModel.ready.batchNote)
    }

    @Test
    fun aBatchIsAddedByHandOnceProvenAndRefusedSayingWhyWhenNot() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.startAdd(AddKind.BATCH)
        viewModel.onAddText("0x12")
        viewModel.confirmAdd()
        assertEquals(SwarmTexts.NOT_A_BATCH_ID, viewModel.ready.adding?.error)

        viewModel.onAddText("cd".repeat(32))
        viewModel.confirmAdd()
        runCurrent()
        assertEquals(
            "This batch is owned by 0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB, not this node.",
            viewModel.ready.adding?.error,
        )

        viewModel.onAddText("0x" + "AB".repeat(32))
        viewModel.confirmAdd()
        runCurrent()
        assertNull(viewModel.ready.adding, "kept: the form closes")
        assertEquals(listOf(batchId), kept.value.batches)
    }

    @Test
    fun aCheckAnsweredAfterItsFormWasCancelledReachesNoOtherForm() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        slowBatch = gate
        val viewModel = viewModel()
        runCurrent()
        viewModel.startAdd(AddKind.BATCH)
        viewModel.onAddText("cd".repeat(32))
        viewModel.confirmAdd()
        viewModel.cancelAdd()
        viewModel.startAdd(AddKind.CHEQUEBOOK)
        gate.complete(Unit)
        runCurrent()
        val open = assertIs<AddForm>(viewModel.ready.adding)
        assertEquals(AddForm(AddKind.CHEQUEBOOK, attempt = open.attempt), open, "the new form, untouched")
    }

    @Test
    fun aCheckTakenBackKeepsNothing() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        slowBatch = gate
        val viewModel = viewModel()
        runCurrent()
        viewModel.startAdd(AddKind.BATCH)
        // The node's own batch: kept, had its check not been taken back.
        viewModel.onAddText("ab".repeat(32))
        viewModel.confirmAdd()
        viewModel.cancelAdd()
        gate.complete(Unit)
        runCurrent()
        assertEquals(emptyList(), kept.value.batches)
    }

    @Test
    fun addIsGreyedOutWhileGnosisChainIsOffAndTurnOnTurnsItOn() = runTest {
        networks.on.value = emptySet()
        val viewModel = viewModel()
        runCurrent()
        assertEquals(SwarmTexts.NETWORK_OFF, viewModel.ready.addReason)
        viewModel.turnOnNetwork()
        runCurrent()
        assertNull(viewModel.ready.addReason)
        assertEquals(setOf(Network.GNOSIS), networks.on.value)
    }

    @Test
    fun aRemovalThatCouldNotBeSavedSaysSo() = runTest {
        removeSaves = false
        kept.value = SwarmCandidateList.Loaded(listOf(chequebookAddress), emptyList())
        val viewModel = viewModel()
        runCurrent()
        viewModel.removeChequebook(viewModel.ready.chequebooks.single().address)
        runCurrent()
        assertEquals(SwarmTexts.NOT_REMOVED, viewModel.ready.message)
    }

    @Test
    fun aHeldRowNamesItsBlockOnceAndSaysItMayHaveChanged() {
        val text = SwarmTexts.stated(SwarmTexts.GONE, 43_000_512, held = true)
        assertEquals(false, text.endsWith(".."), text)
        assertEquals(true, text.endsWith("it may have changed since."), text)
        assertEquals(
            SwarmTexts.GONE + " Verified at block 43,000,512.",
            SwarmTexts.stated(SwarmTexts.GONE, 43_000_512, held = false),
        )
    }

    @Test
    fun aChequebookAddressIsCheckedAgainstItsChecksum() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.startAdd(AddKind.CHEQUEBOOK)
        viewModel.onAddText("0xFB6916095ca1df60bB79Ce92cE3Ea74c37c5d359")
        viewModel.confirmAdd()
        assertEquals(SwarmTexts.CHECKSUM, viewModel.ready.adding?.error)
        viewModel.cancelAdd()
        assertNull(viewModel.ready.adding)
    }

    @Test
    fun aReadingThatCannotBeHadSaysWhy() = runTest {
        chequebookReading = SwarmReading.Unavailable(ReadUnavailable.NETWORK_OFF)
        kept.value = SwarmCandidateList.Loaded(listOf(chequebookAddress), emptyList())
        val viewModel = viewModel()
        runCurrent()
        val row = viewModel.ready.chequebooks.single()
        assertEquals(emptyList(), row.lines)
        assertEquals(SwarmTexts.unavailable(ReadUnavailable.NETWORK_OFF), row.status)
    }

    @Test
    fun removingOneRemembersItAsRemoved() = runTest {
        kept.value = SwarmCandidateList.Loaded(listOf(chequebookAddress), listOf(batchId))
        val viewModel = viewModel()
        runCurrent()
        viewModel.removeChequebook(viewModel.ready.chequebooks.single().address)
        viewModel.removeBatch(viewModel.ready.batches.single().id)
        runCurrent()
        assertEquals(listOf(SwarmCandidate.ChequebookAt(chequebookAddress), SwarmCandidate.Batch(batchId)), removed)
    }

    @Test
    fun aSafeOrAnAccountOffGnosisChainIsNoSwarmNode() = runTest {
        accounts.accounts.value = listOf(bee.copy(kind = AccountKind.SAFE))
        val viewModel = viewModel()
        runCurrent()
        assertEquals(SwarmUiState.Unavailable(SwarmTexts.NOT_SWARM), viewModel.uiState.value)
    }

    @Test
    fun anAccountThatLeftTheWalletEndsTheScreen() = runTest {
        val viewModel = viewModel()
        runCurrent()
        accounts.accounts.value = emptyList()
        runCurrent()
        assertEquals(SwarmUiState.Gone, viewModel.uiState.value)
    }
}
