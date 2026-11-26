package eth.serotine.data.ledger

import eth.serotine.data.account.InMemoryAccountRepository
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Eip712Hashes
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.LedgerDevice
import eth.serotine.domain.repository.LedgerProblem
import eth.serotine.domain.repository.LedgerResult
import eth.serotine.domain.repository.SignatureRecovery
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class LedgerSignerTest {
    private val path = LedgerPath.DEFAULT
    private val sender = Address.of("0x" + "aa".repeat(20))
    private val recipient = Address.of("0x" + "bb".repeat(20))
    private val account = Account(Network.GNOSIS, sender, AccountKind.EOA, "Ledger", AccountCustody.Ledger(path))
    private val accounts = InMemoryAccountRepository(listOf(account))
    private val time = TestTimeSource()
    private val device = Device()
    private val signature = Signature("01".repeat(32), "02".repeat(32), 1)
    private val hashes = Eip712Hashes(Digest.of("12".repeat(32)), Digest.of("34".repeat(32)))
    private val message = assertNotNull(PersonalMessage.of(byteArrayOf(0x80.toByte(), 0x00, 0x61)))
    private val localDigests = mutableListOf<Digest>()
    private val local = object : Signer {
        override suspend fun sign(account: Address, digest: Digest, unlock: Unlock): Signing {
            assertEquals(sender, account)
            assertTrue(unlock !is Unlock.WithLedger)
            localDigests += digest
            return Signing.Signed(signature)
        }
    }
    private val recoveredDigests = mutableListOf<Digest>()
    private var recovered: Address? = sender
    private val recovery = object : SignatureRecovery {
        override fun recover(digest: Digest, signature: Signature): Address? {
            assertEquals(this@LedgerSignerTest.signature, signature)
            recoveredDigests += digest
            return recovered
        }
    }
    private val signer = LedgerSigner(local, device, accounts, recovery)

    @Test
    fun signsOnlyTheExactTransactionTypedDataAndPersonalMessage() = runTest {
        val tx = transaction()
        device.transaction = {
            assertEquals(tx, it)
            LedgerResult.Ok(signature)
        }
        device.typed = {
            assertEquals(hashes, it)
            LedgerResult.Ok(signature)
        }
        device.personal = {
            assertEquals(message, it)
            LedgerResult.Ok(signature)
        }

        assertEquals(Signing.Signed(signature), signer.signTransaction(account.id, tx, approval()))
        assertEquals(Signing.Signed(signature), signer.signTypedData(account.id, hashes, approval()))
        assertEquals(Signing.Signed(signature), signer.signPersonalMessage(account.id, message, approval()))
        assertEquals(listOf(tx.signingDigest(), hashes.digest, message.digest()), recoveredDigests)
        assertTrue(localDigests.isEmpty())
        assertEquals(3, device.calls)
    }

    @Test
    fun refusesRawLedgerApprovalAndNeverUsesALocalKeyForACurrentLedgerEnrollment() = runTest {
        val localAccount = account.copy(network = Network.ETHEREUM, custody = AccountCustody.InKeyStore)
        accounts.add(localAccount)
        val software = Unlock.WithBiometrics("Sign")
        assertEquals(Signing.NoKey, signer.sign(sender, hashes.digest, approval()))
        assertEquals(Signing.NoKey, signer.signTransaction(account.id, transaction(), software))
        assertEquals(Signing.NoKey, signer.signTypedData(account.id, hashes, software))
        assertEquals(Signing.NoKey, signer.signPersonalMessage(account.id, message, software))
        assertTrue(localDigests.isEmpty())
        assertEquals(0, device.calls)

        assertEquals(Signing.Signed(signature), signer.signTypedData(localAccount.id, hashes, software))
        val safeOwner = AccountId(Network.SEPOLIA, sender)
        accounts.add(localAccount.copy(network = safeOwner.network, custody = AccountCustody.Watched))
        assertEquals(Signing.Signed(signature), signer.signTypedData(safeOwner, hashes, software))
        assertEquals(listOf(hashes.digest, hashes.digest), localDigests)
        assertEquals(0, device.calls)
    }

    @Test
    fun dropsALocalSignatureIfTheAccountWasEnrolledWhileItWasSigning() = runTest {
        accounts.remove(account.id)
        val changingLocal = object : Signer {
            override suspend fun sign(account: Address, digest: Digest, unlock: Unlock): Signing {
                accounts.add(this@LedgerSignerTest.account)
                return Signing.Signed(signature)
            }
        }
        val adapter = LedgerSigner(changingLocal, device, accounts, recovery)
        val result = adapter.signTypedData(account.id, hashes, Unlock.WithBiometrics("Sign"))
        assertEquals(Signing.NoKey, result)
        assertEquals(0, device.calls)
    }

    @Test
    fun refusesWrongChainAndMissingExactEnrollmentBeforeContactingTheDevice() = runTest {
        assertEquals(
            Signing.LedgerFailed(LedgerProblem.UNSUPPORTED_TRANSACTION),
            signer.signTransaction(account.id, transaction(Network.SEPOLIA), approval()),
        )
        val otherChain = AccountId(Network.SEPOLIA, sender)
        accounts.add(account.copy(network = otherChain.network, custody = AccountCustody.Watched))
        assertEquals(
            Signing.LedgerFailed(LedgerProblem.WRONG_ACCOUNT),
            signer.signTransaction(otherChain, transaction(Network.SEPOLIA), approval()),
        )
        assertEquals(0, device.calls)
    }

    @Test
    fun refusesAnUnreadableEnrollmentList() = runTest {
        val unreadable = object : AccountRepository by accounts {
            override fun observeAccounts() = flowOf(AccountList.Unreadable(AccountList.Reason.READ_FAILED))
        }
        val adapter = LedgerSigner(local, device, unreadable, recovery)
        assertEquals(Signing.LedgerFailed(LedgerProblem.UNAVAILABLE), adapter.signTypedData(account.id, hashes, approval()))
        assertEquals(0, device.calls)
    }

    @Test
    fun rejectsWrongAndInvalidRecoveredAddresses() = runTest {
        device.transaction = { LedgerResult.Ok(signature) }
        recovered = recipient
        assertEquals(Signing.LedgerFailed(LedgerProblem.WRONG_ACCOUNT), signer.signTransaction(account.id, transaction(), approval()))
        recovered = null
        assertEquals(Signing.LedgerFailed(LedgerProblem.INVALID_RESPONSE), signer.signTransaction(account.id, transaction(), approval()))
        assertTrue(localDigests.isEmpty())
        assertEquals(2, device.calls)
    }

    @Test
    fun forwardsADeviceRejectionWithoutRecoveryOrFallback() = runTest {
        device.transaction = { LedgerResult.Failed(LedgerProblem.REJECTED) }
        assertEquals(Signing.LedgerFailed(LedgerProblem.REJECTED), signer.signTransaction(account.id, transaction(), approval()))
        assertTrue(recoveredDigests.isEmpty())
        assertTrue(localDigests.isEmpty())
        assertEquals(1, device.calls)
    }

    @Test
    fun refusesExpiredApprovalBeforeContactingTheDevice() = runTest {
        val expired = approval()
        time += 2.seconds
        assertEquals(Signing.TimedOut, signer.signTransaction(account.id, transaction(), expired))
        assertEquals(0, device.calls)
    }

    @Test
    fun cancelsAnOutstandingDeviceApprovalAtTheDeadline() = runTest {
        var cancelled = false
        device.transaction = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        assertEquals(Signing.TimedOut, signer.signTransaction(account.id, transaction(), approval()))
        assertTrue(cancelled)
        assertTrue(recoveredDigests.isEmpty())
    }

    @Test
    fun ignoresADeviceSignatureAfterTheDeadlineEvenBeforeTimeoutDispatch() = runTest {
        device.transaction = {
            time += 2.seconds
            LedgerResult.Ok(signature)
        }
        assertEquals(Signing.TimedOut, signer.signTransaction(account.id, transaction(), approval()))
        assertTrue(recoveredDigests.isEmpty())
    }

    @Test
    fun rejectsADeviceSignatureIfEnrollmentWasRemovedOrItsPathChanged() = runTest {
        val newPath = assertNotNull(LedgerPath.parse("m/44'/60'/1'/0/0"))
        for (replacement in listOf(null, account.copy(custody = AccountCustody.Ledger(newPath)))) {
            device.transaction = {
                accounts.remove(account.id)
                if (replacement != null) accounts.add(replacement)
                LedgerResult.Ok(signature)
            }
            assertEquals(
                Signing.LedgerFailed(LedgerProblem.WRONG_ACCOUNT),
                signer.signTransaction(account.id, transaction(), approval()),
            )
            accounts.remove(account.id)
            accounts.add(account)
        }
        assertEquals(2, device.calls)
    }

    @Test
    fun cancellationWinsOverADeviceSignature() = runTest {
        device.transaction = {
            currentCoroutineContext().cancel()
            LedgerResult.Ok(signature)
        }
        val job = launch { signer.signTransaction(account.id, transaction(), approval()) }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, device.calls)
        assertTrue(recoveredDigests.isEmpty())
    }

    private fun approval(): Unlock.WithLedger = Unlock.WithLedger(time.markNow() + 1.seconds)

    private fun transaction(network: Network = account.network): Eip1559Transaction = Eip1559Transaction(
        network.chainId, 7, UInt256.of(1L), UInt256.of(2L), 40_000, recipient, UInt256.ZERO, byteArrayOf(1, 4, 0),
    )

    private inner class Device : LedgerDevice {
        var transaction: suspend (Eip1559Transaction) -> LedgerResult<Signature> = { error("unexpected transaction") }
        var typed: suspend (Eip712Hashes) -> LedgerResult<Signature> = { error("unexpected typed data") }
        var personal: suspend (PersonalMessage) -> LedgerResult<Signature> = { error("unexpected personal message") }
        var calls = 0

        override suspend fun confirmAddress(path: LedgerPath): LedgerResult<Address> = error("unexpected enrollment")

        override suspend fun signTransaction(
            path: LedgerPath,
            expectedAddress: Address,
            transaction: Eip1559Transaction,
        ): LedgerResult<Signature> {
            checkSender(path, expectedAddress)
            return this.transaction(transaction)
        }

        override suspend fun signTypedData(
            path: LedgerPath,
            expectedAddress: Address,
            hashes: Eip712Hashes,
        ): LedgerResult<Signature> {
            checkSender(path, expectedAddress)
            return typed(hashes)
        }

        override suspend fun signPersonalMessage(
            path: LedgerPath,
            expectedAddress: Address,
            message: PersonalMessage,
        ): LedgerResult<Signature> {
            checkSender(path, expectedAddress)
            return personal(message)
        }

        private fun checkSender(path: LedgerPath, address: Address) {
            assertEquals(this@LedgerSignerTest.path, path)
            assertEquals(sender, address)
            calls++
        }
    }
}
