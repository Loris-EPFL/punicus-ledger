package eth.serotine.data.keys

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.PerAppPath
import fr.acinq.secp256k1.Secp256k1
import org.kotlincrypto.hash.sha3.Keccak256

/**
 * BIP-44 `m/44'/60'/0'/0/[index]`: purpose 44, coin type 60 (Ether), account 0, external chain,
 * address [index] — the path other wallets list a phrase's accounts along, the first at index 0.
 */
internal fun ethereumAccount(index: Int): IntArray {
    require(index >= 0) { "a non-hardened index" }
    return intArrayOf(44 or Bip32.HARDENED, 60 or Bip32.HARDENED, 0 or Bip32.HARDENED, 0, index)
}

/**
 * A per-app address's path (docs/keys-and-signing.md, *Per-app addresses*): `m/44'/60'/a'/b'/c'`, every level
 * hardened, [PerAppPath] being in range by construction.
 */
internal fun perAppAccount(path: PerAppPath): IntArray = intArrayOf(
    44 or Bip32.HARDENED,
    60 or Bip32.HARDENED,
    path.account or Bip32.HARDENED,
    path.change or Bip32.HARDENED,
    path.index or Bip32.HARDENED,
)

/**
 * Where in a phrase's seed an envelope's account is: one of its ordinary accounts ([Ordinary], [ethereumAccount])
 * or a per-app address ([PerApp], [perAppAccount]).
 */
internal sealed interface AccountPath {

    /** The BIP-32 path below the seed's master key. */
    val bip32: IntArray

    /** Account [index] of the phrase, `m/44'/60'/0'/0/index`. */
    data class Ordinary(val index: Int) : AccountPath {
        init {
            require(index >= 0) { "a non-hardened index" }
        }

        override val bip32: IntArray get() = ethereumAccount(index)
    }

    /** The per-app address at [path]. */
    data class PerApp(val path: PerAppPath) : AccountPath {
        override val bip32: IntArray get() = perAppAccount(path)
    }
}

/** The Ethereum address of [privateKey]: the last 20 bytes of Keccak-256 over the public key's X || Y. */
internal fun addressOf(privateKey: ByteArray): Address {
    val publicKey = Secp256k1.pubkeyCreate(privateKey) // 0x04 || X || Y
    val hash = Keccak256().digest(publicKey.copyOfRange(1, 65))
    return Address.of("0x" + hash.copyOfRange(12, 32).toHex())
}

/** The first account's address of a BIP-39 [seed]; the derived key is zeroed before returning. */
internal fun firstAddressOf(seed: ByteArray): Address = addressOf(seed, index = 0)

/**
 * The address of account [index] of a BIP-39 [seed] ([ethereumAccount]);
 * the derived key is zeroed before returning.
 */
internal fun addressOf(seed: ByteArray, index: Int): Address = addressOf(seed, AccountPath.Ordinary(index))

/** The address at [path] of a BIP-39 [seed]; the derived key is zeroed before returning. */
internal fun addressOf(seed: ByteArray, path: AccountPath): Address {
    val key = Bip32.derive(seed, path.bip32)
    try {
        return addressOf(key)
    } finally {
        key.fill(0)
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

internal fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        append(HEX_DIGITS[(byte.toInt() shr 4) and 0x0f])
        append(HEX_DIGITS[byte.toInt() and 0x0f])
    }
}

/** The bytes [this] hex string spells; throws on anything else. */
internal fun String.fromHex(): ByteArray {
    require(length % 2 == 0) { "odd-length hex" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
