package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Eip712Hashes
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.Signature

/** A locally connected Ledger's Ethereum app, with no bare-digest signing operation. */
interface LedgerDevice {
    /** Reads public addresses in [paths] order, without prompting. This never confirms enrollment. */
    suspend fun readAddresses(paths: List<LedgerPath>): LedgerResult<List<Address>> =
        LedgerResult.Failed(LedgerProblem.UNAVAILABLE)

    /** Returns the address at [path] only after the user confirms it on the device. */
    suspend fun confirmAddress(path: LedgerPath): LedgerResult<Address>

    /** Confirms [expectedAddress] at [path] and asks the device to sign the exact [transaction]. */
    suspend fun signTransaction(
        path: LedgerPath,
        expectedAddress: Address,
        transaction: Eip1559Transaction,
    ): LedgerResult<Signature>

    /** Confirms [expectedAddress] at [path] and asks the device to sign the EIP-712 [hashes]. */
    suspend fun signTypedData(
        path: LedgerPath,
        expectedAddress: Address,
        hashes: Eip712Hashes,
    ): LedgerResult<Signature> = LedgerResult.Failed(LedgerProblem.UNSUPPORTED_TRANSACTION)

    /** Confirms [expectedAddress] at [path] and asks the device to sign the literal [message] with EIP-191. */
    suspend fun signPersonalMessage(
        path: LedgerPath,
        expectedAddress: Address,
        message: PersonalMessage,
    ): LedgerResult<Signature> = LedgerResult.Failed(LedgerProblem.UNSUPPORTED_TRANSACTION)
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
    BLIND_SIGNING_DISABLED,
    UNSUPPORTED_TRANSACTION,
}
