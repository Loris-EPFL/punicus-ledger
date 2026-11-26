package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.Signature

/** A locally connected Ledger's Ethereum app. Only transaction and address confirmation, never digest signing. */
interface LedgerDevice {
    /** Returns the address at [path] only after the user confirms it on the device. */
    suspend fun confirmAddress(path: LedgerPath): LedgerResult<Address>

    /** Confirms the address matches [expectedAddress] and asks the device to display and sign [transaction]. */
    suspend fun signTransaction(
        path: LedgerPath,
        expectedAddress: Address,
        transaction: Eip1559Transaction,
    ): LedgerResult<Signature>
}

/** A Ledger operation's value, or why it did not complete. Coroutine cancellation is not a device failure. */
sealed interface LedgerResult<out T> {
    /** The device operation completed, yielding [value]. */
    data class Ok<T>(val value: T) : LedgerResult<T>

    /** The operation did not complete, for [problem]. */
    data class Failed(val problem: LedgerProblem) : LedgerResult<Nothing>
}

/** Expected refusals when using a Ledger or its Ethereum app. */
enum class LedgerProblem {
    NOT_CONNECTED,
    MULTIPLE_DEVICES,
    ACCESS_DENIED,
    UNAVAILABLE,
    REJECTED,
    OPEN_ETHEREUM,
    TIMED_OUT,
    INVALID_RESPONSE,
    WRONG_ACCOUNT,
    UNSUPPORTED_TRANSACTION,
}
