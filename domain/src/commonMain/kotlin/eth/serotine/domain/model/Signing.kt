package eth.serotine.domain.model

import kotlin.jvm.JvmInline

/**
 * A 32-byte hash to be signed — a transaction's signing hash, a Safe transaction's hash — as 64
 * lower-case hex digits, validated at construction. What it is the hash of is the caller's: a
 * [Digest] is signed as given, never hashed again.
 */
@JvmInline
value class Digest private constructor(val hex: String) {

    override fun toString(): String = "Digest($hex)"

    companion object {
        private val SHAPE = Regex("^[0-9a-f]{64}$")

        /** [hex] (with or without `0x` or `0X`, any case) as a digest, or null when it is not 32 bytes of hex. */
        fun parse(hex: String): Digest? = hex.lowercase().removePrefix("0x").takeIf(SHAPE::matches)?.let(::Digest)

        /** [parse], throwing on bad input. */
        fun of(hex: String): Digest = requireNotNull(parse(hex)) { "not a 32-byte digest: '$hex'" }

        internal fun fromBytes(bytes: ByteArray): Digest {
            require(bytes.size == 32) { "a digest is 32 bytes" }
            return Digest(bytes.toHex())
        }
    }
}

/** The two 32-byte EIP-712 hashes the device binds into one signature, not a caller-supplied digest. */
class Eip712Hashes private constructor(
    val domainSeparator: Digest,
    val structHash: Digest,
    /** `keccak256(0x19 ‖ 0x01 ‖ domainSeparator ‖ structHash)`. */
    val digest: Digest,
) {
    constructor(domainSeparator: Digest, structHash: Digest) : this(
        domainSeparator,
        structHash,
        signedDigest(domainSeparator.hex.hexToBytes(), structHash.hex.hexToBytes()),
    )

    override fun equals(other: Any?): Boolean =
        other is Eip712Hashes && domainSeparator == other.domainSeparator && structHash == other.structHash

    override fun hashCode(): Int = 31 * domainSeparator.hashCode() + structHash.hashCode()

    override fun toString(): String = "Eip712Hashes(domainSeparator=$domainSeparator, structHash=$structHash)"

    companion object {
        internal fun fromBytes(domainSeparator: ByteArray, structHash: ByteArray): Eip712Hashes =
            Eip712Hashes(
                Digest.fromBytes(domainSeparator),
                Digest.fromBytes(structHash),
                signedDigest(domainSeparator, structHash),
            )

        private fun signedDigest(domainSeparator: ByteArray, structHash: ByteArray): Digest {
            val preimage = ByteArray(66)
            preimage[0] = 0x19
            preimage[1] = 0x01
            domainSeparator.copyInto(preimage, destinationOffset = 2)
            structHash.copyInto(preimage, destinationOffset = 34)
            return Digest.fromBytes(keccak256(preimage))
        }
    }
}

/**
 * A secp256k1 ECDSA signature: [r] and [s] as 64 lower-case hex digits each, [r] in 1..n-1 and
 * [s] in the lower half of the curve order n (as Ethereum requires), and the [recoveryId] (0 or
 * 1) that recovers the signer's public key — EIP-1559's `yParity`, or a Safe signature's `v` less
 * 27. All of it is checked at construction, so a signature decoded from a co-signer's QR code
 * holds the same promises as one this wallet made.
 */
data class Signature(val r: String, val s: String, val recoveryId: Int) {
    init {
        require(SCALAR.matches(r) && SCALAR.matches(s)) { "r and s are 32 bytes each, in lower-case hex" }
        // Equal lengths of lower-case hex: the strings order as the numbers do.
        require(r != ZERO && r < ORDER) { "r is in 1..n-1" }
        require(s != ZERO && s <= HALF_ORDER) { "s is in the lower half of the curve order" }
        require(recoveryId in 0..1) { "a recovery id is 0 or 1" }
    }

    private companion object {
        val SCALAR = Regex("^[0-9a-f]{64}$")
        val ZERO = "0".repeat(64)
        const val ORDER = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"
        const val HALF_ORDER = "7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0"
    }
}
