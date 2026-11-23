package eth.serotine.ui.postage

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PostageBatch
import eth.serotine.domain.model.PostageState
import eth.serotine.domain.model.SwarmDeployment
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BatchFinding
import eth.serotine.domain.repository.PostageReading
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.SwarmCandidate
import eth.serotine.domain.repository.SwarmCandidateChange
import eth.serotine.domain.repository.SwarmCandidateList
import eth.serotine.domain.repository.SwarmCandidates
import eth.serotine.domain.repository.SwarmContracts
import eth.serotine.domain.repository.SwarmReading
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObservePostageBatch
import eth.serotine.domain.usecase.ObserveSwarmNodes
import eth.serotine.domain.usecase.QuoteBatchDilution
import eth.serotine.domain.usecase.QuoteBatchPurchase
import eth.serotine.domain.usecase.QuoteBatchTopUp
import eth.serotine.domain.usecase.SendBatchApproval
import eth.serotine.domain.usecase.SendBatchCall
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeBalances
import eth.serotine.ui.FakeBroadcaster
import eth.serotine.ui.FakeKeys
import eth.serotine.ui.FakeSigner
import eth.serotine.ui.FakeTokenBalances
import eth.serotine.ui.FakeTransactionInputs
import eth.serotine.ui.MemoryOutbox
import eth.serotine.ui.MemorySentTransfers
import eth.serotine.ui.components.STALE_QUOTE
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** The postage screen's state machine, over the batch use cases with fake ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostageViewModelTest {

    private val bzz =
        Token(Address.of("0xdbf3ea6f5bee45c02255b2c26a16f300502f68da"), "BZZ", "BZZ on xDai", 16, icon = "bzz")
    private val stamp = Address.of("0x45a1502382541cd610cc9068e88727426b696293")
    private val id = BatchId.of("ab".repeat(32))
    private val minimum = UInt256.of(3_578_031_360)
    private var price = UInt256.of(207_062)

    private var node = Account(
        Network.GNOSIS,
        Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"),
        AccountKind.EOA,
        "Bee node",
        AccountCustody.Watched,
    )
    private val payer = Account(
        Network.GNOSIS,
        Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"),
        AccountKind.EOA,
        "Savings",
        AccountCustody.InKeyStore,
    )

    private var batch: SwarmReading<BatchFinding> = SwarmReading.Verified(
        BatchFinding.Node(PostageBatch(id, node.address, 22, 16, true, UInt256.of(8_000_000_000), UInt256.of(207_062))),
        100,
    )

    private val contracts = object : SwarmContracts {
        override fun observeChequebook(network: Network, node: Address, chequebook: Address) =
            flowOf(SwarmReading.NotPinned)
        override fun observeBatch(network: Network, node: Address, id: BatchId) = flowOf(batch)
        override fun deployment(network: Network) = SwarmDeployment(bzz, stamp)
        override suspend fun chequebook(
            network: eth.serotine.domain.model.Network,
            node: eth.serotine.domain.model.Address,
            chequebook: eth.serotine.domain.model.Address,
        ): eth.serotine.domain.repository.SwarmReading<eth.serotine.domain.repository.ChequebookFinding> =
            eth.serotine.domain.repository.SwarmReading.NotPinned

        override suspend fun postage(network: Network, node: Address?, id: BatchId?): PostageReading =
            PostageReading.Read(
                PostageState(false, price, minimum),
                100,
                if (id == null) null else (batch as? SwarmReading.Verified)?.finding,
            )
    }

    /** The wallet's Swarm nodes, as the candidates kept say. */
    private var swarmNodes = emptyMap<AccountId, SwarmCandidateList.Loaded>()

    private val candidates = object : SwarmCandidates {
        val added = mutableListOf<SwarmCandidate>()
        override fun observe(node: AccountId) = flowOf(SwarmCandidateList.Loaded(emptyList(), emptyList()))
        override fun observeNodes(network: Network) = flowOf(swarmNodes)
        override suspend fun add(node: AccountId, candidate: SwarmCandidate, byHand: Boolean): SwarmCandidateChange {
            added += candidate
            return SwarmCandidateChange.CHANGED
        }
        override suspend fun remove(node: AccountId, candidate: SwarmCandidate) = SwarmCandidateChange.NOT_FOUND
        override suspend fun forget(node: AccountId) = SwarmCandidateChange.NOT_FOUND
    }

    private val inputs = FakeTransactionInputs()
    private val native = FakeBalances()
    private val tokens = FakeTokenBalances()
    private val sent = MemorySentTransfers()
    private val outbox = MemoryOutbox()
    private val signer = FakeSigner()
    private val broadcaster = FakeBroadcaster()

    /** The PostageStamp's allowance over the payer's xBZZ, as xBZZ's `allowance` answers it. */
    private var allowance = UInt256.ZERO

    init {
        listOf(node.address, payer.address).forEach {
            native.of(it).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
            tokens.of(it, bzz.address).value =
                TokenBalanceReading.Verified(TokenUnits.of("1" + "0".repeat(30)), 100)
        }
        inputs.runOf = { to, data ->
            when {
                to != bzz.address -> null
                hex(data).startsWith("dd62ed3e") -> Simulation.Succeeded(hex(allowance.toBytes32()), 100)
                else -> Simulation.Succeeded(hex(UInt256.of(1).toBytes32()), 100)
            }
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private val clock = TestTimeSource()

    private fun TestScope.viewModel(action: PostageAction, vararg accounts: Account): PostageViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val held = FakeAccounts(*accounts)
        val viewModel = PostageViewModel(
            node.id,
            action,
            id.takeUnless { action == PostageAction.BUY },
            ObserveAccount(held),
            ObserveAccounts(held),
            ObservePostageBatch(contracts),
            ObserveSwarmNodes(candidates),
            QuoteBatchPurchase(contracts, inputs, native, tokens, sent, outbox),
            QuoteBatchTopUp(contracts, inputs, native, tokens, sent, outbox),
            QuoteBatchDilution(contracts, inputs, native, tokens, sent, outbox),
            SendBatchApproval(signer, broadcaster, sent, inputs, contracts),
            SendBatchCall(signer, broadcaster, sent, inputs, candidates, contracts),
            DescribeKeyStore(FakeKeys()),
            awaitPoll = 5.seconds,
            awaitMax = 30.seconds,
            timeSource = clock,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    private val PostageViewModel.ready: PostageUiState.Ready
        get() = assertIs<PostageUiState.Ready>(uiState.value)

    private fun PostageViewModel.signWith(password: String) {
        onPasswordChange(password)
        sign()
    }

    @Test
    fun aPurchaseIsApprovedFirstThenReviewedOnceABlockHasTheApproval() = runTest {
        val viewModel = viewModel(PostageAction.BUY, node, payer)
        runCurrent()
        val form = assertIs<PostageStep.Compose>(viewModel.ready.step)
        // The node holds no key here: the account that may pay is chosen.
        assertEquals(listOf("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"), form.payers.map { it.address })
        assertEquals("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359", form.payer)
        assertEquals(20, form.depth)

        viewModel.review()
        runCurrent()
        val approval = assertIs<PostageStep.Review>(viewModel.ready.step)
        assertEquals(PostageTexts.APPROVAL_TITLE, approval.title)
        assertEquals(PostageTexts.position(1), approval.position)
        assertTrue(approval.warnings.isEmpty(), "the node's key does not sign")

        viewModel.signWith("pw")
        runCurrent()
        assertIs<PostageStep.Awaiting>(viewModel.ready.step)
        val approved = sent.transfers.value.single()
        val recorded = assertIs<SentAction.TokenApproval>(approved.action)

        // Mined, for exactly the total; the price rose meanwhile, and the batch is still what was approved.
        inputs.nonce = 8
        inputs.mined = 8
        allowance = recorded.amount
        price = UInt256.of(300_000)
        advanceTimeBy(6.seconds)
        runCurrent()
        val purchase = assertIs<PostageStep.Review>(viewModel.ready.step)
        assertEquals("Buy a postage batch", purchase.title)
        assertEquals(PostageTexts.position(2), purchase.position)
        // What was approved buys less time at the higher price: said, never more asked for.
        assertEquals("About 20 days at the current price", purchase.lines.toMap()["Time to live"])

        viewModel.signWith("pw")
        runCurrent()
        val bought = sent.transfers.value.last()
        assertIs<SentAction.BatchPurchase>(bought.action)
        assertEquals(bought.hash, viewModel.sent.value)
        assertIs<SwarmCandidate.Batch>(candidates.added.single())
    }

    @Test
    fun aDilutionWarnsOfTheNodesKeyAndOfAnImmutableBatch() = runTest {
        node = node.copy(custody = AccountCustody.InKeyStore)
        allowance = UInt256.of(Long.MAX_VALUE)
        val viewModel = viewModel(PostageAction.DILUTE, node)
        runCurrent()
        val form = assertIs<PostageStep.Compose>(viewModel.ready.step)
        assertTrue(form.payers.isEmpty())
        assertEquals(PostageTexts.NODE_SIGNS, form.signer)
        assertEquals(23, form.depth)
        assertNull(form.days)

        viewModel.review()
        runCurrent()
        val review = assertIs<PostageStep.Review>(viewModel.ready.step)
        assertEquals(listOf(PostageTexts.NODE_KEY_WARNING, PostageTexts.IMMUTABLE_WARNING), review.warnings)
        assertNull(review.position)
        assertEquals("About 2 days → About 26 hours", review.lines.toMap()["Time left"])
    }

    @Test
    fun aNonceTakenSinceTheQuoteSignsNothingAndQuotesAgainSayingWhy() = runTest {
        node = node.copy(custody = AccountCustody.InKeyStore)
        val viewModel = viewModel(PostageAction.DILUTE, node)
        runCurrent()
        viewModel.review()
        runCurrent()
        assertIs<PostageStep.Review>(viewModel.ready.step)
        // Bee, signing with the same key, mined a transaction meanwhile.
        inputs.nonce = 8
        inputs.mined = 8
        viewModel.signWith("pw")
        runCurrent()
        val again = assertIs<PostageStep.Review>(viewModel.ready.step)
        assertEquals(PostageTexts.NONCE_TAKEN, again.note)
        assertTrue(signer.asked.isEmpty())
        assertTrue(sent.transfers.value.isEmpty())
    }

    @Test
    fun aTopUpSaysWhyNothingWasQuoted() = runTest {
        val viewModel = viewModel(PostageAction.TOP_UP, node, payer)
        runCurrent()
        viewModel.onDays("")
        viewModel.review()
        assertEquals("Say for how many days.", assertIs<PostageStep.Compose>(viewModel.ready.step).error)

        batch = SwarmReading.Verified(
            BatchFinding.Node(PostageBatch(id, node.address, 22, 16, false, UInt256.ZERO, UInt256.of(207_062))),
            100,
        )
        viewModel.onDays("30")
        viewModel.review()
        runCurrent()
        assertEquals(PostageTexts.EXPIRED, assertIs<PostageStep.Compose>(viewModel.ready.step).error)
    }

    @Test
    fun nothingCanBeDoneWithoutAKeyHereToSignWith() = runTest {
        assertEquals(
            PostageUiState.Unavailable(PostageTexts.NO_PAYER),
            viewModel(PostageAction.BUY, node).also { runCurrent() }.uiState.value,
        )
        assertEquals(
            PostageUiState.Unavailable(PostageTexts.NODE_KEY_NOT_HELD),
            viewModel(PostageAction.DILUTE, node, payer).also { runCurrent() }.uiState.value,
        )
    }

    @Test
    fun backFromAReviewReturnsToTheFormAndFromTheFormLeaves() = runTest {
        val viewModel = viewModel(PostageAction.BUY, node, payer)
        runCurrent()
        viewModel.onDays("45")
        viewModel.review()
        runCurrent()
        assertIs<PostageStep.Review>(viewModel.ready.step)
        assertTrue(viewModel.back())
        assertEquals("45", assertIs<PostageStep.Compose>(viewModel.ready.step).days)
        assertEquals(false, viewModel.back())
    }

    @Test
    fun aRequoteAnsweringWhileHiddenNeverBringsThePasswordBack() = runTest {
        node = node.copy(custody = AccountCustody.InKeyStore)
        val viewModel = viewModel(PostageAction.DILUTE, node)
        runCurrent()
        viewModel.review()
        runCurrent()
        viewModel.onPasswordChange("secret")
        // Over a minute old at the tap: quoted again, and the answer waits.
        clock += 61.seconds
        val gate = CompletableDeferred<Unit>()
        inputs.gate = gate
        viewModel.sign()
        runCurrent()
        viewModel.onHidden()
        gate.complete(Unit)
        inputs.gate = null
        runCurrent()
        val review = assertIs<PostageStep.Review>(viewModel.ready.step)
        assertEquals("", review.unlock.password.text)
        assertTrue(signer.asked.isEmpty())
    }

    @Test
    fun aQuotesMinuteRunsFromItsFirstRead() = runTest {
        node = node.copy(custody = AccountCustody.InKeyStore)
        val viewModel = viewModel(PostageAction.DILUTE, node)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        inputs.gate = gate
        viewModel.review()
        runCurrent()
        // A slow peer: the reads took 50 seconds.
        clock += 50.seconds
        gate.complete(Unit)
        inputs.gate = null
        runCurrent()
        assertIs<PostageStep.Review>(viewModel.ready.step)
        clock += 15.seconds
        viewModel.signWith("pw")
        runCurrent()
        // 65 seconds since its first read: made again, not signed.
        assertEquals(STALE_QUOTE, assertIs<PostageStep.Review>(viewModel.ready.step).note)
        assertTrue(signer.asked.isEmpty())
    }

    @Test
    fun theAmountApprovedOutlivesTheWaitsTimeOut() = runTest {
        val viewModel = viewModel(PostageAction.BUY, node, payer)
        runCurrent()
        viewModel.review()
        runCurrent()
        viewModel.signWith("pw")
        runCurrent()
        val approval = assertIs<SentAction.TokenApproval>(sent.transfers.value.single().action)
        // No block has it within the wait.
        repeat(7) {
            clock += 5.seconds
            advanceTimeBy(5.seconds)
            runCurrent()
        }
        assertEquals(PostageTexts.AWAIT_TIMED_OUT, assertIs<PostageStep.Compose>(viewModel.ready.step).note)
        // Mined later, for exactly the total; the price rose meanwhile. Review again: the call, not a new approval.
        inputs.nonce = 8
        inputs.mined = 8
        allowance = approval.amount
        price = UInt256.of(300_000)
        viewModel.review()
        runCurrent()
        val purchase = assertIs<PostageStep.Review>(viewModel.ready.step)
        assertEquals("Buy a postage batch", purchase.title)
        assertEquals(PostageTexts.position(2), purchase.position)
    }

    @Test
    fun anotherSwarmNodeOfTheWalletPayingIsWarnedOfToo() = runTest {
        swarmNodes = mapOf(payer.id to SwarmCandidateList.Loaded(emptyList(), listOf(BatchId.of("cd".repeat(32)))))
        allowance = UInt256.of(Long.MAX_VALUE)
        val viewModel = viewModel(PostageAction.TOP_UP, node, payer)
        runCurrent()
        viewModel.review()
        runCurrent()
        val review = assertIs<PostageStep.Review>(viewModel.ready.step)
        assertEquals(listOf(PostageTexts.NODE_KEY_WARNING), review.warnings)
    }
}
