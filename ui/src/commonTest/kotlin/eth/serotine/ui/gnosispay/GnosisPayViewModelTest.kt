package eth.serotine.ui.gnosispay

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DelayTransfer
import eth.serotine.domain.model.DelayTransferPlan
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.DelayEntry
import eth.serotine.domain.repository.DelayFinding
import eth.serotine.domain.repository.DelayGeneration
import eth.serotine.domain.repository.DelayModule
import eth.serotine.domain.repository.DelayModules
import eth.serotine.domain.repository.DelayQueue
import eth.serotine.domain.repository.DelayQueueReading
import eth.serotine.domain.repository.DelayReading
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveGnosisPay
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.QuoteDelayExecution
import eth.serotine.domain.usecase.QuoteDelayQueue
import eth.serotine.domain.usecase.ReadDelayTransfers
import eth.serotine.domain.usecase.SendDelayCall
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeBalances
import eth.serotine.ui.FakeBroadcaster
import eth.serotine.ui.FakeKeys
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.FakeSigner
import eth.serotine.ui.FakeTokenBalances
import eth.serotine.ui.FakeTransactionInputs
import eth.serotine.ui.ListCatalog
import eth.serotine.ui.MemoryOutbox
import eth.serotine.ui.MemorySentTransfers
import eth.serotine.ui.MemoryTransactionHistory
import eth.serotine.ui.MemoryUserTokens
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class GnosisPayViewModelTest {

    private val safe = Address.of("0x188416e7d22a45e1c5f69c628d9c05bee54bd4fb")
    private val delay = Address.of("0xfc85bcbc2ce6aec1be9d94c77c872269ebddb6c7")
    private val member = Address.of("0xa52bddbc4145f89b3740266a74f2d81c358677aa")
    private val recipient = Address.of("0x" + "11".repeat(20))
    private val id = AccountId(Network.GNOSIS, safe)

    private val card = Account(Network.GNOSIS, safe, AccountKind.SAFE, "Card", AccountCustody.Watched)
    private val owner = Account(Network.GNOSIS, member, AccountKind.EOA, "Card owner", AccountCustody.InKeyStore)
    private val accounts = FakeAccounts(card, owner)

    private val kinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address): Flow<KindReading> = flowOf(
            KindReading.Verified(
                AddressKind.Safe(
                    SafeState("1.3.0", false, listOf(sentinel), 1, 3, listOf(delay), null),
                ),
                40,
            ),
        )
    }

    private val sentinel = Address.of("0x" + "0".repeat(39) + "2")
    private var queueAnswer: DelayQueueReading =
        DelayQueueReading.Read(DelayQueue(0, 0, 90_000, 111_600, null, 0), 41, 0)
    private val delays = object : DelayModules {
        override fun observeDelay(network: Network, safe: Address, module: Address): Flow<DelayReading> = flowOf(
            DelayReading.Verified(
                DelayFinding.Wired(DelayModule(delay, DelayGeneration.CURRENT, 90_000, 111_600, 0, 0, listOf(member))),
                41,
            ),
        )

        override suspend fun queue(network: Network, delay: Address, hash: Digest): DelayQueueReading = queueAnswer
    }

    private val balances = FakeBalances()
    private val tokens = FakeTokenBalances()
    private val history = MemoryTransactionHistory()
    private val inputs = FakeTransactionInputs()
    private val sent = MemorySentTransfers()
    private val outbox = MemoryOutbox()
    private val signer = FakeSigner()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): GnosisPayViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        balances.of(safe).value = BalanceReading.Verified(Wei.of("5000000000000000000"), 41)
        balances.of(member).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 41)
        val viewModel = GnosisPayViewModel(
            id,
            ObserveAccount(accounts),
            ObserveGnosisPay(kinds, delays, accounts),
            ObserveNativeBalance(balances),
            ObserveTokenHoldings(ObserveKnownTokens(ListCatalog(), MemoryUserTokens(), FakeMetadata()), tokens),
            ReadDelayTransfers(history, delays),
            QuoteDelayQueue(delays, history, inputs, balances, tokens, sent, Network.GNOSIS, outbox),
            QuoteDelayExecution(delays, inputs, balances, tokens, sent, Network.GNOSIS, outbox),
            SendDelayCall(signer, FakeBroadcaster(), sent),
            DescribeKeyStore(FakeKeys()),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    private suspend fun GnosisPayViewModel.ready() =
        uiState.first { it is GnosisPayUiState.Ready } as GnosisPayUiState.Ready

    @Test
    fun theOverviewSaysWhatTheDelayMakesATransferDoAndOffersSend() = runTest {
        val state = viewModel().ready()

        assertEquals(GnosisPayStep.Overview(), state.step)
        assertTrue(state.waits.contains("25 h") && state.waits.contains("31 h"), state.waits)
        assertEquals(ActionItem(enabled = true, reason = null), state.send)
        assertEquals("Card owner", state.members.single().label)
        assertTrue(state.members.single().heldHere)
    }

    @Test
    fun aWatchedMemberGreysSendOutAndSaysWhy() = runTest {
        accounts.accounts.value = listOf(card, owner.copy(custody = AccountCustody.Watched))
        val state = viewModel().ready()

        assertEquals(false, state.send.enabled)
        assertNotNull(state.send.reason)
    }

    @Test
    fun aTransferIsReviewedSignedAndRecordedAsItsQueueing() = runTest {
        val viewModel = viewModel()
        viewModel.ready()
        viewModel.startSend()
        viewModel.onAssetChange(null)
        viewModel.onToChange(recipient.hex)
        viewModel.onAmountChange("1.5")
        viewModel.review()

        val review = (viewModel.ready().step as GnosisPayStep.Review)
        assertTrue(review.title.startsWith("Queue 1.5 XDAI"), review.title)

        viewModel.onPasswordChange("correct horse")
        viewModel.sign()

        val hash = viewModel.sent.first { it != null }
        val recorded = (sent.observe().first() as SentTransferList.Loaded).transfers.single()
        assertEquals(hash, recorded.hash)
        assertTrue(recorded.action is SentAction.DelayQueue)
        assertEquals(member, recorded.account)
        assertEquals(listOf("correct horse"), signer.passwords)
        assertEquals(GnosisPayStep.Overview(), viewModel.ready().step)
    }

    @Test
    fun aRecipientThatIsNoAddressIsSaidOnTheForm() = runTest {
        val viewModel = viewModel()
        viewModel.ready()
        viewModel.startSend()
        viewModel.onToChange("card.eth")
        viewModel.onAmountChange("1")
        viewModel.review()

        val compose = viewModel.ready().step as GnosisPayStep.Compose
        assertEquals(GnosisPayTexts.BAD_ADDRESS, compose.error)
    }

    @Test
    fun aQueuedTransferReadyToExecuteOffersExecute() = runTest {
        val transfer = (
            DelayTransfer.of(safe, delay, listOf(delay), TransferAsset.Native, recipient, UInt256.of(7))
                as DelayTransferPlan.Planned
            ).transfer
        val queueing = SentTransfer(
            Network.GNOSIS,
            member,
            3,
            SignedTransfer(byteArrayOf(1, 2, 3)),
            SentAction.DelayQueue(transfer),
            UInt256.ZERO,
            100,
        )
        history.entries.value = listOf(HistoryEntry(queueing, SentOutcome.NonceUsed))
        queueAnswer = DelayQueueReading.Read(
            DelayQueue(0, 1, 90_000, 111_600, DelayEntry(0, transfer.hash, 1_000), 1),
            41,
            95_000,
        )
        val viewModel = viewModel()
        val row = viewModel.uiState.first { (it as? GnosisPayUiState.Ready)?.queued?.isNotEmpty() == true }
            .let { (it as GnosisPayUiState.Ready).queued.single() }

        assertEquals(ActionItem(enabled = true, reason = null), row.execute)

        viewModel.execute(row.id)
        val review = viewModel.uiState.first { (it as? GnosisPayUiState.Ready)?.step is GnosisPayStep.Review }
        assertTrue(((review as GnosisPayUiState.Ready).step as GnosisPayStep.Review).title.startsWith("Execute"))
    }

    @Test
    fun aPeriodIsSaidInHoursAndMinutesRoundedUp() {
        assertEquals("25 h", GnosisPayTexts.period(90_000))
        assertEquals("31 h", GnosisPayTexts.period(111_600))
        assertEquals("3 min", GnosisPayTexts.period(180))
        assertEquals("1 h", GnosisPayTexts.period(3_599))
        assertEquals("1 h 30 min", GnosisPayTexts.period(5_400))
    }
}
