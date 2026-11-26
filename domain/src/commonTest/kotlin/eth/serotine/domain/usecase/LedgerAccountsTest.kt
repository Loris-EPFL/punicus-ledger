package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Erc20
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
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
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
    private val signer = FakeSigner(recovery)
    private val enroll = EnrollLedgerAccount(device, accounts, WalletChanges())
    private val send = SendTransfer(signer, broadcaster, sent) { 123 }

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
    fun anAddressCanBeAddedOnEveryNetworkAndNeverReplacesEntries() = runTest {
        val watched = account.copy(custody = AccountCustody.Watched)
        val keyed = account.copy(network = Network.SEPOLIA, custody = AccountCustody.InKeyStore)
        accounts.accounts.value = listOf(watched, keyed)
        device.confirm = LedgerResult.Ok(sender)
        val confirmed = assertIs<LedgerResult.Ok<ConfirmedLedgerAccount>>(enroll.confirm(path)).value
        assertEquals(sender, confirmed.address)
        assertEquals(path, confirmed.path)
        val gnosis = account.copy(network = Network.GNOSIS)
        assertEquals(AccountAdd.Added, enroll.add(Network.GNOSIS, confirmed))
        assertEquals(AccountAdd.AlreadyPresent, enroll.add(Network.ETHEREUM, confirmed))
        assertEquals(AccountAdd.AlreadyPresent, enroll.add(Network.SEPOLIA, confirmed))
        assertEquals(listOf(watched.id, keyed.id, gnosis.id), accounts.accounts.value.map { it.id })
        assertEquals(watched, accounts.accounts.value.first())
        assertEquals(keyed, accounts.accounts.value[1])
        assertEquals(gnosis.custody, accounts.accounts.value.last().custody)

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
    fun aLedgerEnrolledOnOneChainCanBeAddedOnTheOthersWithTheSamePath() = runTest {
        val add = AddOnNetworks(accounts, WalletChanges())
        assertEquals(NetworksAdd.Added(listOf(Network.GNOSIS, Network.SEPOLIA)), add(account.id, Network.entries))
        assertEquals(Network.entries.toSet(), accounts.accounts.value.map { it.network }.toSet())
        assertTrue(accounts.accounts.value.all { it.custody == AccountCustody.Ledger(path) })
    }

    @Test
    fun aMismatchedChainIsRefusedBeforeContactingSigner() = runTest {
        val failure = Signing.LedgerFailed(LedgerProblem.UNSUPPORTED_TRANSACTION)
        assertEquals(TransferSending.NotSigned(failure), send(quote(chainId = Network.SEPOLIA.chainId), approval()))
        assertTrue(signer.ledgerTransactions.isEmpty())
        assertNothingSent()
    }

    @Test
    fun anEnrolledLedgerMayBeOfferedToADappButAWatchedAccountMayNot() {
        val enabled = setOf(account.network)
        assertEquals(DappOffer.OFFERED, dappOffer(account, enabled))
        assertEquals(DappOffer.NO_KEY, dappOffer(account.copy(custody = AccountCustody.Watched), enabled))
    }

    @Test
    fun aDeviceRejectionNeverReachesBroadcastOrFallsBackToTheLocalSigner() = runTest {
        val refused = Signing.LedgerFailed(LedgerProblem.REJECTED)
        signer.answer = refused
        val quote = quote()
        assertEquals(TransferSending.NotSigned(refused), send(quote, approval()))
        assertEquals(listOf(account.id to quote.transaction), signer.ledgerTransactions)
        assertTrue(signer.asked.isEmpty())
        assertNothingSent()
    }

    @Test
    fun aSignatureReturnedAfterTheQuoteDeadlineNeverReachesBroadcast() = runTest {
        signer.beforeLedgerReply = { time += 2.seconds }
        assertEquals(TransferSending.NotSigned(Signing.TimedOut), send(quote(), approval()))
        assertNothingSent()
    }

    @Test
    fun sendsTheExactApprovedTransactionAndKeepsAnUnansweredBroadcastForRetry() = runTest {
        val token = Token(Address.of("0x" + "cc".repeat(20)), "TKN", "Token", 18, icon = null)
        val quote = quote(network = Network.GNOSIS, asset = TransferAsset.Erc20Token(token))
        val signature = recovery.sign(sender, quote.transaction.signingDigest())
        broadcaster.answer = Broadcast.Unavailable("no peer answered")

        val notSent = assertIs<TransferSending.NotSent>(send(quote, approval()))
        assertEquals(listOf(AccountId(Network.GNOSIS, sender) to quote.transaction), signer.ledgerTransactions)
        assertTrue(signer.asked.isEmpty())
        assertContentEquals(quote.transaction.encodeSigned(signature), broadcaster.sent.single())
        assertEquals(notSent.transfer, sent.transfers.value.single())
        assertTrue(notSent.transfer.maybeSent)
        assertEquals(123L, notSent.transfer.sentAt)
        assertEquals(SentAction.Transfer(quote.to, quote.asset, quote.amount), notSent.transfer.action)
    }

    private fun approval(): Unlock.WithLedger = Unlock.WithLedger(time.markNow() + 1.seconds)

    private fun assertNothingSent() {
        assertTrue(broadcaster.sent.isEmpty())
        assertTrue(sent.transfers.value.isEmpty())
    }

    private fun quote(
        network: Network = account.network,
        chainId: Long = network.chainId,
        asset: TransferAsset = TransferAsset.Native,
    ): TransferQuote {
        val amount = UInt256.of(1_000L)
        val token = (asset as? TransferAsset.Erc20Token)?.token
        val transaction = Eip1559Transaction(
            chainId, 7, UInt256.of(1L), UInt256.of(2L), 80_000, token?.address ?: recipient,
            if (token == null) amount else UInt256.ZERO,
            if (token == null) ByteArray(0) else Erc20.transferData(recipient, amount),
        )
        return TransferQuote(
            network = network,
            from = sender,
            to = recipient,
            asset = asset,
            amount = amount,
            level = FeeLevel.NORMAL,
            transaction = transaction,
            maxFee = UInt256.of(160_000L),
            nativeBefore = UInt256.of(500_000L),
            nativeAfterAtLeast = UInt256.of(if (token == null) 339_000L else 340_000L),
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

        override suspend fun confirmAddress(path: LedgerPath): LedgerResult<Address> = confirm

        override suspend fun signTransaction(
            path: LedgerPath,
            expectedAddress: Address,
            transaction: Eip1559Transaction,
        ): LedgerResult<Signature> = error("enrollment must not sign a transaction")
    }
}
