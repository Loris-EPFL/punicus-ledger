package eth.serotine.data.ledger

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Eip712Hashes
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.Signature
import eth.serotine.domain.repository.LedgerDevice
import eth.serotine.domain.repository.LedgerProblem
import eth.serotine.domain.repository.LedgerResult
import java.util.concurrent.ThreadLocalRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A Ledger Ethereum app on USB HID; no private keys, network calls, or fallback signing commands. */
class UsbLedgerDevice internal constructor(private val open: () -> LedgerReports) : LedgerDevice {
    constructor() : this(HidApi::open)

    override suspend fun confirmAddress(path: LedgerPath): LedgerResult<Address> = withDevice {
        LedgerEthereum.readAddress(this, path, display = true)
    }

    override suspend fun signTransaction(
        path: LedgerPath,
        expectedAddress: Address,
        transaction: Eip1559Transaction,
    ): LedgerResult<Signature> {
        if (Network.ofChainId(transaction.chainId) == null) {
            return LedgerResult.Failed(LedgerProblem.UNSUPPORTED_TRANSACTION)
        }
        return withSigner(path, expectedAddress) { LedgerEthereum.signTransaction(this, path, transaction) }
    }

    override suspend fun signTypedData(
        path: LedgerPath,
        expectedAddress: Address,
        hashes: Eip712Hashes,
    ): LedgerResult<Signature> =
        withSigner(path, expectedAddress) { LedgerEthereum.signTypedData(this, path, hashes) }

    override suspend fun signPersonalMessage(
        path: LedgerPath,
        expectedAddress: Address,
        message: PersonalMessage,
    ): LedgerResult<Signature> =
        withSigner(path, expectedAddress) { LedgerEthereum.signPersonalMessage(this, path, message) }

    private suspend fun withSigner(
        path: LedgerPath,
        expectedAddress: Address,
        sign: LedgerHidExchange.() -> Signature,
    ): LedgerResult<Signature> = withDevice {
        // The address was displayed at enrollment. Match it again on the same handle before signing.
        if (LedgerEthereum.readAddress(this, path, display = false) != expectedAddress) {
            throw LedgerFailure(LedgerProblem.WRONG_ACCOUNT)
        }
        sign()
    }

    private suspend fun <T> withDevice(block: LedgerHidExchange.() -> T): LedgerResult<T> = usbMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                ensureActive()
                open().use { reports ->
                    ensureActive()
                    // As in LedgerJS, responses echo a per-handle channel. Closing the OS handle
                    // need not dismiss a pending review on the Ledger itself.
                    val channel = ThreadLocalRandom.current().nextInt(1, 0x10000)
                    LedgerResult.Ok(LedgerHidExchange(reports, channel, coroutineContext.job).block())
                }
            } catch (failure: LedgerFailure) {
                LedgerResult.Failed(failure.problem)
            } catch (_: LinkageError) {
                LedgerResult.Failed(LedgerProblem.UNAVAILABLE)
            }
        }
    }

    private companion object {
        // HIDAPI has global state; only one enrollment or signing operation may own USB at a time.
        val usbMutex = Mutex()
    }
}
