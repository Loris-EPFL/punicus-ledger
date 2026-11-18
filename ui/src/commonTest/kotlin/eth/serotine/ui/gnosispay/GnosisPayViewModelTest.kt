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
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.usecase.DelayQuoting
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveGnosisPay
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.QuoteDelayExecution
import eth.serotine.domain.usecase.QuoteDelayQueue
import eth.serotine.domain.usecase.QuoteDelaySkip
import eth.serotine.domain.usecase.SendDelaySkip
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
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.shortAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

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

    /** Kinds that differ from the card Safe's own, by address: a member that is a Safe, its owners. */
    private val otherKinds = mutableMapOf<Address, KindReading>()
    private val sentinel = Address.of("0x" + "0".repeat(39) + "2")
    private val cardKind = KindReading.Verified(
        AddressKind.Safe(SafeState("1.3.0", false, listOf(sentinel), 1, 3, listOf(delay), null)),
        40,
    )

    /** The card Safe's own kind as it changes, where a test makes it change. */
    private var cardKinds: Flow<KindReading>? = null
    private val kinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address): Flow<KindReading> =
            cardKinds?.takeIf { address == safe } ?: flowOf(otherKinds[address] ?: cardKind)
    }

    private var queueAnswer: DelayQueueReading =
        DelayQueueReading.Read(DelayQueue(0, 0, 180, 1_800, null, 0), 41, 0)
    /** The queue's answer as the chain moves on, where a test makes it so; [queueAnswer] otherwise. */
    private var queueAnswerNow: (() -> DelayQueueReading)? = null

    private val delays = object : DelayModules {
        /** How many times the queue was read. */
        var queueReads = 0

        override fun observeDelay(network: Network, safe: Address, module: Address): Flow<DelayReading> = flowOf(
            DelayReading.Verified(
                DelayFinding.Wired(DelayModule(delay, DelayGeneration.CURRENT, 180, 1_800, 0, 0, listOf(member))),
                41,
            ),
        )

        override suspend fun queue(network: Network, safe: Address, delay: Address, hash: Digest): DelayQueueReading {
            queueReads++
            return queueAnswerNow?.invoke() ?: queueAnswer
        }
    }

    private val balances = FakeBalances()
    private val tokens = FakeTokenBalances()
    private val history = MemoryTransactionHistory()
    private val inputs = FakeTransactionInputs()
    private val sent = MemorySentTransfers()
    private val outbox = MemoryOutbox()
    private val signer = FakeSigner()
    private val time = TestTimeSource()


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
            ReadDelayTransfers(history, delays) { 1_200 },
            QuoteDelayQueue(delays, history, inputs, balances, tokens, sent, Network.GNOSIS, outbox),
            QuoteDelayExecution(delays, history, inputs, balances, tokens, sent, Network.GNOSIS, outbox),
            SendDelayCall(signer, FakeBroadcaster(), sent),
            QuoteDelaySkip(delays, history, inputs, balances, sent, Network.GNOSIS, outbox),
            SendDelaySkip(signer, FakeBroadcaster(), sent),
            DescribeKeyStore(FakeKeys()),
            timeSource = time,
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
        assertTrue(state.waits.contains("3 min") && state.waits.contains("30 min"), state.waits)
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
    fun theOwnerOfAMemberSafeSendsThroughItAndIsListedAmongTheKeys() = runTest {
        // The Delay's member is a Safe of threshold 1 whose owner's key is held here (owner's request, 2026-11-17).
        val key = Address.of("0x" + "91".repeat(20))
        val passkey = Address.of("0x" + "fd".repeat(20))
        otherKinds[member] = KindReading.Verified(
            AddressKind.Safe(SafeState("1.4.1", true, listOf(passkey, key), 1, 80, emptyList(), null)),
            40,
        )
        otherKinds[passkey] = KindReading.Verified(AddressKind.Contract, 40)
        val keyHeld = Account(Network.GNOSIS, key, AccountKind.EOA, "Signer", AccountCustody.InKeyStore)
        accounts.accounts.value = listOf(card, keyHeld)
        balances.of(key).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 41)
        val viewModel = viewModel()

        val state = viewModel.ready()
        assertEquals(ActionItem(enabled = true, reason = null), state.send)
        assertEquals("A Safe: 1 of its 2 owners signs", state.members.single().what)
        assertTrue(state.members.single().heldHere)
        val listed = state.keys.single()
        assertEquals("Signer", listed.label)
        assertTrue(listed.heldHere && listed.alone)
        val short = shortAddress(Eip55.format(member))
        assertEquals("Through Safe $short", listed.through)
        assertNotNull(state.controlNote, "the passkey is said not to be followed")

        viewModel.startSend()
        assertEquals("Signer, through Safe $short", (viewModel.ready().step as GnosisPayStep.Compose).sender)
        viewModel.onAssetChange(null)
        viewModel.onToChange(recipient.hex)
        viewModel.onAmountChange("1")
        viewModel.review()
        val review = viewModel.ready().step as GnosisPayStep.Review
        assertEquals(Eip55.format(key), review.lines.toMap()["Signed by"])
        assertTrue(review.lines.toMap().getValue("Through Safe").startsWith(Eip55.format(member)))

        viewModel.onPasswordChange("correct horse")
        viewModel.sign()
        viewModel.sent.first { it != null }
        val recorded = (sent.observe().first() as SentTransferList.Loaded).transfers.single()
        assertEquals(key, recorded.account)
        assertTrue(recorded.action is SentAction.DelayQueue)
    }

    @Test
    fun aTransferQueuedHereIsExecutableByItsKeyWhateverTheControlTreeSays() = runTest {
        // The member Safe's owners cannot be read now: no one controls the account from here, as far as
        // is verified, yet the key that queued the transfer may still execute it (anyone may).
        val key = Address.of("0x" + "91".repeat(20))
        otherKinds[member] = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        val keyHeld = Account(Network.GNOSIS, key, AccountKind.EOA, "Signer", AccountCustody.InKeyStore)
        accounts.accounts.value = listOf(card, keyHeld)
        val transfer = (
            DelayTransfer.of(safe, delay, listOf(delay), TransferAsset.Native, recipient, UInt256.of(7))
                as DelayTransferPlan.Planned
            ).transfer
        val queueing = SentTransfer(
            Network.GNOSIS,
            key,
            3,
            SignedTransfer(byteArrayOf(1, 2, 3)),
            SentAction.DelayQueue(transfer),
            UInt256.ZERO,
            100,
        )
        history.entries.value = listOf(HistoryEntry(queueing, SentOutcome.NonceUsed))
        queueAnswer = DelayQueueReading.Read(
            DelayQueue(0, 1, 180, 1_800, DelayEntry(0, transfer.hash, 1_000), 1),
            41,
            1_200,
        )

        val state = viewModel().uiState.first { (it as? GnosisPayUiState.Ready)?.queued?.isNotEmpty() == true }
            as GnosisPayUiState.Ready
        assertEquals(false, state.send.enabled)
        assertEquals("Not verified yet", state.members.single().what)
        assertEquals(ActionItem(true, null), state.queued.single().execute)
    }

    @Test
    fun nothingIsReviewedOnAnAccountThatCouldNotBeVerifiedAtTheLatestBlock() = runTest {
        val kind = MutableStateFlow<KindReading>(cardKind)
        cardKinds = kind
        val viewModel = viewModel()
        viewModel.ready()
        // No peer served the Safe's proof at this head: what was verified before stays on screen, under
        // the Safe's block, the older of the two, not the Delay's.
        kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertTrue(viewModel.ready().send.enabled)
        assertEquals(GnosisPayTexts.checkedAt(40, held = true), viewModel.ready().checkedAt)
        viewModel.startSend()
        viewModel.onAssetChange(null)
        viewModel.onToChange(recipient.hex)
        viewModel.onAmountChange("1.5")
        viewModel.review()

        // Its modules, which no transfer may go to, could have changed since: said, and nothing quoted.
        assertEquals(GnosisPayTexts.NOT_VERIFIED_NOW, (viewModel.ready().step as GnosisPayStep.Compose).error)

        kind.value = cardKind.copy(blockNumber = 44)
        viewModel.review()
        assertTrue(viewModel.ready().step is GnosisPayStep.Review)
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
            DelayQueue(0, 1, 180, 1_800, DelayEntry(0, transfer.hash, 1_000), 1),
            41,
            1_200,
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
    fun aQueuedTransferBehindAnExpiredEntryOffersItsSkipAndItsReviewSaysItMovesNothing() = runTest {
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
        // Entry 0 was queued elsewhere at 0 and expired (180 + 1800 s) by the head at 5000; ours is entry 1.
        queueAnswer = DelayQueueReading.Read(
            DelayQueue(0, 2, 180, 1_800, DelayEntry(1, transfer.hash, 4_000), 2, headCreatedAt = 0),
            41,
            5_000,
        )
        val viewModel = viewModel()
        val row = viewModel.uiState.first { (it as? GnosisPayUiState.Ready)?.queued?.isNotEmpty() == true }
            .let { (it as GnosisPayUiState.Ready).queued.single() }

        assertEquals(GnosisPayTexts.BLOCKED_BY_EXPIRED, row.status)
        assertEquals(ActionItem(false, GnosisPayTexts.BLOCKED_BY_EXPIRED), row.execute)
        assertEquals(ActionItem(enabled = true, reason = null), row.skip)

        viewModel.skipExpired()
        val review = viewModel.uiState.first { (it as? GnosisPayUiState.Ready)?.step is GnosisPayStep.Review }
        val step = (review as GnosisPayUiState.Ready).step as GnosisPayStep.Review
        assertEquals(GnosisPayTexts.SKIP_TITLE, step.title)
        assertTrue(step.lines.any { (_, value) -> value.contains("It moves nothing") }, step.lines.toString())
    }

    @Test
    fun aTransferWaitingOutItsCooldownDrainsABarAndCountsDown() = runTest {
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
        // Queued at 1000 with a cooldown of 180 s: ready at 1180. Read at a head of 1060: a third is gone.
        // The device's clock plays no part: the countdown runs on the head's time plus the time since.
        // The chain's head moves on with the test's time; its block past the cooldown is slow to come
        // (held at 1179 here), as on a light client a few seconds behind.
        val start = time.markNow()
        queueAnswerNow = {
            DelayQueueReading.Read(
                DelayQueue(0, 1, 180, 1_800, DelayEntry(0, transfer.hash, 1_000), 1),
                41,
                minOf(1_060 + start.elapsedNow().inWholeSeconds, 1_179),
            )
        }
        val viewModel = viewModel()
        val row = viewModel.uiState.first { (it as? GnosisPayUiState.Ready)?.queued?.isNotEmpty() == true }
            .let { (it as GnosisPayUiState.Ready).queued.single() }

        assertEquals("Executable in 2:00", row.status)
        assertEquals(120f / 180f, row.cooldownLeft)

        // A second later the bar has drained a little more.
        time += 1.seconds
        testScheduler.advanceTimeBy(1_001)
        val later = (viewModel.uiState.value as GnosisPayUiState.Ready).queued.single()
        assertEquals("Executable in 1:59", later.status)
        assertEquals(119f / 180f, later.cooldownLeft)

        // Past its end, while the head read still says Waiting: never 0:00 for good, and the queue is
        // read again every few seconds, a bounded number of times, until a head past its end answers.
        val readsBefore = delays.queueReads
        // The head reaches 1179, a second short, at 120 s; a few seconds on, re-reads find it again.
        repeat(125) {
            time += 1.seconds
            testScheduler.advanceTimeBy(1_000)
        }
        val due = (viewModel.uiState.value as GnosisPayUiState.Ready).queued.single()
        // Counted from the first read of the last head, never run back by a read of the same head again.
        assertEquals(GnosisPayTexts.EXECUTABLE_SHORTLY, due.status)
        assertEquals(0f, due.cooldownLeft)
        repeat(60) {
            time += 1.seconds
            testScheduler.advanceTimeBy(1_000)
        }
        val reads = delays.queueReads - readsBefore
        assertTrue(reads in 10..20, "$reads reads again")
    }

    private suspend fun GnosisPayViewModel.reviewTransfer(to: String = recipient.hex): GnosisPayStep.Review {
        ready()
        startSend()
        onAssetChange(null)
        onToChange(to)
        onAmountChange("1.5")
        review()
        return ready().step as GnosisPayStep.Review
    }

    @Test
    fun theQueueingIsQuotedAtTheNormalFeeLevel() = runTest {
        val review = viewModel().reviewTransfer()

        assertEquals(FeeLevel.NORMAL, review.fee)
        assertTrue(review.lines.any { (label, value) -> label == "Fee" && "normal" in value }, review.lines.toString())
    }

    @Test
    fun aMistypedChecksumIsRefusedOnTheForm() = runTest {
        val viewModel = viewModel()
        viewModel.ready()
        viewModel.startSend()
        // EIP-55's own example, one letter's case flipped.
        viewModel.onToChange("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD")
        viewModel.onAmountChange("1")
        viewModel.review()

        assertEquals(GnosisPayTexts.BAD_CHECKSUM, (viewModel.ready().step as GnosisPayStep.Compose).error)
    }

    @Test
    fun aQuoteOverAMinuteOldIsMadeAgainBeforeAnythingIsSigned() = runTest {
        val viewModel = viewModel()
        viewModel.reviewTransfer()
        viewModel.onPasswordChange("correct horse")
        time += 61.seconds

        viewModel.sign()

        val review = viewModel.ready().step as GnosisPayStep.Review
        assertEquals(emptyList(), signer.passwords)
        assertNotNull(review.note)
        assertEquals(false, review.signing)

        // Fresh now: the next tap signs.
        viewModel.sign()
        assertEquals(listOf("correct horse"), signer.passwords)
    }

    @Test
    fun aHiddenScreenDropsThePasswordAndHasItsQuoteMadeAgainWhenShown() = runTest {
        val viewModel = viewModel()
        viewModel.reviewTransfer()
        viewModel.onPasswordChange("correct horse")

        viewModel.onHidden()
        assertEquals("", (viewModel.ready().step as GnosisPayStep.Review).unlock.password.text)

        viewModel.onPasswordChange("correct horse")
        viewModel.onShown()
        viewModel.sign()
        // Quoted again on the way back, so this tap signs the fresh quote.
        assertEquals(listOf("correct horse"), signer.passwords)
    }

    @Test
    fun aQuoteThatLandsWhileTheScreenIsHiddenIsMadeAgainWhenShown() = runTest {
        val viewModel = viewModel()
        viewModel.ready()
        viewModel.startSend()
        viewModel.onAssetChange(null)
        viewModel.onToChange(recipient.hex)
        viewModel.onAmountChange("1.5")
        // Hidden before the review exists: the quote lands as made while hidden.
        viewModel.onHidden()
        viewModel.review()
        viewModel.onPasswordChange("correct horse")
        viewModel.sign()
        assertEquals(emptyList(), signer.passwords, "a quote made while hidden is never signed as it was")

        viewModel.onShown()
        viewModel.onPasswordChange("correct horse")
        viewModel.sign()
        assertEquals(listOf("correct horse"), signer.passwords)
    }

    @Test
    fun aWrongPasswordSaysSoAndStaysOnTheReview() = runTest {
        signer.answer = Signing.WrongPassword
        val viewModel = viewModel()
        viewModel.reviewTransfer()
        viewModel.onPasswordChange("wrong")
        viewModel.sign()

        val review = viewModel.ready().step as GnosisPayStep.Review
        assertNotNull(review.unlock.error)
        assertEquals(false, review.signing)
        assertNull(viewModel.sent.value)
    }

    @Test
    fun anExecutionTheDelayNoLongerAllowsSaysWhyOnTheOverview() = runTest {
        queuedTransfer()
        val viewModel = viewModel()
        val row = viewModel.uiState.first { (it as? GnosisPayUiState.Ready)?.queued?.isNotEmpty() == true }
            .let { (it as GnosisPayUiState.Ready).queued.single() }
        queueAnswer = DelayQueueReading.NotTheSafes(DelayGeneration.CURRENT, 42)

        viewModel.execute(row.id)

        val overview = viewModel.uiState
            .first { ((it as? GnosisPayUiState.Ready)?.step as? GnosisPayStep.Overview)?.error != null }
        val error = ((overview as GnosisPayUiState.Ready).step as GnosisPayStep.Overview).error
        assertEquals(GnosisPayTexts.quoteRefused(DelayQuoting.NotTheSafesDelay(DelayGeneration.CURRENT)), error)
    }

    @Test
    fun anExecuteWhoseStandingCannotBeVerifiedIsGreyedOutNotHidden() = runTest {
        queuedTransfer()
        queueAnswer = DelayQueueReading.Unavailable(ReadUnavailable.UNPROVEN)
        val viewModel = viewModel()
        val row = viewModel.uiState.first { (it as? GnosisPayUiState.Ready)?.queued?.isNotEmpty() == true }
            .let { (it as GnosisPayUiState.Ready).queued.single() }

        assertEquals(ActionItem(false, GnosisPayTexts.EXECUTE_UNVERIFIED), row.execute)
    }

    @Test
    fun anExecutionOnItsWayGreysExecuteOutSayingSo() = runTest {
        val transfer = queuedTransfer()
        val executing = SentTransfer(
            Network.GNOSIS,
            member,
            4,
            SignedTransfer(byteArrayOf(4, 5, 6)),
            SentAction.DelayExecution(transfer),
            UInt256.ZERO,
            1_190,
        )
        history.entries.value += HistoryEntry(executing, SentOutcome.NotSeen)
        val viewModel = viewModel()
        val row = viewModel.uiState.first { (it as? GnosisPayUiState.Ready)?.queued?.isNotEmpty() == true }
            .let { (it as GnosisPayUiState.Ready).queued.single() }

        assertEquals(ActionItem(false, GnosisPayTexts.EXECUTING), row.execute)
        assertEquals(GnosisPayTexts.EXECUTING_STATUS, row.status)
    }

    /** One transfer queued from here 200 s ago, at the head's time of 1,200, ready to execute. */
    private fun queuedTransfer(): DelayTransfer {
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
            DelayQueue(0, 1, 180, 1_800, DelayEntry(0, transfer.hash, 1_000), 1),
            41,
            1_200,
        )
        return transfer
    }

    @Test
    fun aPeriodIsSaidInHoursAndMinutesRoundedUp() {
        // What Gnosis Pay accounts carry, and the outlier the tests' real account has.
        assertEquals("3 min", GnosisPayTexts.period(180))
        assertEquals("30 min", GnosisPayTexts.period(1_800))
        assertEquals("25 h", GnosisPayTexts.period(90_000))
        assertEquals("3 min", GnosisPayTexts.period(180))
        assertEquals("1 h", GnosisPayTexts.period(3_599))
        assertEquals("1 h 30 min", GnosisPayTexts.period(5_400))
    }
}
