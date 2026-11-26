package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.supportsLedger
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.LedgerDevice
import eth.serotine.domain.repository.LedgerProblem
import eth.serotine.domain.repository.LedgerResult
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.SignatureRecovery
import kotlin.time.TimeMark
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Signs only a reviewed native ETH transfer from an enrolled Ledger account. Checks the signer of the exact
 * transaction before handing it to the same broadcast and record step as a key-store transfer. [clock] is in unix
 * seconds.
 */
class SendLedgerTransfer(
    private val ledger: LedgerDevice,
    private val recovery: SignatureRecovery,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val accounts: AccountRepository,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    /** Requires on-device approval and a live [notAfter] deadline; cancellation before broadcasting sends nothing. */
    suspend operator fun invoke(quote: TransferQuote, path: LedgerPath, notAfter: TimeMark): LedgerResult<TransferSending> {
        val context = currentCoroutineContext()
        context.ensureActive()
        val transaction = quote.transaction
        if (!quote.network.supportsLedger || quote.asset != TransferAsset.Native ||
            transaction.chainId != quote.network.chainId || transaction.hasCallData ||
            transaction.to != quote.to || transaction.value != quote.amount ||
            quote.amount.isZero || quote.to == Address.ZERO
        ) return LedgerResult.Failed(LedgerProblem.UNSUPPORTED_TRANSACTION)

        if (notAfter.hasPassedNow()) return LedgerResult.Failed(LedgerProblem.TIMED_OUT)
        enrollmentProblem(quote, path)?.let { return LedgerResult.Failed(it) }
        context.ensureActive()
        val result = withTimeoutOrNull(-notAfter.elapsedNow()) {
            ledger.signTransaction(path, quote.from, transaction)
        } ?: return LedgerResult.Failed(LedgerProblem.TIMED_OUT)
        context.ensureActive()
        val signature = when (result) {
            is LedgerResult.Ok -> result.value
            is LedgerResult.Failed -> return result
        }
        if (notAfter.hasPassedNow()) return LedgerResult.Failed(LedgerProblem.TIMED_OUT)

        // Signature already enforces parity, nonzero scalars, and the EIP-2 low-s bound.
        val recovered = recovery.recover(transaction.signingDigest(), signature)
            ?: return LedgerResult.Failed(LedgerProblem.INVALID_RESPONSE)
        if (recovered != quote.from) return LedgerResult.Failed(LedgerProblem.WRONG_ACCOUNT)
        val transfer = quote.call.signedTransfer(
            quote.network,
            signature,
            SentAction.Transfer(quote.to, quote.asset, quote.amount),
            clock,
        )
        enrollmentProblem(quote, path)?.let { return LedgerResult.Failed(it) }
        context.ensureActive()
        if (notAfter.hasPassedNow()) return LedgerResult.Failed(LedgerProblem.TIMED_OUT)
        return LedgerResult.Ok(broadcastAndRecord(broadcaster, sentTransfers, transfer, clock))
    }

    private suspend fun enrollmentProblem(quote: TransferQuote, path: LedgerPath): LedgerProblem? {
        val listed = accounts.observeAccounts().first() as? AccountList.Loaded ?: return LedgerProblem.UNAVAILABLE
        val account = listed.accounts.firstOrNull { it.network == quote.network && it.address == quote.from }
        return if (account?.kind == AccountKind.EOA && (account.custody as? AccountCustody.Ledger)?.path == path) {
            null
        } else {
            LedgerProblem.WRONG_ACCOUNT
        }
    }
}
