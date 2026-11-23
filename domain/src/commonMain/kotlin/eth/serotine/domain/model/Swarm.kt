package eth.serotine.domain.model

import kotlin.jvm.JvmInline

/**
 * A Swarm postage batch's id: 32 bytes, as 64 lower-case hex digits, validated at construction.
 * The PostageStamp makes it `keccak256(abi.encode(buyer, nonce))`, the buyer being the account
 * that sent `createBatch` (docs/swarm.md, *Where the candidates come from*). Bee's API and its
 * dashboard show it with or without `0x`; both parse.
 */
@JvmInline
value class BatchId private constructor(val hex: String) {

    override fun toString(): String = "BatchId($hex)"

    companion object {
        private val SHAPE = Regex("^[0-9a-f]{64}$")

        /**
         * [text] (with or without `0x`, any case, spaces around it) as a batch id, or null when it
         * is not 32 bytes of hex.
         */
        fun parse(text: String): BatchId? =
            text.trim().lowercase().removePrefix("0x").takeIf(SHAPE::matches)?.let(::BatchId)

        /** [parse], throwing on bad input. */
        fun of(text: String): BatchId = requireNotNull(parse(text)) { "not a 32-byte batch id: '$text'" }

        /**
         * The id the PostageStamp gives the batch [buyer] creates with [nonce] (32 bytes):
         * `keccak256(abi.encode(buyer, nonce))`, worked out here so that a batch bought from this
         * wallet is found again (docs/swarm.md, *Where the candidates come from*).
         */
        fun of(buyer: Address, nonce: ByteArray): BatchId {
            require(nonce.size == 32) { "a batch nonce is 32 bytes" }
            return BatchId(keccak256(AbiWords.word(buyer) + nonce).toHex())
        }
    }
}

/**
 * A Swarm postage batch as the PostageStamp's `batches(id)` and `remainingBalance(id)` answer it
 * at one block, with that block's `lastPrice()` (docs/swarm.md, *What the Swarm screen shows*):
 * its [owner], its [depth] (it holds up to `2^depth` chunks) and [bucketDepth], whether it is
 * [immutable], and [remainingPerChunk], the balance per chunk it has left, in PLUR (xBZZ's
 * smallest unit, 10^-16 xBZZ). Zero has expired. [lastPrice] is the PLUR one chunk costs per
 * block at that block, which Swarm's price oracle sets.
 */
data class PostageBatch(
    val id: BatchId,
    val owner: Address,
    val depth: Int,
    val bucketDepth: Int,
    val immutable: Boolean,
    val remainingPerChunk: UInt256,
    val lastPrice: UInt256,
) {
    init {
        require(depth in 0..MAX_DEPTH && bucketDepth in 0..depth) {
            "a batch's depths are a uint8 each, the bucket's the lower"
        }
    }

    /** Whether the batch has run out: no balance is left per chunk. */
    val expired: Boolean
        get() = remainingPerChunk.isZero

    /**
     * Blocks the batch has left at [lastPrice], as Bee's `estimateBatchTTL` works it out: its
     * balance per chunk divided by the price; null when the price is zero (no time can be told).
     */
    val blocksLeft: UInt256?
        get() = if (lastPrice.isZero) null else remainingPerChunk.mulDivOrNull(UInt256.of(1), lastPrice)

    /**
     * Seconds the batch has left at [lastPrice], at Gnosis Chain's [BLOCK_SECONDS] a block; null
     * when no time can be told, or it does not fit a Long.
     */
    val secondsLeft: Long?
        get() = blocksLeft?.timesOrNull(UInt256.of(BLOCK_SECONDS))?.toLongOrNull()

    /** The most chunks the batch holds in theory: `2^depth`. Chunks fill its buckets unevenly, so fewer in practice. */
    val theoreticalChunks: UInt256
        get() = UInt256.of(1).shifted(depth)

    companion object {
        /** A uint8's largest value: the PostageStamp keeps depths as uint8. */
        const val MAX_DEPTH = 255

        /** Seconds a Gnosis Chain block takes, as Bee's `--block-time` assumes by default. */
        const val BLOCK_SECONDS = 5L

        /** Bytes one chunk holds. */
        const val CHUNK_BYTES = 4096L
    }
}

/** [this] times `2^bits`, or zero once it no longer fits 256 bits. */
private fun UInt256.shifted(bits: Int): UInt256 {
    var value = this
    repeat(bits) { value = value.timesOrNull(UInt256.of(2)) ?: return UInt256.of(0) }
    return value
}

/**
 * A Swarm node's chequebook (Bee's ERC20SimpleSwap) as its own getters answer them at one block
 * (docs/swarm.md, *What the Swarm screen shows*), all in PLUR: its [balance] of xBZZ, the
 * [liquidBalance] not hard-deposited to a peer (what the issuer may withdraw), and [totalPaidOut]
 * to peers so far; [bounced] once a cheque of it could not be paid in full, which it keeps for
 * good.
 */
data class Chequebook(
    val address: Address,
    val issuer: Address,
    val balance: UInt256,
    val liquidBalance: UInt256,
    val totalPaidOut: UInt256,
    val bounced: Boolean,
) {
    init {
        require(liquidBalance <= balance) { "a chequebook's liquid balance is part of its balance" }
    }
}
