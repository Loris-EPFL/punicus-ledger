package eth.serotine.ui.withdraw

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Chequebook
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SwarmDeployment
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BatchFinding
import eth.serotine.domain.repository.ChequebookFinding
import eth.serotine.domain.repository.ChequebookReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.PostageReading
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SwarmContracts
import eth.serotine.domain.repository.SwarmReading
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveChequebook
import eth.serotine.domain.usecase.QuoteChequebookWithdrawal
import eth.serotine.domain.usecase.SendChequebookWithdrawal
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeBalances
import eth.serotine.ui.FakeBroadcaster
import eth.serotine.ui.FakeKeys
import eth.serotine.ui.FakeSigner
import eth.serotine.ui.FakeTransactionInputs
import eth.serotine.ui.MemoryOutbox
import eth.serotine.ui.MemorySentTransfers
import eth.serotine.ui.components.STALE_QUOTE
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** The withdrawal screen's state machine, over the withdrawal use cases with fake ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class WithdrawViewModelTest {

    private val bzz =
        Token(Address.of("0xdbf3ea6f5bee45c02255b2c26a16f300502f68da"), "BZZ", "BZZ on xDai", 16, icon = "bzz")
    private val chequebook = Address.of("0x614f90324e7ac11a08c29f3313de9f4c7e047b77")
    private var node = Account(
        Network.GNOSIS,
        Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"),
        AccountKind.EOA,
        "Bee node",
        AccountCustody.InKeyStore,
    )

    /** 1.2 BZZ free of 1.5. */
    private var liquid = UInt256.of(12_000_000_000_000_000)

    private fun reading(): SwarmReading<ChequebookFinding> = SwarmReading.Verified(
        ChequebookFinding.Node(
            Chequebook(chequebook, node.address, UInt256.of(15_000_000_000_000_000), liquid, UInt256.ZERO, false),
        ),
        100,
    )

    /** Whether the light client is still syncing, as the polled reading says. */
    private var syncing = false

    /** While set, a fresh read of the chequebook waits for it. */
    private var gate: CompletableDeferred<Unit>? = null

    /** Whether a fresh read of the chequebook finds no proof. */
    private var unreadable = false

    private val contracts = object : SwarmContracts {
        override fun observeChequebook(network: Network, node: Address, chequebook: Address) =
            flowOf(if (syncing) SwarmReading.Syncing else reading())
        override suspend fun chequebook(network: Network, node: Address, chequebook: Address): ChequebookReading {
            gate?.await()
            return if (unreadable) {
                ChequebookReading.Unavailable(ReadUnavailable.SYNCING)
            } else {
                ChequebookReading.Read((reading() as SwarmReading.Verified).finding, 100)
            }
        }
        override fun observeBatch(network: Network, node: Address, id: BatchId): Flow<SwarmReading<BatchFinding>> =
            flowOf(SwarmReading.NotPinned)
        override fun deployment(network: Network) = SwarmDeployment(bzz, Address.of("0x" + "45".repeat(20)))
        override suspend fun postage(network: Network, node: Address?, id: BatchId?) = PostageReading.NotPinned
    }

    private val inputs = FakeTransactionInputs()
    private val native = FakeBalances()
    private val sent = MemorySentTransfers()
    private val signer = FakeSigner()
    private val clock = TestTimeSource()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): WithdrawViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        native.of(node.address).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
        val accounts = FakeAccounts(node)
        val viewModel = WithdrawViewModel(
            node.id,
            chequebook,
            ObserveAccount(accounts),
            ObserveChequebook(contracts),
            QuoteChequebookWithdrawal(contracts, inputs, native, sent, MemoryOutbox()),
            SendChequebookWithdrawal(signer, FakeBroadcaster(), sent, inputs, contracts),
            DescribeKeyStore(FakeKeys()),
            timeSource = clock,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    private val WithdrawViewModel.ready: WithdrawUiState.Ready
        get() = assertIs<WithdrawUiState.Ready>(uiState.value)

    @Test
    fun aWithdrawalIsReviewedWithItsWarningsThenSent() = runTest {
        val viewModel = viewModel()
        runCurrent()
        val form = assertIs<WithdrawStep.Compose>(viewModel.ready.step)
        assertEquals("1.2", form.all)
        viewModel.onAmount("1")
        viewModel.review()
        runCurrent()
        val review = assertIs<WithdrawStep.Review>(viewModel.ready.step)
        assertEquals(listOf(WithdrawTexts.CHEQUES_WARNING, WithdrawTexts.NODE_KEY_WARNING), review.warnings)
        assertEquals("1 BZZ", review.lines.toMap()["Withdraws"])

        viewModel.onPasswordChange("pw")
        viewModel.sign()
        runCurrent()
        val withdrawn = assertIs<SentAction.ChequebookWithdrawal>(sent.transfers.value.single().action)
        assertEquals(UInt256.of(10_000_000_000_000_000), withdrawn.amount)
        assertEquals(sent.transfers.value.single().hash, viewModel.sent.value)
    }

    @Test
    fun moreThanIsFreeIsRefusedSayingHowMuchIs() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.onAmount("2")
        viewModel.review()
        runCurrent()
        assertEquals(
            "At most 1.2 BZZ is free to withdraw now.",
            assertIs<WithdrawStep.Compose>(viewModel.ready.step).error,
        )
        viewModel.onAmount("1.00000000000000001")
        viewModel.review()
        assertEquals(
            "An amount in BZZ, with at most 16 decimals.",
            assertIs<WithdrawStep.Compose>(viewModel.ready.step).error,
        )
    }

    @Test
    fun aCashOutSinceTheQuoteSignsNothingAndQuotesAgain() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.onAmount("1")
        viewModel.review()
        runCurrent()
        liquid = UInt256.of(1)
        viewModel.onPasswordChange("pw")
        viewModel.sign()
        runCurrent()
        // Quoted again on what is free now: refused, saying how much is.
        assertEquals(
            "At most 0.0000000000000001 BZZ is free to withdraw now.",
            assertIs<WithdrawStep.Compose>(viewModel.ready.step).error,
        )
        assertTrue(signer.asked.isEmpty())
    }

    @Test
    fun aStaleQuoteAnsweringWhileHiddenNeverBringsThePasswordBack() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.onAmount("1")
        viewModel.review()
        runCurrent()
        viewModel.onPasswordChange("secret")
        clock += 61.seconds
        val gate = CompletableDeferred<Unit>()
        inputs.gate = gate
        viewModel.sign()
        runCurrent()
        viewModel.onHidden()
        gate.complete(Unit)
        inputs.gate = null
        runCurrent()
        val review = assertIs<WithdrawStep.Review>(viewModel.ready.step)
        assertEquals("", review.unlock.password.text)
        assertTrue(signer.asked.isEmpty())
        // Shown again: quoted once more, still saying why.
        viewModel.onShown()
        runCurrent()
        assertEquals(STALE_QUOTE, assertIs<WithdrawStep.Review>(viewModel.ready.step).note)
    }

    @Test
    fun aWatchedNodeCannotWithdraw() = runTest {
        node = node.copy(custody = AccountCustody.Watched)
        val viewModel = viewModel()
        runCurrent()
        assertEquals(WithdrawUiState.Unavailable(WithdrawTexts.NODE_KEY_NOT_HELD), viewModel.uiState.value)
    }

    @Test
    fun whyASendFailedWhileHiddenIsSaidOnceShownAgain() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.onAmount("1")
        viewModel.review()
        runCurrent()
        viewModel.onPasswordChange("pw")
        // The check before signing waits, the screen goes away, and the check finds no proof.
        val wait = CompletableDeferred<Unit>()
        gate = wait
        unreadable = true
        viewModel.sign()
        runCurrent()
        viewModel.onHidden()
        wait.complete(Unit)
        gate = null
        runCurrent()
        unreadable = false
        viewModel.onShown()
        runCurrent()
        val review = assertIs<WithdrawStep.Review>(viewModel.ready.step)
        assertEquals(WithdrawTexts.notChecked(ReadUnavailable.SYNCING), review.note)
        assertTrue(signer.asked.isEmpty())
    }

    @Test
    fun syncingIsSaidAsSuchNotAsNotReadYet() = runTest {
        syncing = true
        val viewModel = viewModel()
        runCurrent()
        assertEquals(listOf(eth.serotine.ui.swarm.SwarmTexts.SYNCING), viewModel.ready.lines)
    }
}
