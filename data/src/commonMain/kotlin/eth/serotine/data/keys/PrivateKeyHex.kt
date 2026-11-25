package eth.serotine.data.keys

import eth.serotine.domain.model.PrivateKey
import eth.serotine.domain.model.PrivateKeyProblem
import fr.acinq.secp256k1.Secp256k1

/** What [readPrivateKey] made of a typed key: its 32 bytes, for the caller to zero, or why it is none. */
internal sealed interface ReadKey {
    class Key(val bytes: ByteArray) : ReadKey

    data class Invalid(val problem: PrivateKeyProblem) : ReadKey
}

/**
 * [key] read as a secp256k1 private key: its text trimmed, an optional `0x` or `0X` dropped, then exactly
 * [PrivateKey.DIGITS] hex digits of either case, whose value is not zero and is below the curve's order (libsecp256k1's
 * `secKeyVerify`). Nothing of the key is logged or kept; the bytes returned are the caller's to zero.
 */
internal fun readPrivateKey(key: PrivateKey): ReadKey {
    val text = key.text.trim().let { if (it.startsWith("0x") || it.startsWith("0X")) it.drop(2) else it }
    val notHex = text.indexOfFirst { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }
    if (notHex >= 0) return ReadKey.Invalid(PrivateKeyProblem.NotHex(notHex + 1))
    if (text.length != PrivateKey.DIGITS) return ReadKey.Invalid(PrivateKeyProblem.Length(text.length))
    val bytes = ByteArray(PrivateKey.DIGITS / 2) { i -> text.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    val valid = try {
        Secp256k1.secKeyVerify(bytes)
    } catch (e: Throwable) {
        bytes.fill(0)
        throw e
    }
    if (!valid) {
        bytes.fill(0)
        return ReadKey.Invalid(PrivateKeyProblem.OutOfRange)
    }
    return ReadKey.Key(bytes)
}
