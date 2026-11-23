package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Chequebook
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SwarmCalls
import eth.serotine.domain.model.SwarmDeployment
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BatchFinding
import eth.serotine.domain.repository.ChequebookFinding
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.PostageReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
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

/** Withdrawing a Swarm node's xBZZ from its chequebook, over fake ports. */
class ChequebookWithdrawalTest {

    private val bzz =
        Token(Address.of("0xdbf3ea6f5bee45c02255b2c26a16f300502f68da"), "BZZ", "BZZ on xDai", 16, icon = "bzz")
    private val stamp = Address.of("0x45a1502382541cd610cc9068e88727426b696293")
    private val node = Address.of("0x" + "0b".repeat(20))
    private val stranger = Address.of("0x" + "5e".repeat(20))
    private val address = Address.of("0x" + "cb".repeat(20))

    private var liquid = UInt256.of(10_000_000_000_000_000)
    private var finding: () -> SwarmReading<ChequebookFinding> = {
        SwarmReading.Verified(
            ChequebookFinding.Node(
                Chequebook(address, node, UInt256.of(12_000_000_000_000_000), liquid, UInt256.ZERO, bounced = false),
            ),
            100,
        )
    }

    private val contracts = object : SwarmContracts {
        override fun observeChequebook(network: Network, node: Address, chequebook: Address) = flowOf(finding())
        override suspend fun chequebook(network: Network, node: Address, chequebook: Address) = finding()
        override fun observeBatch(network: Network, node: Address, id: BatchId): Flow<SwarmReading<BatchFinding>> =
            flowOf(SwarmReading.NotPinned)
        override fun deployment(network: Network) = SwarmDeployment(bzz, stamp)
        override suspend fun postage(network: Network, node: Address?, id: BatchId?): PostageReading =
            PostageReading.NotPinned
    }

    private val inputs = FakeTransactionInputs()
    private val native = FakeNativeBalances()
    private val sent = MemorySentTransfers()

    init {
        native.of(node).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
    }

    private val quote = QuoteChequebookWithdrawal(contracts, inputs, native, sent, Outbox.None) { 1_000 }

    private suspend fun withdraw(amount: UInt256) = quote(node, address, amount, FeeLevel.NORMAL)

    @Test
    fun aWithdrawalIsTheChequebooksOwnCallFromTheNode() = runTest {
        val quoted = assertIs<WithdrawalQuoting.Quoted>(withdraw(UInt256.of(1_000))).quote
        assertEquals(address, quoted.quote.transaction.to)
        assertEquals(node, quoted.quote.from)
        assertEquals(SwarmCalls.withdraw(UInt256.of(1_000)).toHex(), quoted.quote.transaction.data.toHex())
        // withdraw(uint256)'s selector, as Bee's chequebook names it.
        assertTrue(quoted.quote.transaction.data.toHex().startsWith("2e1a7d4d"))
        assertEquals(SentAction.ChequebookWithdrawal(bzz, address, UInt256.of(1_000)), quoted.action)
        assertEquals(100, quoted.readAt)
    }

    @Test
    fun aWithdrawalIsRefusedWhatTheChequebookWouldRefuse() = runTest {
        assertEquals(WithdrawalQuoting.Refused(WithdrawalRefusal.NothingToWithdraw), withdraw(UInt256.ZERO))
        assertEquals(
            WithdrawalQuoting.Refused(WithdrawalRefusal.MoreThanFree(liquid)),
            withdraw(UInt256.of(10_000_000_000_000_001)),
        )
        finding = { SwarmReading.Verified(ChequebookFinding.OthersIssuer(stranger), 100) }
        assertEquals(
            WithdrawalQuoting.Refused(WithdrawalRefusal.NotTheNodes(ChequebookFinding.OthersIssuer(stranger))),
            withdraw(UInt256.of(1)),
        )
        finding = { SwarmReading.Unavailable(ReadUnavailable.UNPROVEN) }
        assertEquals(
            WithdrawalQuoting.Refused(WithdrawalRefusal.Unavailable(ReadUnavailable.UNPROVEN)),
            withdraw(UInt256.of(1)),
        )
    }

    @Test
    fun nothingIsSignedOnceTheChequebookOrTheNonceMovedMeanwhile() = runTest {
        val quoted = assertIs<WithdrawalQuoting.Quoted>(withdraw(UInt256.of(5_000_000_000_000_000))).quote
        val signer = FakeSigner(FakeRecovery())
        val send = SendChequebookWithdrawal(signer, FakeBroadcaster(), sent, inputs, contracts) { 1_000 }
        // Bee cashed out meanwhile: less is free than the quote takes.
        liquid = UInt256.of(1)
        assertEquals(WithdrawalSending.Changed, send(quoted, Unlock.WithBiometrics("test")))
        liquid = UInt256.of(10_000_000_000_000_000)
        // Bee, on the same key, mined a transaction meanwhile.
        inputs.mined = quoted.quote.transaction.nonce + 1
        assertEquals(WithdrawalSending.NonceTaken, send(quoted, Unlock.WithBiometrics("test")))
        assertTrue(signer.asked.isEmpty())

        inputs.mined = quoted.quote.transaction.nonce
        assertIs<WithdrawalSending.Sending>(send(quoted, Unlock.WithBiometrics("test")))
        assertEquals(quoted.action, sent.transfers.value.single().action)
    }

    @Test
    fun aWithdrawalOnItsWayCountsAgainstWhatIsFree() = runTest {
        val first = assertIs<WithdrawalQuoting.Quoted>(withdraw(liquid)).quote
        val signer = FakeSigner(FakeRecovery())
        val send = SendChequebookWithdrawal(signer, FakeBroadcaster(), sent, inputs, contracts) { 1_000 }
        assertIs<WithdrawalSending.Sending>(send(first, Unlock.WithBiometrics("test")))
        // Not mined yet: the chequebook still shows it all liquid, and none of it is free for a second one.
        assertEquals(WithdrawalQuoting.Refused(WithdrawalRefusal.MoreThanFree(UInt256.ZERO)), withdraw(UInt256.of(1)))
        // Mined: the record no longer holds it back.
        inputs.mined = first.quote.transaction.nonce + 1
        assertIs<WithdrawalQuoting.Quoted>(withdraw(UInt256.of(1)))
    }
}
