package eth.serotine.data.ledger

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Eip712Hashes
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.Signature
import eth.serotine.domain.repository.LedgerProblem
import fr.acinq.secp256k1.Secp256k1
import fr.acinq.secp256k1.Secp256k1Exception
import org.kotlincrypto.hash.sha3.Keccak256

/** Ethereum app commands from LedgerHQ/app-ethereum's `doc/apdu.md`, with no external metadata. */
internal object LedgerEthereum {
    private const val PUBLIC_KEY_SIZE = 65
    private const val ADDRESS_HEX_SIZE = 40
    private const val APDU_HEADER_SIZE = 5
    private const val MAX_CHUNK = 255
    private const val QUERY_TIMEOUT = 5_000
    private const val APPROVAL_TIMEOUT = 120_000
    private const val ADDRESS_RESPONSE_SIZE = 1 + PUBLIC_KEY_SIZE + 1 + ADDRESS_HEX_SIZE + STATUS_BYTES
    private const val SIGNATURE_RESPONSE_SIZE = 1 + 32 + 32 + STATUS_BYTES
    private const val CONFIG_RESPONSE_SIZE = 4 + STATUS_BYTES
    private const val SIGN_TRANSACTION = 0x04
    private const val SIGN_PERSONAL_MESSAGE = 0x08

    fun readAddress(exchange: LedgerHidExchange, path: LedgerPath, display: Boolean): Address {
        val components = path.components()
        val command = command(0x02, if (display) 0x01 else 0x00, pathLength(components))
        command.putPath(path, components)
        val reply = exchange.exchange(command, ADDRESS_RESPONSE_SIZE, if (display) APPROVAL_TIMEOUT else QUERY_TIMEOUT)
        return parseAddress(reply)
    }

    fun signTransaction(exchange: LedgerHidExchange, path: LedgerPath, transaction: Eip1559Transaction): Signature {
        // With the setting off, the device can still clear-sign what it recognises. Do not preempt its decision.
        val blindSigningDisabled = transaction.hasCallData && !blindSigningEnabled(exchange)
        val reply = stream(
            exchange, SIGN_TRANSACTION, path, transaction.encodeUnsigned(), blindSigningDisabled = blindSigningDisabled,
        )
        return parseSignature(reply, blindSigningDisabled = blindSigningDisabled)
    }

    fun signTypedData(exchange: LedgerHidExchange, path: LedgerPath, hashes: Eip712Hashes): Signature {
        // V0 firmware always requires Blind signing. Do not change the setting or attempt another signing mode.
        if (!blindSigningEnabled(exchange)) throw LedgerFailure(LedgerProblem.BLIND_SIGNING_DISABLED)
        val components = path.components()
        val prefix = pathLength(components)
        val command = command(0x0c, 0x00, prefix + 64) // P2 = 00: V0, two 32-byte hashes (never the final digest).
        command.putPath(path, components)
        command.putDigest(APDU_HEADER_SIZE + prefix, hashes.domainSeparator)
        command.putDigest(APDU_HEADER_SIZE + prefix + 32, hashes.structHash)
        return parseSignature(exchange.exchange(command, SIGNATURE_RESPONSE_SIZE, APPROVAL_TIMEOUT), vBase = 27)
    }

    fun signPersonalMessage(exchange: LedgerHidExchange, path: LedgerPath, message: PersonalMessage): Signature {
        val reply = stream(exchange, SIGN_PERSONAL_MESSAGE, path, message.encode())
        return parseSignature(reply, vBase = 27)
    }

    private fun blindSigningEnabled(exchange: LedgerHidExchange): Boolean {
        val reply = exchange.exchange(command(0x06, 0x00, 0), CONFIG_RESPONSE_SIZE, QUERY_TIMEOUT)
        requireSuccess(reply, CONFIG_RESPONSE_SIZE)
        return reply.u8(0) and 0x01 != 0
    }

    private fun stream(
        exchange: LedgerHidExchange,
        ins: Int,
        path: LedgerPath,
        payload: ByteArray,
        blindSigningDisabled: Boolean = false,
    ): ByteArray {
        val components = path.components()
        val pathSize = pathLength(components)
        val message = ins == SIGN_PERSONAL_MESSAGE
        val prefix = pathSize + if (message) 4 else 0
        var prefixSize = prefix
        var apdu = command(ins, 0x00, prefix + minOf(payload.size, MAX_CHUNK - prefix))
        apdu.putPath(path, components)
        if (message) apdu.put32(APDU_HEADER_SIZE + pathSize, payload.size)
        var offset = 0
        while (true) {
            val count = apdu.size - APDU_HEADER_SIZE - prefixSize
            payload.copyInto(apdu, APDU_HEADER_SIZE + prefixSize, offset, offset + count)
            offset += count
            if (offset == payload.size) return exchange.exchange(apdu, SIGNATURE_RESPONSE_SIZE, APPROVAL_TIMEOUT)
            // Debug Data on the device can require approval of intermediate transaction chunks as well.
            val reply = exchange.exchange(apdu, STATUS_BYTES, if (message) QUERY_TIMEOUT else APPROVAL_TIMEOUT)
            requireSuccess(reply, STATUS_BYTES, blindSigningDisabled)
            prefixSize = 0
            val nextSize = minOf(payload.size - offset, MAX_CHUNK)
            if (apdu.size == APDU_HEADER_SIZE + nextSize) {
                apdu[2] = 0x80.toByte()
            } else {
                apdu = command(ins, 0x80, nextSize)
            }
        }
    }

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

    fun parseSignature(response: ByteArray, vBase: Int = 0, blindSigningDisabled: Boolean = false): Signature {
        requireSuccess(response, SIGNATURE_RESPONSE_SIZE, blindSigningDisabled)
        // Firmware v is 0/1 for type 2 transactions and 27/28 for EIP-191/712.
        return try {
            Signature(response.toHexString(1, 33), response.toHexString(33, 65), response.u8(0) - vBase)
        } catch (_: IllegalArgumentException) {
            throw LedgerFailure(LedgerProblem.INVALID_RESPONSE)
        }
    }

    private fun requireSuccess(response: ByteArray, expectedSize: Int, blindSigningDisabled: Boolean = false) {
        if (response.size < STATUS_BYTES) throw LedgerFailure(LedgerProblem.INVALID_RESPONSE)
        val status = response.uint16(response.size - STATUS_BYTES)
        if (status == 0x9000 && response.size == expectedSize) return
        val problem = if (response.size == STATUS_BYTES) {
            when (status) {
                0x6985 -> LedgerProblem.REJECTED
                0x5515, 0x6d00, 0x6e00, 0x6e01 -> LedgerProblem.OPEN_ETHEREUM
                0x6401 -> LedgerProblem.TIMED_OUT
                // 6A80 is also a parse error: only offer the setting when the device told us it was off.
                0x6a80 -> if (blindSigningDisabled) {
                    LedgerProblem.BLIND_SIGNING_DISABLED
                } else {
                    LedgerProblem.INVALID_RESPONSE
                }
                else -> LedgerProblem.INVALID_RESPONSE
            }
        } else {
            LedgerProblem.INVALID_RESPONSE
        }
        throw LedgerFailure(problem)
    }

    private fun command(ins: Int, p1: Int, length: Int): ByteArray = ByteArray(APDU_HEADER_SIZE + length).apply {
        this[0] = 0xe0.toByte()
        this[1] = ins.toByte()
        this[2] = p1.toByte()
        // P2 is 00 for all of these operations: no chain code, process & start flow, or EIP-712 V0.
        this[4] = length.toByte()
    }

    private fun LedgerPath.components(): Int = value.count { it == '/' }

    private fun pathLength(components: Int): Int = 1 + 4 * components

    private fun ByteArray.putPath(path: LedgerPath, components: Int) {
        this[APDU_HEADER_SIZE] = components.toByte()
        var offset = APDU_HEADER_SIZE + 1
        var start = 2 // The canonical path starts with m/. Components were validated by LedgerPath.
        repeat(components) {
            val end = path.value.indexOf('/', start).let { if (it == -1) path.value.length else it }
            val hardened = path.value[end - 1] == '\''
            var index = 0
            val digitsEnd = if (hardened) end - 1 else end
            for (i in start until digitsEnd) index = index * 10 + (path.value[i] - '0')
            if (hardened) index = index or Int.MIN_VALUE
            put32(offset, index)
            offset += 4
            start = end + 1
        }
    }

    private fun ByteArray.put32(offset: Int, value: Int) {
        for (i in 0 until 4) this[offset + i] = (value ushr (24 - 8 * i)).toByte()
    }

    private fun ByteArray.putDigest(offset: Int, digest: Digest) {
        val hex = digest.hex
        for (i in hex.indices step 2) {
            this[offset + i / 2] = (hex[i].digitToInt(16) shl 4 or hex[i + 1].digitToInt(16)).toByte()
        }
    }
}
