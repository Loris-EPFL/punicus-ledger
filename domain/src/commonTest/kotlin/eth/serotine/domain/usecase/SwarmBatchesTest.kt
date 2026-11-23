package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PostageBatch
import eth.serotine.domain.model.PostageState
import eth.serotine.domain.model.SwarmCalls
import eth.serotine.domain.model.SwarmDeployment
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BatchFinding
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.PostageReading
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.SwarmCandidate
import eth.serotine.domain.repository.SwarmCandidateChange
import eth.serotine.domain.repository.SwarmCandidateList
import eth.serotine.domain.repository.SwarmCandidates
import eth.serotine.domain.repository.SwarmContracts
import eth.serotine.domain.repository.SwarmReading
import eth.serotine.domain.repository.Unlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/** Buying, topping up and diluting a Swarm node's postage batches, over fake ports. */
class SwarmBatchesTest {

    private val bzz =
        Token(Address.of("0xdbf3ea6f5bee45c02255b2c26a16f300502f68da"), "BZZ", "BZZ on xDai", 16, icon = "bzz")
    private val stamp = Address.of("0x45a1502382541cd610cc9068e88727426b696293")
    private val node = Address.of("0x" + "0b".repeat(20))
    private val payer = Address.of("0x" + "a1".repeat(20))
    private val stranger = Address.of("0x" + "5e".repeat(20))
    private val id = BatchId.of("ab".repeat(32))
    private val nonce = ByteArray(32) { 7 }

    private val minimum = UInt256.of(3_578_031_360)
    private var postage: PostageReading = PostageReading.Read(PostageState(false, UInt256.of(207_062), minimum), 100)
    private var batch: SwarmReading<BatchFinding> = SwarmReading.Verified(
        BatchFinding.Node(PostageBatch(id, node, 22, 16, false, UInt256.of(8_000_000_000), UInt256.of(207_062))),
        100,
    )

    private val contracts = object : SwarmContracts {
        override fun observeChequebook(network: Network, node: Address, chequebook: Address) =
            flowOf(SwarmReading.NotPinned)
        override fun observeBatch(network: Network, node: Address, id: BatchId): Flow<SwarmReading<BatchFinding>> =
            flowOf(batch)
        override fun deployment(network: Network) = SwarmDeployment(bzz, stamp)
        override suspend fun postage(network: Network) = postage
    }

    private val inputs = FakeTransactionInputs()
    private val native = FakeNativeBalances()
    private val sent = MemorySentTransfers()

    /** The PostageStamp's allowance over each account's xBZZ, as `allowance` answers it. */
    private var allowance = UInt256.of(0)

    init {
        listOf(node, payer).forEach {
            native.of(it).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
        }
        inputs.simulationOf = { _, to ->
            // xBZZ answers `allowance` with the allowance, `approve` with true; the PostageStamp nothing.
            val asked = inputs.simulated.last().third.toHex()
            val answer = when {
                to != bzz.address -> ""
                asked.startsWith(ALLOWANCE) -> allowance.toBytes32().toHex()
                else -> UInt256.of(1).toBytes32().toHex()
            }
            Simulation.Succeeded(answer, 100)
        }
    }

    private companion object {
        /** ERC-20's `allowance(address,address)`. */
        const val ALLOWANCE = "dd62ed3e"
    }

    private val approvals =
        QuoteTokenApproval(inputs, native, sent, Network.GNOSIS, outbox = Outbox.None, clock = { 1_000 })
    private val quoter = BatchQuoter(contracts, inputs, native, sent, Outbox.None, approvals) { 1_000 }

    private suspend fun buy(perChunk: UInt256 = minimum, depth: Int = 22) =
        QuoteBatchPurchase(quoter) { nonce }(payer, node, depth, perChunk, immutable = true, FeeLevel.NORMAL)

    @Test
    fun aPurchaseWhoseAllowanceCoversItIsQuotedAsTheCallItIs() = runTest {
        allowance = SwarmCalls.total(minimum, 22)!!
        val quote = assertIs<BatchQuoting.Quoted>(buy()).quote
        assertEquals(allowance, quote.total)
        assertEquals(stamp, quote.quote.transaction.to)
        val encoded = SwarmCalls.createBatch(node, minimum, 22, nonce, true).toHex()
        assertEquals(encoded, quote.quote.transaction.data.toHex())
        val call = assertIs<BatchCall.Purchase>(quote.call)
        assertEquals(BatchId.of(payer, nonce), call.id)
        assertEquals(SentAction.BatchPurchase(stamp, node, call.id, 22, minimum, true), call.action)
    }

    @Test
    fun aPurchaseIsApprovedFirstForExactlyItsTotal() = runTest {
        val quoting = buy()
        val needs = assertIs<BatchQuoting.NeedsApproval>(quoting, quoting.toString())
        assertEquals(SwarmCalls.total(minimum, 22), needs.approval.amount)
        assertEquals(stamp, needs.approval.spender)
        assertEquals(bzz.address, needs.approval.token.address)
    }

    @Test
    fun aPurchaseIsRefusedWhatThePostageStampWouldRefuse() = runTest {
        assertEquals(BatchQuoting.Refused(BatchRefusal.DepthNotOffered), buy(depth = 16))
        assertEquals(BatchQuoting.Refused(BatchRefusal.BelowMinimum(minimum)), buy(perChunk = UInt256.of(1)))
        postage = PostageReading.Read(PostageState(true, UInt256.of(207_062), minimum), 100)
        assertEquals(BatchQuoting.Refused(BatchRefusal.Paused), buy())
        postage = PostageReading.BzzChanged
        assertEquals(BatchQuoting.Refused(BatchRefusal.BzzChanged), buy())
    }

    @Test
    fun aTopUpIsOfTheNodesBatchAloneAndNotExpired() = runTest {
        allowance = UInt256.of(Long.MAX_VALUE)
        val topUp = QuoteBatchTopUp(quoter)
        val quote = assertIs<BatchQuoting.Quoted>(topUp(payer, node, id, UInt256.of(1_000), FeeLevel.NORMAL)).quote
        assertEquals(SwarmCalls.total(UInt256.of(1_000), 22), quote.total)
        assertEquals(SwarmCalls.topUp(id, UInt256.of(1_000)).toHex(), quote.quote.transaction.data.toHex())

        batch = SwarmReading.Verified(BatchFinding.OthersOwner(stranger), 100)
        assertEquals(
            BatchQuoting.Refused(BatchRefusal.NotTheNodes(BatchFinding.OthersOwner(stranger))),
            topUp(payer, node, id, UInt256.of(1_000), FeeLevel.NORMAL),
        )
        batch = SwarmReading.Verified(
            BatchFinding.Node(PostageBatch(id, node, 22, 16, false, UInt256.ZERO, UInt256.of(207_062))),
            100,
        )
        assertEquals(
            BatchQuoting.Refused(BatchRefusal.Expired),
            topUp(payer, node, id, UInt256.of(1_000), FeeLevel.NORMAL),
        )
    }

    @Test
    fun aDilutionMovesNoXbzzAndKeepsTheMinimum() = runTest {
        val dilute = QuoteBatchDilution(quoter)
        // 8,000,000,000 per chunk, halved once: 4,000,000,000, above the minimum.
        val quote = assertIs<BatchQuoting.Quoted>(dilute(node, id, 23, FeeLevel.NORMAL)).quote
        assertTrue(quote.total.isZero)
        assertEquals(node, quote.quote.from)
        assertTrue(inputs.simulated.none { it.second == bzz.address }, "no allowance is read for a dilution")
        // Halved twice: 2,000,000,000, below it.
        assertEquals(BatchQuoting.Refused(BatchRefusal.BelowMinimum(minimum)), dilute(node, id, 24, FeeLevel.NORMAL))
        assertEquals(BatchQuoting.Refused(BatchRefusal.DepthNotOffered), dilute(node, id, 22, FeeLevel.NORMAL))
    }

    private val kept = mutableListOf<Triple<AccountId, SwarmCandidate, Boolean>>()
    private val candidates = object : SwarmCandidates {
        override fun observe(node: AccountId) = flowOf(SwarmCandidateList.Loaded(emptyList(), emptyList()))
        override fun observeNodes(network: Network) = flowOf(emptyMap<AccountId, SwarmCandidateList.Loaded>())
        override suspend fun add(node: AccountId, candidate: SwarmCandidate, byHand: Boolean): SwarmCandidateChange {
            kept += Triple(node, candidate, byHand)
            return SwarmCandidateChange.CHANGED
        }
        override suspend fun remove(node: AccountId, candidate: SwarmCandidate) = SwarmCandidateChange.NOT_FOUND
        override suspend fun forget(node: AccountId) = SwarmCandidateChange.NOT_FOUND
    }

    @Test
    fun aPurchaseSentKeepsItsBatchForTheNode() = runTest {
        allowance = UInt256.of(Long.MAX_VALUE)
        val quote = assertIs<BatchQuoting.Quoted>(buy()).quote
        val send = SendBatchCall(FakeSigner(FakeRecovery()), FakeBroadcaster(), sent, inputs, candidates, contracts) {
            1_000
        }
        val sending = assertIs<BatchSending.Sending>(send(quote, Unlock.WithBiometrics("test")))
        assertIs<TransferSending.Sent>(sending.sending)
        val batch: SwarmCandidate = SwarmCandidate.Batch(BatchId.of(payer, nonce))
        assertEquals(listOf(Triple(AccountId(Network.GNOSIS, node), batch, true)), kept)
        val bought: SentAction = SentAction.BatchPurchase(stamp, node, BatchId.of(payer, nonce), 22, minimum, true)
        assertEquals(bought, sent.transfers.value.single().action)
    }

    @Test
    fun anotherPurchaseWaitsForOneOnItsWay() = runTest {
        allowance = UInt256.of(Long.MAX_VALUE)
        val first = assertIs<BatchQuoting.Quoted>(buy()).quote
        val signer = FakeSigner(FakeRecovery())
        val send = SendBatchCall(signer, FakeBroadcaster(), sent, inputs, candidates, contracts) { 1_000 }
        send(first, Unlock.WithBiometrics("t"))
        // Not mined yet: it pulls on the allowance a second one would count on.
        val hash = sent.transfers.value.single().hash
        assertEquals(BatchQuoting.EarlierPullPending(hash), buy())
        assertEquals(
            BatchQuoting.EarlierPullPending(hash),
            QuoteBatchTopUp(quoter)(payer, node, id, UInt256.of(1_000), FeeLevel.NORMAL),
        )
    }

    @Test
    fun aTopUpIsNotSignedOnceTheBatchWasDilutedMeanwhile() = runTest {
        allowance = UInt256.of(Long.MAX_VALUE)
        val quoting = QuoteBatchTopUp(quoter)(payer, node, id, UInt256.of(1_000), FeeLevel.NORMAL)
        val quote = assertIs<BatchQuoting.Quoted>(quoting).quote
        // The node's Bee diluted the batch to 23: the top-up would pull twice what was approved.
        batch = SwarmReading.Verified(
            BatchFinding.Node(PostageBatch(id, node, 23, 16, false, UInt256.of(4_000_000_000), UInt256.of(207_062))),
            101,
        )
        val signer = FakeSigner(FakeRecovery())
        val send = SendBatchCall(signer, FakeBroadcaster(), sent, inputs, candidates, contracts) { 1_000 }
        assertEquals(BatchSending.BatchChanged, send(quote, Unlock.WithBiometrics("test")))
        assertTrue(signer.asked.isEmpty())
    }

    @Test
    fun nothingIsSignedOnceAnotherTransactionTookTheNonce() = runTest {
        val quote = assertIs<BatchQuoting.Quoted>(QuoteBatchDilution(quoter)(node, id, 23, FeeLevel.NORMAL)).quote
        // A Bee node signing with the same key mined a transaction meanwhile.
        inputs.mined = quote.quote.transaction.nonce + 1
        val signer = FakeSigner(FakeRecovery())
        val send = SendBatchCall(signer, FakeBroadcaster(), sent, inputs, candidates, contracts) { 1_000 }
        assertEquals(BatchSending.NonceTaken, send(quote, Unlock.WithBiometrics("test")))
        assertTrue(signer.asked.isEmpty())
        assertTrue(sent.transfers.value.isEmpty())
    }
}
