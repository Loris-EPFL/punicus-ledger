package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.LedgerDevice
import eth.serotine.domain.repository.LedgerProblem
import eth.serotine.domain.repository.LedgerResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/** The public address and derivation path returned by a successful on-device confirmation. */
class ConfirmedLedgerAccount internal constructor(val path: LedgerPath, val address: Address)

/** Enrolls an address confirmed on a Ledger. Nothing is imported into the wallet's key store. */
class EnrollLedgerAccount(
    private val ledger: LedgerDevice,
    private val accounts: AccountRepository,
    private val changes: WalletChanges,
) {
    /** Reads addresses to help the user choose a path. Only [confirm] can produce an enrollment. */
    suspend fun browse(paths: List<LedgerPath>): LedgerResult<List<Address>> =
        when (val result = ledger.readAddresses(paths)) {
            is LedgerResult.Failed -> result
            is LedgerResult.Ok -> if (result.value.size == paths.size && Address.ZERO !in result.value) result
            else LedgerResult.Failed(LedgerProblem.INVALID_RESPONSE)
        }

    /** Confirms the address on-device; if it was browsed first, it must match [expectedAddress]. */
    suspend fun confirm(path: LedgerPath, expectedAddress: Address? = null): LedgerResult<ConfirmedLedgerAccount> {
        currentCoroutineContext().ensureActive()
        val result = ledger.confirmAddress(path)
        currentCoroutineContext().ensureActive()
        return when (result) {
            is LedgerResult.Failed -> result
            is LedgerResult.Ok -> when {
                result.value == Address.ZERO -> LedgerResult.Failed(LedgerProblem.INVALID_RESPONSE)
                expectedAddress != null && result.value != expectedAddress ->
                    LedgerResult.Failed(LedgerProblem.WRONG_ACCOUNT)
                else -> LedgerResult.Ok(ConfirmedLedgerAccount(path, result.value))
            }
        }
    }

    /** Adds the confirmed address on a supported network, without changing an existing entry. */
    suspend fun add(network: Network, confirmed: ConfirmedLedgerAccount): AccountAdd {
        return changes.serialized(keys = false) {
            val listed = accounts.observeAccounts().first() as? AccountList.Loaded
                ?: return@serialized AccountAdd.NotSaved
            accounts.add(
                Account(
                    network = network,
                    address = confirmed.address,
                    kind = AccountKind.EOA,
                    label = nextFreeLabel(listed.accounts),
                    custody = AccountCustody.Ledger(confirmed.path),
                ),
            )
        }
    }
}
