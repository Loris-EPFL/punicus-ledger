package eth.serotine.data.ledger

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.supportsLedger
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

/** A Ledger Ethereum app on USB HID. No seed, message signing, network calls, or blind-sign fallback. */
class UsbLedgerDevice internal constructor(private val open: () -> LedgerReports) : LedgerDevice {
    constructor() : this(HidApi::open)

    override suspend fun confirmAddress(path: LedgerPath): LedgerResult<Address> = withDevice {
        val reply = exchange(
            LedgerEthereum.addressCommand(path, display = true),
            LedgerEthereum.ADDRESS_RESPONSE_SIZE,
            APPROVAL_TIMEOUT,
        )
        LedgerEthereum.parseAddress(reply)
    }

    override suspend fun signTransaction(
        path: LedgerPath,
        expectedAddress: Address,
        transaction: Eip1559Transaction,
    ): LedgerResult<Signature> {
        if (Network.ofChainId(transaction.chainId)?.supportsLedger != true || transaction.hasCallData ||
            transaction.value.isZero || transaction.to == Address.ZERO
        ) {
            return LedgerResult.Failed(LedgerProblem.UNSUPPORTED_TRANSACTION)
        }
        return withDevice {
            // Both commands use the same open handle. The address was displayed at enrollment;
            // only the transaction needs another approval, and only after the sender matches.
            val addressReply = exchange(
                LedgerEthereum.addressCommand(path, display = false),
                LedgerEthereum.ADDRESS_RESPONSE_SIZE,
                QUERY_TIMEOUT,
            )
            if (LedgerEthereum.parseAddress(addressReply) != expectedAddress) {
                throw LedgerFailure(LedgerProblem.WRONG_ACCOUNT)
            }
            val signatureReply = exchange(
                LedgerEthereum.signCommand(path, transaction.encodeUnsigned()),
                LedgerEthereum.SIGNATURE_RESPONSE_SIZE,
                APPROVAL_TIMEOUT,
            )
            LedgerEthereum.parseSignature(signatureReply)
        }
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
        const val QUERY_TIMEOUT = 5_000
        const val APPROVAL_TIMEOUT = 120_000
    }
}
