package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.LedgerDevice
import eth.serotine.domain.repository.LedgerProblem
import eth.serotine.domain.repository.LedgerResult
import eth.serotine.domain.repository.SentAction
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeMark
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class LedgerAccountsTest {
    private val path = LedgerPath.DEFAULT
    private val sender = Address.of("0x" + "aa".repeat(20))
    private val recipient = Address.of("0x" + "bb".repeat(20))
    private val account = Account(Network.ETHEREUM, sender, AccountKind.EOA, "Ledger", AccountCustody.Ledger(path))
    private val accounts = ListAccountRepository(account)
    private val recovery = FakeRecovery()
    private val broadcaster = FakeBroadcaster()
    private val sent = MemorySentTransfers()
    private val time = TestTimeSource()
    private val device = Device()
    private val enroll = EnrollLedgerAccount(device, accounts, WalletChanges())
    private val send = SendLedgerTransfer(device, recovery, broadcaster, sent, accounts) { 123 }

    @Test
    fun acceptsOnlyEthereumPathsWithThreeToFiveUint31Components() {
        assertEquals(path, LedgerPath.parse("  44'/60'/00'/0/00  "))
        assertEquals("m/44'/60'/2147483647'", LedgerPath.parse("m/44'/60'/2147483647'")?.value)
        for (bad in listOf(
            "m/44'/60'", "m/44'/60'/0'/0/0/0", "m/44'/60'/2147483648'", "m/44'/60'/-1'",
            "m/44'/60'/+1", "m/44'/61'/0'", "m/44/60'/0'", "m/44'/60'/0'/", "m/44'/60'/0''",
        )) assertNull(LedgerPath.parse(bad), bad)
    }

    @Test
    fun anAddressIsAddedOnlyOnSupportedNetworksAndNeverReplacesEntries() = runTest {
        val watched = account.copy(custody = AccountCustody.Watched)
        val keyed = account.copy(network = Network.SEPOLIA, custody = AccountCustody.InKeyStore)
        accounts.accounts.value = listOf(watched, keyed)
        device.confirm = LedgerResult.Ok(sender)
        val confirmed = assertIs<LedgerResult.Ok<ConfirmedLedgerAccount>>(enroll.confirm(path)).value
        assertEquals(sender, confirmed.address)
        assertEquals(path, confirmed.path)
        assertEquals(AccountAdd.NotSaved, enroll.add(Network.GNOSIS, confirmed))
        assertEquals(AccountAdd.AlreadyPresent, enroll.add(Network.ETHEREUM, confirmed))
        assertEquals(AccountAdd.AlreadyPresent, enroll.add(Network.SEPOLIA, confirmed))
        assertEquals(listOf(watched, keyed), accounts.accounts.value)

        accounts.remove(keyed.id)
        assertEquals(AccountAdd.Added, enroll.add(Network.SEPOLIA, confirmed))
        assertEquals(keyed.id, accounts.accounts.value.last().id)
        assertEquals(AccountCustody.Ledger(path), accounts.accounts.value.last().custody)
    }

    @Test
    fun rejectedAddressConfirmationNeverYieldsAnEnrollment() = runTest {
        device.confirm = LedgerResult.Failed(LedgerProblem.REJECTED)
        assertEquals(LedgerResult.Failed(LedgerProblem.REJECTED), enroll.confirm(path))
        assertEquals(listOf(account), accounts.accounts.value)
    }

    @Test
    fun mismatchUnsupportedNetworkTokenOrCalldataIsRefusedBeforeContactingTheDevice() = runTest {
        val token = Token(recipient, "TKN", "Token", 18, icon = null)
        val unsupported = listOf(
            quote(chainId = Network.SEPOLIA.chainId),
            quote(network = Network.GNOSIS),
            quote(asset = TransferAsset.Erc20Token(token)),
            quote(data = byteArrayOf(1)),
        )
        for (quote in unsupported) {
            assertEquals(LedgerResult.Failed(LedgerProblem.UNSUPPORTED_TRANSACTION), send(quote, path, deadline()))
        }
        assertEquals(0, device.signCalls)
        assertNothingSent()
    }

    @Test
    fun aWatchedAccountCannotUseLedgerAndALedgerCannotBeOfferedToADapp() = runTest {
        assertEquals(DappOffer.NO_KEY, dappOffer(account, setOf(account.network)))
        accounts.accounts.value = listOf(account.copy(custody = AccountCustody.Watched))
        assertEquals(LedgerResult.Failed(LedgerProblem.WRONG_ACCOUNT), send(quote(), path, deadline()))
        assertEquals(0, device.signCalls)
        assertNothingSent()
    }

    @Test
    fun aSignatureFromTheWrongSenderNeverReachesBroadcast() = runTest {
        device.sign = { LedgerResult.Ok(recovery.sign(recipient, it.signingDigest())) }
        assertEquals(LedgerResult.Failed(LedgerProblem.WRONG_ACCOUNT), send(quote(), path, deadline()))
        assertEquals(1, device.signCalls)
        assertNothingSent()
    }

    @Test
    fun aDeviceRejectionNeverReachesBroadcast() = runTest {
        device.sign = { LedgerResult.Failed(LedgerProblem.REJECTED) }
        assertEquals(LedgerResult.Failed(LedgerProblem.REJECTED), send(quote(), path, deadline()))
        assertEquals(1, device.signCalls)
        assertNothingSent()
    }

    @Test
    fun aSignatureReturnedAfterTheQuoteDeadlineNeverReachesBroadcast() = runTest {
        device.sign = {
            time += 2.seconds
            LedgerResult.Ok(recovery.sign(sender, it.signingDigest()))
        }
        assertEquals(LedgerResult.Failed(LedgerProblem.TIMED_OUT), send(quote(), path, deadline()))
        assertNothingSent()
    }

    @Test
    fun theQuoteDeadlineCancelsAnOutstandingDeviceApproval() = runTest {
        var cancelled = false
        device.sign = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        assertEquals(LedgerResult.Failed(LedgerProblem.TIMED_OUT), send(quote(), path, deadline()))
        assertTrue(cancelled)
        assertNothingSent()
    }

    @Test
    fun removingTheEnrollmentDuringDeviceApprovalPreventsBroadcast() = runTest {
        device.sign = {
            accounts.remove(account.id)
            LedgerResult.Ok(recovery.sign(sender, it.signingDigest()))
        }
        assertEquals(LedgerResult.Failed(LedgerProblem.WRONG_ACCOUNT), send(quote(), path, deadline()))
        assertNothingSent()
    }

    @Test
    fun cancellationEvenWithADeviceResponsePreventsBroadcast() = runTest {
        device.sign = {
            currentCoroutineContext().cancel()
            LedgerResult.Ok(recovery.sign(sender, it.signingDigest()))
        }
        val job = launch { send(quote(), path, deadline()) }
        job.join()
        assertTrue(job.isCancelled)
        assertNothingSent()
    }

    @Test
    fun sendsTheExactApprovedTransactionAndKeepsAnUnansweredBroadcastForRetry() = runTest {
        val quote = quote()
        val signature = recovery.sign(sender, quote.transaction.signingDigest())
        device.sign = { LedgerResult.Ok(signature) }
        broadcaster.answer = Broadcast.Unavailable("no peer answered")

        val result = assertIs<LedgerResult.Ok<TransferSending>>(send(quote, path, deadline())).value
        val notSent = assertIs<TransferSending.NotSent>(result)
        assertContentEquals(quote.transaction.encodeSigned(signature), broadcaster.sent.single())
        assertEquals(notSent.transfer, sent.transfers.value.single())
        assertTrue(notSent.transfer.maybeSent)
        assertEquals(123L, notSent.transfer.sentAt)
        assertEquals(SentAction.Transfer(quote.to, quote.asset, quote.amount), notSent.transfer.action)
    }

    private fun deadline(): TimeMark = time.markNow() + 1.seconds

    private fun assertNothingSent() {
        assertTrue(broadcaster.sent.isEmpty())
        assertTrue(sent.transfers.value.isEmpty())
    }

    private fun quote(
        network: Network = account.network,
        chainId: Long = network.chainId,
        asset: TransferAsset = TransferAsset.Native,
        data: ByteArray = ByteArray(0),
    ): TransferQuote {
        val amount = UInt256.of(1_000L)
        return TransferQuote(
            network = network,
            from = sender,
            to = recipient,
            asset = asset,
            amount = amount,
            level = FeeLevel.NORMAL,
            transaction = Eip1559Transaction(chainId, 7, UInt256.of(1L), UInt256.of(2L), 21_000, recipient, amount, data),
            maxFee = UInt256.of(42_000L),
            nativeBefore = UInt256.of(100_000L),
            nativeAfterAtLeast = UInt256.of(57_000L),
            pendingCost = UInt256.ZERO,
            tokenBefore = null,
            pendingTokenAmount = null,
            heldByOrders = null,
            tokenAfter = null,
            balanceBlock = 100,
            tokenBalanceBlock = null,
            feeBlock = 100,
            simulationBlock = 100,
        )
    }

    private inner class Device : LedgerDevice {
        var confirm: LedgerResult<Address> = LedgerResult.Failed(LedgerProblem.NOT_CONNECTED)
        var sign: suspend (Eip1559Transaction) -> LedgerResult<Signature> = { error("unexpected Ledger signing") }
        var signCalls = 0

        override suspend fun confirmAddress(path: LedgerPath): LedgerResult<Address> = confirm

        override suspend fun signTransaction(
            path: LedgerPath,
            expectedAddress: Address,
            transaction: Eip1559Transaction,
        ): LedgerResult<Signature> {
            assertEquals(this@LedgerAccountsTest.path, path)
            assertEquals(sender, expectedAddress)
            signCalls++
            return sign(transaction)
        }
    }
}
