package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DelayTransfer
import eth.serotine.domain.model.DelayTransferPlan
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.DelayEntry
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class GnosisPayTransfersTest {

    private val safe = Address.of("0x188416e7d22a45e1c5f69c628d9c05bee54bd4fb")
    private val delay = Address.of("0xfc85bcbc2ce6aec1be9d94c77c872269ebddb6c7")
    private val roles = Address.of("0x5c7516e3e2581d1274d28e97cccc3eda87af796d")
    private val member = Address.of("0xa52bddbc4145f89b3740266a74f2d81c358677aa")
    private val recipient = Address.of("0x" + "11".repeat(20))
    private val eure = Token(Address.of("0xcb444e90d8198415266c6a2724b7900fb12fc56e"), "EURe", "Monerium EURe", 18, null)
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
            "468721a7" + "0".repeat(24) + "11".repeat(20) + "0000000000000000000000000000000000000000000000000de0b6b3a7640000" +
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

    private fun queueOf(vararg entries: DelayEntry, txNonce: Long = 5, expiration: Long = 111_600) =
        DelayQueue(txNonce, txNonce + entries.size, 90_000, expiration, entries.toList())

    private val other = Digest.of("ab".repeat(32))

    @Test
    fun aQueuedTransferStandsAsItsQueueSays() {
        val ours = transfer()
        val mine = DelayEntry(5, ours.hash, createdAt = 1_000)
        assertEquals(
            DelayTransferState.Waiting(readyAt = 91_000, expiresAt = 202_600, ahead = 0),
            queueOf(mine).stateOf(ours, headTime = 90_999),
        )
        // executeNextTx: block.timestamp - createdAt >= cooldown, and createdAt + cooldown + expiration >= it.
        assertEquals(DelayTransferState.Ready(202_600), queueOf(mine).stateOf(ours, headTime = 91_000))
        assertEquals(DelayTransferState.Ready(202_600), queueOf(mine).stateOf(ours, headTime = 202_600))
        assertEquals(DelayTransferState.Expired, queueOf(mine).stateOf(ours, headTime = 202_601))
        assertEquals(DelayTransferState.Ready(null), queueOf(mine, expiration = 0).stateOf(ours, 10_000_000))

        val behind = queueOf(DelayEntry(5, other, 900), mine.copy(nonce = 6))
        assertEquals(DelayTransferState.Behind(1, 202_600), behind.stateOf(ours, headTime = 95_000))
        assertEquals(DelayTransferState.Waiting(91_000, 202_600, 1), behind.stateOf(ours, headTime = 2_000))
        assertEquals(DelayTransferState.NotInQueue, queueOf(DelayEntry(5, other, 900)).stateOf(ours, 95_000))
    }

    private val inputs = FakeTransactionInputs()
    private val native = FakeNativeBalances()
    private val tokens = FakeTokenBalances()
    private val sent = MemorySentTransfers()
    private var queueAnswer: DelayQueueReading = DelayQueueReading.Unavailable(ReadUnavailable.UNPROVEN)
    private val delays = object : DelayModules {
        override fun observeDelay(network: Network, safe: Address, module: Address): Flow<DelayReading> = emptyFlow()
        override suspend fun queue(network: Network, delay: Address): DelayQueueReading = queueAnswer
    }

    private fun funded(safeWei: String = "2000000000000000000") {
        native.of(member).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
        native.of(safe).value = BalanceReading.Verified(Wei.of(safeWei), 100)
    }

    private fun queueing() = QuoteDelayQueue(inputs, native, tokens, sent, Network.GNOSIS, ListOutbox()) { 1_000 }

    @Test
    fun theQueueingIsTheMembersCallOfTheDelay() = runTest {
        funded()
        val quoting = queueing()(member, transfer(), FeeLevel.entries.first()) as DelayQuoting.Quoted
        val quote = quoting.quote

        assertEquals(SentAction.DelayQueue(transfer()), quote.action)
        assertEquals(delay, quote.call.transaction.to)
        assertTrue(quote.call.transaction.value.isZero)
        assertEquals(transfer().queueData().toHex(), quote.call.transaction.data.toHex())
        assertEquals(Network.GNOSIS.chainId, quote.call.transaction.chainId)
        // Run from the member, against the Delay.
        assertEquals(member to delay, inputs.simulated.single().let { it.first to it.second })
        assertEquals(UInt256.of("2000000000000000000"), quote.safeBalance)
    }

    @Test
    fun aTransferTheSafeCannotPayIsNeverQueued() = runTest {
        funded(safeWei = "999999999999999999")
        assertEquals(DelayQuoting.SafeInsufficient, queueing()(member, transfer(), FeeLevel.entries.first()))

        tokens.of(safe, eure.address).value = TokenBalanceReading.Verified(TokenUnits.of("1"), 100)
        assertEquals(
            DelayQuoting.SafeInsufficient,
            queueing()(member, transfer(TransferAsset.Erc20Token(eure)), FeeLevel.entries.first()),
        )
        assertTrue(inputs.simulated.isEmpty())
    }

    @Test
    fun aCallThatRevertsOrReturnsFalseIsNotQuoted() = runTest {
        funded()
        // Not a member: moduleOnly reverts.
        inputs.simulation = Simulation.Reverted(100)
        assertEquals(
            DelayQuoting.NotQuoted(CallRefusal.Reverts(100)),
            queueing()(member, transfer(), FeeLevel.entries.first()),
        )
        inputs.simulation = Simulation.Succeeded("0".repeat(64), 100)
        assertEquals(
            DelayQuoting.NotQuoted(CallRefusal.Reverts(100)),
            queueing()(member, transfer(), FeeLevel.entries.first()),
        )
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
        QuoteDelayExecution(delays, inputs, native, tokens, sent, Network.GNOSIS, ListOutbox()) { 1_000 }

    @Test
    fun theExecutionIsOfferedOnlyOnceItsQueueHasItNext() = runTest {
        funded()
        val ours = transfer()
        queueAnswer = DelayQueueReading.Read(queueOf(DelayEntry(5, other, 900), DelayEntry(6, ours.hash, 1_000)), 120, 95_000)
        assertEquals(
            DelayQuoting.NotReady(DelayTransferState.Behind(1, 202_600)),
            execution()(member, ours, FeeLevel.entries.first()),
        )
        assertTrue(inputs.simulated.isEmpty())

        queueAnswer = DelayQueueReading.Unavailable(ReadUnavailable.SYNCING)
        assertEquals(
            DelayQuoting.NotQuoted(CallRefusal.Unavailable(ReadUnavailable.SYNCING, QuoteFigure.RUN)),
            execution()(member, ours, FeeLevel.entries.first()),
        )

        queueAnswer = DelayQueueReading.Read(queueOf(DelayEntry(5, ours.hash, 1_000)), 120, 95_000)
        val quote = (execution()(member, ours, FeeLevel.entries.first()) as DelayQuoting.Quoted).quote
        assertEquals(SentAction.DelayExecution(ours), quote.action)
        assertEquals(ours.executeData().toHex(), quote.call.transaction.data.toHex())
        assertEquals(delay, quote.call.transaction.to)
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
        val history = MemoryTransactionHistory()
        val first = transfer()
        val second = transfer(TransferAsset.Erc20Token(eure))
        history.entries.value = listOf(
            HistoryEntry(sentBy(SentAction.DelayQueue(first), 10, 1), SentOutcome.NonceUsed),
            HistoryEntry(sentBy(SentAction.DelayExecution(first), 20, 2), SentOutcome.NotSeen),
            HistoryEntry(sentBy(SentAction.DelayQueue(second), 30, 3), SentOutcome.NotSeen),
        )
        queueAnswer = DelayQueueReading.Read(queueOf(DelayEntry(5, second.hash, 1_000)), 120, 2_000)

        val reading = ReadDelayTransfers(history, delays)(AccountId(Network.GNOSIS, safe), delay)
            as DelayTransfersReading.Read

        val listed = reading.transfers.single()
        assertEquals(second, listed.transfer)
        assertEquals(member, listed.member)
        assertEquals(DelayTransferState.Waiting(91_000, 202_600, 0), listed.state)

        // A queue that cannot be read lists them all the same, and says why.
        queueAnswer = DelayQueueReading.Unavailable(ReadUnavailable.UNPROVEN)
        val unread = ReadDelayTransfers(history, delays)(AccountId(Network.GNOSIS, safe), delay)
            as DelayTransfersReading.Read
        assertEquals(null, unread.transfers.single().state)
        assertEquals(ReadUnavailable.UNPROVEN, unread.queueUnread)

        history.readable.value = false
        assertEquals(
            DelayTransfersReading.HistoryUnreadable,
            ReadDelayTransfers(history, delays)(AccountId(Network.GNOSIS, safe), delay),
        )
    }
}
