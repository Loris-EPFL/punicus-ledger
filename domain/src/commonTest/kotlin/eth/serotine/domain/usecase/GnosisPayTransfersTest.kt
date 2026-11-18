package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DelayTransfer
import eth.serotine.domain.model.DelayTransferPlan
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.model.toHex
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
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.Unlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class GnosisPayTransfersTest {

    private val safe = Address.of("0x188416e7d22a45e1c5f69c628d9c05bee54bd4fb")
    private val delay = Address.of("0xfc85bcbc2ce6aec1be9d94c77c872269ebddb6c7")
    private val roles = Address.of("0x5c7516e3e2581d1274d28e97cccc3eda87af796d")
    private val member = Address.of("0xa52bddbc4145f89b3740266a74f2d81c358677aa")
    private val recipient = Address.of("0x" + "11".repeat(20))
    private val eure =
        Token(Address.of("0xcb444e90d8198415266c6a2724b7900fb12fc56e"), "EURe", "Monerium EURe", 18, null)
    private val oneUnit = UInt256.of("1000000000000000000")

    private fun plan(
        asset: TransferAsset = TransferAsset.Native,
        to: Address = recipient,
        amount: UInt256 = oneUnit,
    ) = DelayTransfer.of(safe, delay, listOf(roles, delay), asset, to, amount)

    private fun transfer(asset: TransferAsset = TransferAsset.Native) =
        (plan(asset) as DelayTransferPlan.Planned).transfer

    @Test
    fun theDelaysHashAndCallsAreEncodedAsItsOwn() {
        // keccak256(abi.encodePacked(to, value, data, uint8(0))) and the calldata, worked out apart.
        val native = transfer()
        assertEquals(Digest.of("2bfd7a0ce48c1c80dab186e019242eadd45da2b28d554cb3c92252a5e6dd9a90"), native.hash)
        assertEquals(
            "468721a7" + "0".repeat(24) + "11".repeat(20) +
                "0000000000000000000000000000000000000000000000000de0b6b3a7640000" +
                "0".repeat(62) + "80" + "0".repeat(64) + "0".repeat(64),
            native.queueData().toHex(),
        )
        val token = transfer(TransferAsset.Erc20Token(eure))
        assertEquals(Digest.of("de8e0ae869a1f79040c2f8c500d5cd28dd3e9652a86dcac302276084712d6aec"), token.hash)
        assertEquals(eure.address, token.to)
        assertTrue(token.value.isZero)
        assertEquals(
            "ee072baf" + "0".repeat(24) + "cb444e90d8198415266c6a2724b7900fb12fc56e" + "0".repeat(64) +
                "0".repeat(62) + "80" + "0".repeat(64) + "0".repeat(62) + "44" +
                "a9059cbb" + "0".repeat(24) + "11".repeat(20) +
                "0000000000000000000000000000000000000000000000000de0b6b3a7640000" + "0".repeat(56),
            token.executeData().toHex(),
        )
    }

    @Test
    fun onlyAPlainTransferIsPlanned() {
        assertEquals(DelayTransferPlan.ToZeroAddress, plan(to = Address.ZERO))
        // The account's own contracts: the Safe, its Delay, any other module.
        assertEquals(DelayTransferPlan.ToTheAccount, plan(to = safe))
        assertEquals(DelayTransferPlan.ToTheAccount, plan(to = delay))
        assertEquals(DelayTransferPlan.ToTheAccount, plan(to = roles))
        // A "token" that is one of them would be a call of it.
        val notAToken = Token(roles, "R", "Roles", 18, null)
        assertEquals(DelayTransferPlan.ToTheAccount, plan(TransferAsset.Erc20Token(notAToken)))
        assertEquals(DelayTransferPlan.ToTokenContract, plan(TransferAsset.Erc20Token(eure), to = eure.address))
        assertEquals(DelayTransferPlan.NothingToSend, plan(amount = UInt256.ZERO))
    }

    /** A queue from entry 5 on whose entry [found] (null: none among [searched]) was searched for. */
    private fun queueOf(
        found: DelayEntry?,
        queued: Int = 1,
        searched: Int = found?.let { (it.nonce - 5 + 1).toInt() } ?: queued,
        expiration: Long = 111_600,
    ) = DelayQueue(5, 5L + queued, 90_000, expiration, found, searched)

    @Test
    fun aQueuedTransferStandsAsItsQueueSays() {
        val ours = transfer()
        val mine = DelayEntry(5, ours.hash, createdAt = 1_000)
        assertEquals(
            DelayTransferState.Waiting(readyAt = 91_000, expiresAt = 202_600, ahead = 0),
            queueOf(mine).state(headTime = 90_999),
        )
        // executeNextTx: block.timestamp - createdAt >= cooldown, and createdAt + cooldown + expiration >= it.
        assertEquals(DelayTransferState.Ready(202_600), queueOf(mine).state(headTime = 91_000))
        // Too close to its end to be mined in time: not offered.
        assertEquals(DelayTransferState.Ready(202_600), queueOf(mine).state(202_600 - DELAY_EXPIRY_MARGIN_SECONDS))
        assertEquals(DelayTransferState.Expired(202_600), queueOf(mine).state(202_601 - DELAY_EXPIRY_MARGIN_SECONDS))
        assertEquals(DelayTransferState.Ready(null), queueOf(mine, expiration = 0).state(10_000_000))

        val behind = queueOf(mine.copy(nonce = 6), queued = 2)
        assertEquals(DelayTransferState.Behind(1, 202_600), behind.state(headTime = 95_000))
        assertEquals(DelayTransferState.Waiting(91_000, 202_600, 1), behind.state(headTime = 2_000))
        assertEquals(DelayTransferState.NotInQueue, queueOf(null, queued = 1).state(95_000))
        // A queue longer than was searched: not "gone", only not found as far as read.
        assertEquals(DelayTransferState.FurtherBack(8), queueOf(null, queued = 20, searched = 8).state(95_000))
        assertEquals(DelayTransferState.NotInQueue, queueOf(null, queued = 0).state(95_000))
    }

    private val inputs = FakeTransactionInputs()
    private val native = FakeNativeBalances()
    private val tokens = FakeTokenBalances()
    private val sent = MemorySentTransfers()
    /** An empty queue from entry 5, read at a head produced at 1000: what a queueing is checked against. */
    private var queueAnswer: DelayQueueReading =
        DelayQueueReading.Read(DelayQueue(5, 5, 90_000, 111_600, null, 0), 120, 1_000)

    /** The queue's answer for a hash, where it differs from [queueAnswer]. */
    private val answers = mutableMapOf<Digest, DelayQueueReading>()
    private val asked = mutableListOf<Digest>()
    private val delays = object : DelayModules {
        // The screen's kept reading: no quote leans on it.
        override fun observeDelay(network: Network, safe: Address, module: Address): Flow<DelayReading> =
            error("a quote reads the queue fresh, never the screen's reading")

        override suspend fun queue(network: Network, safe: Address, delay: Address, hash: Digest): DelayQueueReading {
            assertEquals(this@GnosisPayTransfersTest.safe, safe, "the Safe is applied")
            asked += hash
            return answers[hash] ?: queueAnswer
        }
    }
    private val history = MemoryTransactionHistory()

    private fun funded(safeWei: String = "2000000000000000000") {
        native.of(member).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
        native.of(safe).value = BalanceReading.Verified(Wei.of(safeWei), 100)
    }

    private fun queueing() =
        QuoteDelayQueue(delays, history, inputs, native, tokens, sent, Network.GNOSIS, ListOutbox()) { 1_000 }

    @Test
    fun theQueueingIsTheMembersCallOfTheDelay() = runTest {
        funded()
        val quoting = queueing()(member, transfer(), FeeLevel.entries.first()) as DelayQuoting.Quoted
        val quote = quoting.quote

        assertEquals(SentAction.DelayQueue(transfer()), quote.action)
        assertEquals(DelayStep.QUEUE, quote.step)
        assertEquals(delay, quote.call.transaction.to)
        assertTrue(quote.call.transaction.value.isZero)
        assertEquals(transfer().queueData().toHex(), quote.call.transaction.data.toHex())
        assertEquals(Network.GNOSIS.chainId, quote.call.transaction.chainId)
        // The Safe's own transfer first, as it would be executed; then the member's call of the Delay.
        assertEquals(
            listOf(safe to recipient, member to delay),
            inputs.simulated.map { it.first to it.second },
        )
        assertEquals(UInt256.of("2000000000000000000"), quote.safeBalance)
        assertEquals(emptyList(), quote.via)
    }

    @Test
    fun theOwnerOfAMemberSafeQueuesThroughThatSafeAndTheRunMustAcceptIt() = runTest {
        // The member is a Safe of threshold 1; its owner's key is held here (owner's request, 2026-11-17).
        val owner = Address.of("0x" + "91".repeat(20))
        native.of(owner).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
        native.of(safe).value = BalanceReading.Verified(Wei.of("2000000000000000000"), 100)

        val quote = (queueing()(owner, transfer(), FeeLevel.NORMAL, via = listOf(member)) as DelayQuoting.Quoted).quote

        assertEquals(owner, quote.call.from)
        assertEquals(member, quote.call.transaction.to)
        assertEquals(transfer().queueCall(owner, listOf(member)).second.toHex(), quote.call.transaction.data.toHex())
        assertEquals(listOf(member), quote.via)
        assertEquals(SentAction.DelayQueue(transfer()), quote.action)
        assertEquals(listOf(safe to recipient, owner to member), inputs.simulated.map { it.first to it.second })

        // A Safe's execTransaction answering false is a refusal, as the Delay's own would be.
        inputs.simulated.clear()
        inputs.simulationOf = { _, to -> Simulation.Succeeded(if (to == member) "0".repeat(64) else "", 100) }
        assertEquals(
            DelayQuoting.NotQuoted(CallRefusal.Reverts(100)),
            queueing()(owner, transfer(), FeeLevel.NORMAL, via = listOf(member)),
        )
    }

    @Test
    fun nothingIsSentThroughADelayThatIsNotTheSafesOwnCurrentOne() = runTest {
        funded()
        queueAnswer = DelayQueueReading.NotTheSafes(DelayGeneration.RETIRED, 120)
        assertEquals(
            DelayQuoting.NotTheSafesDelay(DelayGeneration.RETIRED),
            queueing()(member, transfer(), FeeLevel.entries.first()),
        )
        queueAnswer = DelayQueueReading.NotTheSafes(DelayGeneration.CURRENT, 120)
        assertEquals(
            DelayQuoting.NotTheSafesDelay(DelayGeneration.CURRENT),
            execution()(member, transfer(), FeeLevel.entries.first()),
        )
        assertTrue(inputs.simulated.isEmpty())
    }

    @Test
    fun aQueueThatCannotBeReadWhileSyncingSaysSoAtOnce() = runTest {
        funded()
        queueAnswer = DelayQueueReading.Unavailable(ReadUnavailable.SYNCING)
        assertEquals(
            DelayQuoting.NotQuoted(CallRefusal.Syncing),
            queueing()(member, transfer(), FeeLevel.entries.first()),
        )
    }

    @Test
    fun theSameCallIsNotQueuedTwiceWhileItsEntryWaits() = runTest {
        funded()
        val ours = transfer()
        // The queue holds an entry of the call, whoever queued it.
        answers[ours.hash] =
            DelayQueueReading.Read(DelayQueue(5, 6, 90_000, 111_600, DelayEntry(5, ours.hash, 900), 1), 120, 1_000)
        assertEquals(DelayQuoting.AlreadyQueued(null), queueing()(member, ours, FeeLevel.entries.first()))

        // Its queueing from here, not mined yet: the queue cannot hold it yet.
        answers.clear()
        val onItsWay = sentBy(SentAction.DelayQueue(ours), 900, 1)
        history.entries.value = listOf(HistoryEntry(onItsWay, SentOutcome.NotSeen))
        assertEquals(DelayQuoting.AlreadyQueued(onItsWay.hash), queueing()(member, ours, FeeLevel.entries.first()))

        // Mined, and no longer in the queue (executed by anyone, or skipped): it may be queued again,
        // whether or not this device saw the execution's receipt.
        history.entries.value = listOf(HistoryEntry(onItsWay, SentOutcome.NonceUsed))
        assertTrue(queueing()(member, ours, FeeLevel.entries.first()) is DelayQuoting.Quoted)
    }

    @Test
    fun anExpiredEntryOfTheSameCallOrAQueueDeeperThanReadIsSaidForWhatItIs() = runTest {
        funded()
        val ours = transfer()
        // Expired, never skipped: it stays in the queue, and the same hash cannot be queued beside it.
        answers[ours.hash] = DelayQueueReading.Read(
            DelayQueue(5, 6, 90_000, 111_600, DelayEntry(5, ours.hash, 900), 1),
            120,
            900 + 90_000 + 111_600 + 1,
        )
        assertEquals(DelayQuoting.SameCallExpired(), queueing()(member, ours, FeeLevel.entries.first()))
        // Within the margin of its end: too late to execute, too early to skip.
        answers[ours.hash] = DelayQueueReading.Read(
            DelayQueue(5, 6, 90_000, 111_600, DelayEntry(5, ours.hash, 900), 1),
            120,
            900 + 90_000 + 111_600 - 10,
        )
        assertEquals(
            DelayQuoting.SameCallExpired(skippable = false),
            queueing()(member, ours, FeeLevel.entries.first()),
        )
        // Behind another entry and expired itself: the one ahead, queued earlier, expired first, and a
        // skip removes both. In its own last minutes, behind an expired one: not yet skippable itself.
        answers[ours.hash] = DelayQueueReading.Read(
            DelayQueue(5, 7, 90_000, 111_600, DelayEntry(6, ours.hash, 900), 2, headCreatedAt = 800),
            120,
            900 + 90_000 + 111_600 + 1,
        )
        assertEquals(DelayQuoting.SameCallExpired(), queueing()(member, ours, FeeLevel.entries.first()))
        answers[ours.hash] = DelayQueueReading.Read(
            DelayQueue(5, 7, 90_000, 111_600, DelayEntry(6, ours.hash, 900), 2, headCreatedAt = 800),
            120,
            900 + 90_000 + 111_600 - 10,
        )
        assertEquals(
            DelayQuoting.SameCallExpired(skippable = false),
            queueing()(member, ours, FeeLevel.entries.first()),
        )

        // More entries than are read: whether one is this call cannot be told.
        answers[ours.hash] = DelayQueueReading.Read(DelayQueue(5, 25, 90_000, 111_600, null, 8), 120, 1_000)
        assertEquals(DelayQuoting.QueueTooLong(8), queueing()(member, ours, FeeLevel.entries.first()))
    }

    @Test
    fun theSameCallIsTheSameWhateverTheTokensDisplayMetadata() = runTest {
        funded()
        tokens.of(safe, eure.address).value = TokenBalanceReading.Verified(TokenUnits.of("5000000000000000000"), 100)
        // The history's token as the store reads it back: no price.
        val stored = transfer(TransferAsset.Erc20Token(eure))
        val priced = transfer(TransferAsset.Erc20Token(eure.copy(priceId = PriceId("EUR"), icon = "eure")))
        assertTrue(priced.sameCall(stored))
        history.entries.value = listOf(HistoryEntry(sentBy(SentAction.DelayQueue(stored), 900, 1), SentOutcome.NotSeen))

        assertTrue(queueing()(member, priced, FeeLevel.entries.first()) is DelayQuoting.AlreadyQueued)
    }

    @Test
    fun whatIsQueuedOfTheAssetAlreadyCountsAgainstTheBalance() = runTest {
        funded(safeWei = "1500000000000000000")
        // 1 xDAI to another recipient, queued from here and waiting.
        val other = (plan(to = Address.of("0x" + "22".repeat(20))) as DelayTransferPlan.Planned).transfer
        history.entries.value =
            listOf(HistoryEntry(sentBy(SentAction.DelayQueue(other), 900, 1), SentOutcome.NonceUsed))

        assertEquals(
            DelayQuoting.SafeInsufficient(alreadyQueued = oneUnit),
            queueing()(member, transfer(), FeeLevel.entries.first()),
        )
    }

    @Test
    fun aTokenThatAnswersFalseToTheSafesTransferIsRefused() = runTest {
        funded()
        tokens.of(safe, eure.address).value = TokenBalanceReading.Verified(TokenUnits.of("5000000000000000000"), 100)
        inputs.simulation = Simulation.Succeeded("0".repeat(64), 100)

        assertEquals(
            DelayQuoting.TokenRefuses,
            queueing()(member, transfer(TransferAsset.Erc20Token(eure)), FeeLevel.entries.first()),
        )
        // Run as the Safe's own call of the token.
        assertEquals(safe to eure.address, inputs.simulated.single().let { it.first to it.second })
    }

    @Test
    fun aHistoryThatCannotBeReadIsSaidAsSuch() = runTest {
        funded()
        history.readable.value = false
        assertEquals(DelayQuoting.HistoryUnreadable, queueing()(member, transfer(), FeeLevel.entries.first()))
    }

    @Test
    fun aTransferTheSafeCannotPayIsNeverQueued() = runTest {
        funded(safeWei = "999999999999999999")
        assertEquals(DelayQuoting.SafeInsufficient(), queueing()(member, transfer(), FeeLevel.entries.first()))

        tokens.of(safe, eure.address).value = TokenBalanceReading.Verified(TokenUnits.of("1"), 100)
        assertEquals(
            DelayQuoting.SafeInsufficient(),
            queueing()(member, transfer(TransferAsset.Erc20Token(eure)), FeeLevel.entries.first()),
        )
        assertTrue(inputs.simulated.isEmpty())
    }

    @Test
    fun aCallThatRevertsOrReturnsFalseIsNotQuoted() = runTest {
        funded()
        // The Safe's own transfer runs; the member's call is what answers.
        var memberRun: Simulation = Simulation.Reverted(100)
        inputs.simulationOf = { from, _ -> if (from == safe) Simulation.Succeeded("", 100) else memberRun }
        // Not a member: moduleOnly reverts.
        assertEquals(
            DelayQuoting.NotQuoted(CallRefusal.Reverts(100)),
            queueing()(member, transfer(), FeeLevel.entries.first()),
        )
        memberRun = Simulation.Succeeded("0".repeat(64), 100)
        assertEquals(
            DelayQuoting.NotQuoted(CallRefusal.Reverts(100)),
            queueing()(member, transfer(), FeeLevel.entries.first()),
        )
    }

    @Test
    fun xdaiTheRecipientDoesNotTakeIsNeverQueued() = runTest {
        funded()
        // The Safe's own transfer of xDAI reverts: a contract without a payable receive.
        inputs.simulationOf = { from, _ ->
            if (from == safe) Simulation.Reverted(100) else Simulation.Succeeded("", 100)
        }

        assertEquals(DelayQuoting.SafeCallReverts(100), queueing()(member, transfer(), FeeLevel.entries.first()))
        assertEquals(safe to recipient, inputs.simulated.single().let { it.first to it.second })
    }

    @Test
    fun anExpiredHeadATooShortWindowOrAFullQueueQueuesNothing() = runTest {
        funded()
        val ours = transfer()
        // Another entry at the head, expired: nothing behind it can be executed until it is skipped.
        answers[ours.hash] = DelayQueueReading.Read(
            DelayQueue(5, 6, 90_000, 111_600, null, 1, headCreatedAt = 0),
            120,
            90_000 + 111_600 + 1,
        )
        assertEquals(DelayQuoting.HeadExpired, queueing()(member, ours, FeeLevel.entries.first()))

        // An expiration within the margin: no execution could be mined in time.
        answers[ours.hash] = DelayQueueReading.Read(DelayQueue(5, 5, 180, 300, null, 0), 120, 1_000)
        assertEquals(DelayQuoting.WindowTooShort(300), queueing()(member, ours, FeeLevel.entries.first()))

        // Eight waiting, as deep as the queue is read: a ninth could not be found again.
        answers[ours.hash] = DelayQueueReading.Read(DelayQueue(5, 13, 90_000, 111_600, null, 8, 900), 120, 1_000)
        assertEquals(DelayQuoting.QueueTooLong(8), queueing()(member, ours, FeeLevel.entries.first()))
    }

    @Test
    fun aTransferStillCoolingDownIsWaitingWhateverItsWindow() {
        val hash = transfer().hash
        // A 5-minute expiration, within the margin: still cooling down, it waits; it never reads as expired.
        val queue = DelayQueue(5, 6, 180, 300, DelayEntry(5, hash, 1_000), 1)
        assertEquals(DelayTransferState.Waiting(1_180, 1_480, 0), queue.state(1_100))
    }

    @Test
    fun aBalanceNotVerifiedNowQuotesNothing() = runTest {
        native.of(member).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
        native.of(safe).value = BalanceReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(
            DelayQuoting.NotQuoted(CallRefusal.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.BALANCE)),
            queueing()(member, transfer(), FeeLevel.entries.first()),
        )
    }

    private fun execution() =
        QuoteDelayExecution(delays, history, inputs, native, tokens, sent, Network.GNOSIS, ListOutbox()) { 1_000 }

    /** The queue holding [hash] at entry [nonce], read at a head produced at 95000. */
    private fun answering(nonce: Long, queued: Int = (nonce - 4).toInt()) {
        val ours = transfer()
        queueAnswer = DelayQueueReading.Read(queueOf(DelayEntry(nonce, ours.hash, 1_000), queued = queued), 120, 95_000)
    }

    @Test
    fun theExecutionIsOfferedOnlyOnceItsQueueHasItNext() = runTest {
        funded()
        val ours = transfer()
        answering(nonce = 6)
        assertEquals(
            DelayQuoting.NotReady(DelayTransferState.Behind(1, 202_600)),
            execution()(member, ours, FeeLevel.entries.first()),
        )
        assertTrue(inputs.simulated.isEmpty())

        queueAnswer = DelayQueueReading.Unavailable(ReadUnavailable.SYNCING)
        assertEquals(
            DelayQuoting.NotQuoted(CallRefusal.Syncing),
            execution()(member, ours, FeeLevel.entries.first()),
        )

        answering(nonce = 5)
        val quote = (execution()(member, ours, FeeLevel.entries.first()) as DelayQuoting.Quoted).quote
        assertEquals(SentAction.DelayExecution(ours), quote.action)
        // The queue is searched for this transfer's own hash.
        assertEquals(ours.hash, asked.last())
        assertEquals(ours.executeData().toHex(), quote.call.transaction.data.toHex())
        assertEquals(delay, quote.call.transaction.to)
    }

    @Test
    fun anExecutionFromHereOnItsWayIsNotSentAgain() = runTest {
        funded()
        val ours = transfer()
        answering(nonce = 5)
        // Sent and not mined yet: the queue still has the entry next, and a second would revert.
        val executing = sentBy(SentAction.DelayExecution(ours), 990, 9)
        history.entries.value = listOf(HistoryEntry(executing, SentOutcome.NotSeen))

        assertEquals(
            DelayQuoting.ExecutionOnItsWay(executing.hash),
            execution()(member, ours, FeeLevel.entries.first()),
        )
        assertTrue(inputs.simulated.isEmpty())
    }

    @Test
    fun whatIsSentIsRecordedAsTheDelayCallItIs() = runTest {
        funded()
        val quote = (queueing()(member, transfer(), FeeLevel.entries.first()) as DelayQuoting.Quoted).quote
        val signer = FakeSigner(FakeRecovery())

        val sending = SendDelayCall(signer, FakeBroadcaster(), sent) { 2_000 }(quote, Unlock.WithBiometrics("test"))

        assertTrue(sending is TransferSending.Sent)
        val recorded = (sent.observe().first() as SentTransferList.Loaded).transfers.single()
        assertEquals(SentAction.DelayQueue(transfer()), recorded.action)
        assertEquals(member, recorded.account)
        assertEquals(member, signer.asked.single().first)
    }

    private fun skipping() =
        QuoteDelaySkip(delays, history, inputs, native, sent, Network.GNOSIS, ListOutbox()) { 1_000 }

    /** A queue whose next entry, 5 of 7, was queued at [createdAt]; read at a head produced at [headTime]. */
    private fun headQueuedAt(createdAt: Long, headTime: Long = 300_000) {
        queueAnswer = DelayQueueReading.Read(
            DelayQueue(5, 7, 90_000, 111_600, null, 0, headCreatedAt = createdAt),
            120,
            headTime,
        )
    }

    @Test
    fun anExpiredHeadIsSkippedByAnyHeldKeyWithTheDelaysOwnCall() = runTest {
        funded()
        // Queued at 1000: ready at 91,000, expired after 202,600.
        headQueuedAt(createdAt = 1_000)

        val quote = (skipping()(member, safe, delay, FeeLevel.NORMAL) as DelaySkipQuoting.Quoted).quote

        assertEquals(delay, quote.call.transaction.to)
        assertTrue(quote.call.transaction.value.isZero)
        // cast sig "skipExpired()"
        assertEquals("b19d4758", quote.call.transaction.data.toHex())
        // Room to skip both waiting entries, should they expire before it is mined.
        assertTrue(quote.call.transaction.gasLimit >= 2 * 5_000, quote.call.transaction.gasLimit.toString())
        assertEquals(SentAction.DelaySkip(safe, delay), quote.action)
        assertEquals(2, quote.waiting)
        assertEquals(1_000, quote.headCreatedAt)
        // Only the sender's own call is run: the skip moves nothing of the Safe's.
        assertEquals(listOf(member to delay), inputs.simulated.map { it.first to it.second })
    }

    @Test
    fun nothingIsSkippedWhileTheHeadHasNotExpired() = runTest {
        funded()
        headQueuedAt(createdAt = 1_000, headTime = 202_600)
        assertEquals(DelaySkipQuoting.NothingExpired, skipping()(member, safe, delay, FeeLevel.NORMAL))
        // An empty queue has nothing to skip either.
        queueAnswer = DelayQueueReading.Read(DelayQueue(5, 5, 90_000, 111_600, null, 0), 120, 300_000)
        assertEquals(DelaySkipQuoting.NothingExpired, skipping()(member, safe, delay, FeeLevel.NORMAL))
        assertTrue(inputs.simulated.isEmpty())
    }

    @Test
    fun aDelayNotTheSafesOwnOrASkipOnItsWayIsNeverSentTo() = runTest {
        funded()
        queueAnswer = DelayQueueReading.NotTheSafes(DelayGeneration.RETIRED, 120)
        assertEquals(
            DelaySkipQuoting.NotTheSafesDelay(DelayGeneration.RETIRED),
            skipping()(member, safe, delay, FeeLevel.NORMAL),
        )

        headQueuedAt(createdAt = 1_000)
        val skipping = sentBy(SentAction.DelaySkip(safe, delay), 990, 7)
        history.entries.value = listOf(HistoryEntry(skipping, SentOutcome.NotSeen))
        assertEquals(DelaySkipQuoting.SkipOnItsWay(skipping.hash), skipping()(member, safe, delay, FeeLevel.NORMAL))
        assertTrue(inputs.simulated.isEmpty())
    }

    @Test
    fun aSkipIsRecordedAsTheSkipItIs() = runTest {
        funded()
        headQueuedAt(createdAt = 1_000)
        val quote = (skipping()(member, safe, delay, FeeLevel.NORMAL) as DelaySkipQuoting.Quoted).quote

        val sending = SendDelaySkip(FakeSigner(FakeRecovery()), FakeBroadcaster(), sent) { 2_000 }(
            quote,
            Unlock.WithBiometrics("test"),
        )

        assertTrue(sending is TransferSending.Sent)
        val recorded = (sent.observe().first() as SentTransferList.Loaded).transfers.single()
        assertEquals(SentAction.DelaySkip(safe, delay), recorded.action)
    }

    private fun sentBy(action: SentAction, at: Long, hash: Int) = SentTransfer(
        Network.GNOSIS,
        member,
        nonce = at,
        signed = SignedTransfer(byteArrayOf(hash.toByte())),
        action = action,
        maxCost = UInt256.ZERO,
        sentAt = at,
    )

    @Test
    fun theTransfersQueuedHereAndNotExecutedAreListedWithWhereTheyStand() = runTest {
        val first = transfer()
        val second = transfer(TransferAsset.Erc20Token(eure))
        history.entries.value = listOf(
            HistoryEntry(sentBy(SentAction.DelayQueue(first), 10, 1), SentOutcome.NonceUsed),
            HistoryEntry(sentBy(SentAction.DelayExecution(first), 20, 2), SentOutcome.Finalized(130, true)),
            HistoryEntry(sentBy(SentAction.DelayQueue(second), 30, 3), SentOutcome.NotSeen),
        )
        answers[second.hash] = DelayQueueReading.Read(queueOf(DelayEntry(5, second.hash, 1_000)), 120, 2_000)
        // An execution that failed leaves its transfer listed while the queue holds it.
        val third = (plan(amount = UInt256.of(3)) as DelayTransferPlan.Planned).transfer
        // Mined, its execution's receipt missed, and gone from the queue: no longer listed.
        val fourth = (plan(amount = UInt256.of(4)) as DelayTransferPlan.Planned).transfer
        history.entries.value += listOf(
            HistoryEntry(sentBy(SentAction.DelayQueue(third), 40, 4), SentOutcome.NonceUsed),
            HistoryEntry(sentBy(SentAction.DelayExecution(third), 50, 5), SentOutcome.Included(140, false)),
            HistoryEntry(sentBy(SentAction.DelayQueue(fourth), 60, 6), SentOutcome.NonceUsed),
            HistoryEntry(sentBy(SentAction.DelayExecution(fourth), 70, 7), SentOutcome.NonceUsed),
        )
        answers[third.hash] = DelayQueueReading.Read(queueOf(DelayEntry(5, third.hash, 1_000)), 120, 2_000)
        queueAnswer = DelayQueueReading.Read(queueOf(null, queued = 1), 120, 2_000)

        val reading = ReadDelayTransfers(history, delays) { 1_000 }(AccountId(Network.GNOSIS, safe), delay)
            as DelayTransfersReading.Read

        assertEquals(listOf(second, third), reading.transfers.map { it.transfer })
        val listed = reading.transfers.first()
        assertEquals(second, listed.transfer)
        assertEquals(member, listed.member)
        assertEquals(DelayTransferState.Waiting(91_000, 202_600, 0), listed.state)

        // A queue that cannot be read lists them all the same, and says why.
        answers.clear()
        queueAnswer = DelayQueueReading.Unavailable(ReadUnavailable.UNPROVEN)
        val unread = ReadDelayTransfers(history, delays) { 1_000 }(AccountId(Network.GNOSIS, safe), delay)
            as DelayTransfersReading.Read
        assertEquals(listOf(null, null, null), unread.transfers.map { it.state })
        assertEquals(ReadUnavailable.UNPROVEN, unread.queueUnread)

        history.readable.value = false
        assertEquals(
            DelayTransfersReading.HistoryUnreadable,
            ReadDelayTransfers(history, delays) { 1_000 }(AccountId(Network.GNOSIS, safe), delay),
        )
    }

    @Test
    fun aQueueingOlderThanItsEntryCanWaitAndADayIsNoLongerReadNorListed() = runTest {
        val old = transfer()
        val recent = transfer(TransferAsset.Erc20Token(eure))
        // 90,000 s of cooldown, 111,600 s of expiration and a day: 288,000 s before now, nothing waits.
        val now = 1_000_000L
        history.entries.value = listOf(
            HistoryEntry(sentBy(SentAction.DelayQueue(old), now - 288_001, 1), SentOutcome.NonceUsed),
            HistoryEntry(sentBy(SentAction.DelayQueue(recent), now - 100, 2), SentOutcome.NonceUsed),
            // The same call queued twice: its queue is read once.
            HistoryEntry(sentBy(SentAction.DelayQueue(recent), now - 50, 3), SentOutcome.NonceUsed),
        )
        answers[old.hash] = DelayQueueReading.Read(queueOf(DelayEntry(5, old.hash, 1_000)), 120, now)
        answers[recent.hash] =
            DelayQueueReading.Read(queueOf(DelayEntry(6, recent.hash, now - 90), queued = 2), 120, now)
        asked.clear()

        val reading = ReadDelayTransfers(history, delays) { now }(AccountId(Network.GNOSIS, safe), delay)
            as DelayTransfersReading.Read

        // One row per call: the newest queueing of it, whose entry the queue holds.
        assertEquals(listOf(recent), reading.transfers.map { it.transfer })
        assertEquals(now - 50, reading.transfers.single().queuedAt)
        assertEquals(listOf(recent.hash), asked)
    }

    @Test
    fun aQueuedTransferIsMarkedWhileTheDelaysNextEntryHasExpired() = runTest {
        val ours = transfer()
        history.entries.value =
            listOf(HistoryEntry(sentBy(SentAction.DelayQueue(ours), 10, 1), SentOutcome.NonceUsed))
        // Entry 5, queued at 1000: 90,000 s of cooldown and 111,600 s of expiration, so expired after 202,600.
        suspend fun read(entry: DelayEntry?, headTime: Long, expiration: Long = 111_600): QueuedDelayTransfer? {
            answers[ours.hash] = DelayQueueReading.Read(
                DelayQueue(5, 7, 90_000, expiration, entry, (entry?.nonce ?: 5).toInt() - 4, headCreatedAt = 1_000),
                120,
                headTime,
            )
            val reading = ReadDelayTransfers(history, delays) { 10 }(AccountId(Network.GNOSIS, safe), delay)
            return (reading as DelayTransfersReading.Read).transfers.singleOrNull()
        }
        val behind = DelayEntry(6, ours.hash, 2_000)

        // Behind the expired head, and the head itself expired: both marked; one moment before, neither.
        assertEquals(true, read(behind, headTime = 202_601)?.expiredHead)
        assertEquals(true, read(DelayEntry(5, ours.hash, 1_000), headTime = 202_601)?.expiredHead)
        assertEquals(false, read(behind, headTime = 202_600)?.expiredHead)
        // A Delay whose entries never expire has nothing to skip.
        assertEquals(false, read(behind, headTime = 10_000_000, expiration = 0)?.expiredHead)
    }
}
