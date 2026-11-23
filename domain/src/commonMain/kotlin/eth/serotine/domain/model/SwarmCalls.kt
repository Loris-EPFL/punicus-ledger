package eth.serotine.domain.model

/**
 * The PostageStamp calls a Swarm node's batches are bought, topped up and diluted with
 * (docs/swarm.md, *The four actions*), encoded here, where the use case can show what is signed,
 * and the figures they move. Each amount is in PLUR, xBZZ's smallest unit (10^-16 xBZZ).
 */
object SwarmCalls {

    /** The bucket depth a batch is bought with, as Bee buys. */
    const val BUCKET_DEPTH = 16

    /** The smallest depth offered: Bee refuses a batch of depth 16 or less. */
    const val MIN_DEPTH = 17

    /** The largest depth offered (docs/swarm.md, *Buy a batch*). */
    const val MAX_DEPTH = 30

    /**
     * `createBatch(address _owner, uint256 _initialBalancePerChunk, uint8 _depth, uint8 _bucketDepth,
     * bytes32 _nonce, bool _immutable)`: a batch [owner] owns, [perChunk] for each of its `2^depth`
     * chunks, bucket depth [BUCKET_DEPTH], made with [nonce] (32 bytes) by whoever sends it.
     */
    fun createBatch(owner: Address, perChunk: UInt256, depth: Int, nonce: ByteArray, immutable: Boolean): ByteArray {
        require(depth in MIN_DEPTH..MAX_DEPTH) { "a depth offered" }
        require(nonce.size == 32) { "a batch nonce is 32 bytes" }
        return CREATE_BATCH + AbiWords.word(owner) + perChunk.toBytes32() + AbiWords.word(depth.toLong()) +
            AbiWords.word(BUCKET_DEPTH.toLong()) + nonce + AbiWords.word(if (immutable) 1L else 0L)
    }

    /** `topUp(bytes32 _batchId, uint256 _topupAmountPerChunk)`: [perChunk] more for each chunk of [id]. */
    fun topUp(id: BatchId, perChunk: UInt256): ByteArray = TOP_UP + id.bytes() + perChunk.toBytes32()

    /** `increaseDepth(bytes32 _batchId, uint8 _newDepth)`: [id] diluted to [depth]. */
    fun increaseDepth(id: BatchId, depth: Int): ByteArray {
        require(depth in 0..PostageBatch.MAX_DEPTH) { "a depth is a uint8" }
        return INCREASE_DEPTH + id.bytes() + AbiWords.word(depth.toLong())
    }

    /** What [perChunk] for every one of a batch's `2^depth` chunks costs in all; null when it does not fit. */
    fun total(perChunk: UInt256, depth: Int): UInt256? {
        var total = perChunk
        repeat(depth) { total = total.timesOrNull(UInt256.of(2)) ?: return null }
        return total
    }

    /**
     * The balance per chunk that keeps a batch for [seconds] at [lastPrice] PLUR a chunk a block, in
     * Gnosis Chain's blocks of [PostageBatch.BLOCK_SECONDS], rounded up to a whole block; null when it
     * does not fit.
     */
    fun perChunkFor(seconds: Long, lastPrice: UInt256): UInt256? {
        require(seconds >= 0) { "a time is not negative" }
        val blocks = (seconds + PostageBatch.BLOCK_SECONDS - 1) / PostageBatch.BLOCK_SECONDS
        return lastPrice.timesOrNull(UInt256.of(blocks))
    }

    /**
     * What is left per chunk of a batch with [remaining] per chunk once diluted from [depth] to
     * [newDepth]: halved with each step, as the PostageStamp divides it.
     */
    fun remainingAfterDilution(remaining: UInt256, depth: Int, newDepth: Int): UInt256 {
        require(newDepth > depth) { "a dilution deepens the batch" }
        var left = remaining
        repeat(newDepth - depth) { left = left.mulDivOrNull(UInt256.of(1), UInt256.of(2)) ?: UInt256.ZERO }
        return left
    }

    internal val CREATE_BATCH = AbiWords.selector("createBatch(address,uint256,uint8,uint8,bytes32,bool)")
    internal val TOP_UP = AbiWords.selector("topUp(bytes32,uint256)")
    internal val INCREASE_DEPTH = AbiWords.selector("increaseDepth(bytes32,uint8)")

    private fun BatchId.bytes(): ByteArray = hex.hexToBytes()
}

/**
 * The PostageStamp's state at one block, as far as a purchase, a top-up or a dilution goes:
 * whether it is [paused] (each of them reverts then), its [lastPrice] (PLUR a chunk a block), and
 * [minimumPerChunk], the balance per chunk a batch must keep (a day's, at that price).
 */
data class PostageState(val paused: Boolean, val lastPrice: UInt256, val minimumPerChunk: UInt256)
