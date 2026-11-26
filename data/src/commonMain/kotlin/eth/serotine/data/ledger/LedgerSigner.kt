package eth.serotine.data.ledger

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Eip712Hashes
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.Signature
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.LedgerDevice
import eth.serotine.domain.repository.LedgerProblem
import eth.serotine.domain.repository.LedgerResult
import eth.serotine.domain.repository.SignatureRecovery
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import kotlin.time.TimeMark
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Routes on-device approvals to a current Ledger enrollment and verifies the exact signature before returning it. */
class LedgerSigner(
    private val local: Signer,
    private val device: LedgerDevice,
    private val accounts: AccountRepository,
    private val recovery: SignatureRecovery,
) : Signer {
    override suspend fun sign(account: Address, digest: Digest, unlock: Unlock): Signing =
        if (unlock is Unlock.WithLedger) Signing.NoKey else local.sign(account, digest, unlock)

    override suspend fun signTransaction(account: AccountId, transaction: Eip1559Transaction, unlock: Unlock): Signing {
        if (unlock !is Unlock.WithLedger) return withLocal(account) { local.signTransaction(account, transaction, unlock) }
        if (transaction.chainId != account.network.chainId) {
            return Signing.LedgerFailed(LedgerProblem.UNSUPPORTED_TRANSACTION)
        }
        return withLedger(account, unlock.notAfter, transaction::signingDigest) { path ->
            device.signTransaction(path, account.address, transaction)
        }
    }

    override suspend fun signTypedData(account: AccountId, hashes: Eip712Hashes, unlock: Unlock): Signing {
        if (unlock !is Unlock.WithLedger) return withLocal(account) { local.signTypedData(account, hashes, unlock) }
        return withLedger(account, unlock.notAfter, { hashes.digest }) { path ->
            device.signTypedData(path, account.address, hashes)
        }
    }

    override suspend fun signPersonalMessage(account: AccountId, message: PersonalMessage, unlock: Unlock): Signing {
        if (unlock !is Unlock.WithLedger) return withLocal(account) { local.signPersonalMessage(account, message, unlock) }
        return withLedger(account, unlock.notAfter, message::digest) { path ->
            device.signPersonalMessage(path, account.address, message)
        }
    }

    private suspend inline fun withLocal(account: AccountId, operation: () -> Signing): Signing {
        val listed = accounts.observeAccounts().first() as? AccountList.Loaded ?: return Signing.Unavailable
        // A stale local unlock cannot sign for an enrolled Ledger, even with this address's key on another network.
        if (listed.find(account)?.custody is AccountCustody.Ledger) return Signing.NoKey
        val result = operation()
        if (result !is Signing.Signed) return result
        val current = accounts.observeAccounts().first() as? AccountList.Loaded ?: return Signing.Unavailable
        return if (current.find(account)?.custody is AccountCustody.Ledger) Signing.NoKey else result
    }

    private suspend inline fun withLedger(
        account: AccountId,
        notAfter: TimeMark,
        crossinline digest: () -> Digest,
        crossinline operation: suspend (LedgerPath) -> LedgerResult<Signature>,
    ): Signing = withTimeoutOrNull(-notAfter.elapsedNow()) {
        val listed = accounts.observeAccounts().first() as? AccountList.Loaded
            ?: return@withTimeoutOrNull Signing.LedgerFailed(LedgerProblem.UNAVAILABLE)
        val path = listed.ledgerPath(account)
            ?: return@withTimeoutOrNull Signing.LedgerFailed(LedgerProblem.WRONG_ACCOUNT)
        ensureActive()
        if (notAfter.hasPassedNow()) return@withTimeoutOrNull Signing.TimedOut
        val result = operation(path)
        ensureActive()
        if (notAfter.hasPassedNow()) return@withTimeoutOrNull Signing.TimedOut
        val signature = when (result) {
            is LedgerResult.Ok -> result.value
            is LedgerResult.Failed -> return@withTimeoutOrNull Signing.LedgerFailed(result.problem)
        }
        val recovered = recovery.recover(digest(), signature)
            ?: return@withTimeoutOrNull Signing.LedgerFailed(LedgerProblem.INVALID_RESPONSE)
        if (recovered != account.address) return@withTimeoutOrNull Signing.LedgerFailed(LedgerProblem.WRONG_ACCOUNT)
        val current = accounts.observeAccounts().first() as? AccountList.Loaded
            ?: return@withTimeoutOrNull Signing.LedgerFailed(LedgerProblem.UNAVAILABLE)
        if (current.ledgerPath(account) != path) return@withTimeoutOrNull Signing.LedgerFailed(LedgerProblem.WRONG_ACCOUNT)
        ensureActive()
        if (notAfter.hasPassedNow()) Signing.TimedOut else Signing.Signed(signature)
    } ?: Signing.TimedOut

    private fun AccountList.Loaded.find(id: AccountId): Account? =
        accounts.firstOrNull { it.network == id.network && it.address == id.address }

    private fun AccountList.Loaded.ledgerPath(id: AccountId): LedgerPath? =
        (find(id)?.takeIf { it.kind == AccountKind.EOA }?.custody as? AccountCustody.Ledger)?.path
}
