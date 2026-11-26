package eth.serotine.data.ledger

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.Signature
import eth.serotine.domain.repository.LedgerProblem
import fr.acinq.secp256k1.Secp256k1
import fr.acinq.secp256k1.Secp256k1Exception
import org.kotlincrypto.hash.sha3.Keccak256

/** Only GET ETH PUBLIC ADDRESS and the plain EIP-1559 form of SIGN ETH TRANSACTION (LedgerHQ/app-ethereum/doc/apdu.md). */
internal object LedgerEthereum {
    private const val PUBLIC_KEY_SIZE = 65
    private const val ADDRESS_HEX_SIZE = 40
    private const val APDU_HEADER_SIZE = 5
    const val ADDRESS_RESPONSE_SIZE = 1 + PUBLIC_KEY_SIZE + 1 + ADDRESS_HEX_SIZE + STATUS_BYTES
    const val SIGNATURE_RESPONSE_SIZE = 1 + 32 + 32 + STATUS_BYTES

    fun addressCommand(path: LedgerPath, display: Boolean): ByteArray =
        command(0x02, if (display) 0x01 else 0x00, path)

    /** A plain transfer (fixed recipient, empty calldata and access list) fits in a single short APDU. */
    fun signCommand(path: LedgerPath, unsigned: ByteArray): ByteArray = command(0x04, 0x00, path, unsigned)

    fun parseAddress(response: ByteArray): Address {
        requireSuccess(response, ADDRESS_RESPONSE_SIZE)
        val addressLengthOffset = 1 + PUBLIC_KEY_SIZE
        if (response.u8(0) != PUBLIC_KEY_SIZE || response.u8(1) != 0x04 ||
            response.u8(addressLengthOffset) != ADDRESS_HEX_SIZE
        ) {
            throw LedgerFailure(LedgerProblem.INVALID_RESPONSE)
        }
        val address = Address.parse("0x" + response.decodeToString(addressLengthOffset + 1, response.size - STATUS_BYTES))
            ?: throw LedgerFailure(LedgerProblem.INVALID_RESPONSE)
        val publicKey = try {
            Secp256k1.pubkeyParse(response.copyOfRange(1, addressLengthOffset))
        } catch (_: Secp256k1Exception) {
            throw LedgerFailure(LedgerProblem.INVALID_RESPONSE)
        }
        val hash = Keccak256().apply { update(publicKey, 1, PUBLIC_KEY_SIZE - 1) }.digest()
        if (address.hex != "0x" + hash.toHexString(12, 32)) throw LedgerFailure(LedgerProblem.INVALID_RESPONSE)
        return address
    }

    fun parseSignature(response: ByteArray): Signature {
        requireSuccess(response, SIGNATURE_RESPONSE_SIZE)
        // The Ethereum app returns 0 or 1 for typed transactions, not legacy/EIP-155 v.
        return try {
            Signature(response.toHexString(1, 33), response.toHexString(33, 65), response.u8(0))
        } catch (_: IllegalArgumentException) {
            throw LedgerFailure(LedgerProblem.INVALID_RESPONSE)
        }
    }

    private fun requireSuccess(response: ByteArray, expectedSize: Int) {
        if (response.size < STATUS_BYTES) throw LedgerFailure(LedgerProblem.INVALID_RESPONSE)
        val status = response.uint16(response.size - STATUS_BYTES)
        if (status == 0x9000 && response.size == expectedSize) return
        val problem = if (response.size == STATUS_BYTES) {
            when (status) {
                0x6985 -> LedgerProblem.REJECTED
                0x5515, 0x6d00, 0x6e00, 0x6e01 -> LedgerProblem.OPEN_ETHEREUM
                0x6401 -> LedgerProblem.TIMED_OUT
                else -> LedgerProblem.INVALID_RESPONSE
            }
        } else {
            LedgerProblem.INVALID_RESPONSE
        }
        throw LedgerFailure(problem)
    }

    private fun command(ins: Int, p1: Int, path: LedgerPath, unsigned: ByteArray? = null): ByteArray {
        val components = path.value.count { it == '/' }
        val length = 1 + 4 * components + (unsigned?.size ?: 0)
        require(length <= 255)
        return ByteArray(APDU_HEADER_SIZE + length).apply {
            this[0] = 0xe0.toByte()
            this[1] = ins.toByte()
            this[2] = p1.toByte()
            this[4] = length.toByte()
            this[5] = components.toByte()
            var offset = 6
            var start = 2 // The canonical path starts with m/. Components were validated by LedgerPath.
            repeat(components) {
                val end = path.value.indexOf('/', start).let { if (it == -1) path.value.length else it }
                val hardened = path.value[end - 1] == '\''
                var index = 0
                val digitsEnd = if (hardened) end - 1 else end
                for (i in start until digitsEnd) index = index * 10 + (path.value[i] - '0')
                if (hardened) index = index or Int.MIN_VALUE
                for (shift in 24 downTo 0 step 8) this[offset++] = (index ushr shift).toByte()
                start = end + 1
            }
            unsigned?.copyInto(this, offset)
        }
    }
}
