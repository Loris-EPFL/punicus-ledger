package eth.serotine.data.engine

import eth.serotine.domain.model.Address

/**
 * One read of several verified calls that must all answer at one block, as
 * `VerifiedDelayModules` reads a Delay's getters: the first call at the head, every later one held
 * to the block the first answered at ([VerifiedChain.callAt]), asked once more when it gives no
 * answer, and then at the head, where the check below still throws away an answer of another
 * block: an engine that serves no call at a named block reads as before. Every answer must name
 * the first one's block number, and its hash where the engine names one.
 *
 * Each [call] returns null once the read has ended, with [ended] saying how; nothing is read after
 * that. One instance is one read: a read that [Ended.STRADDLED] is made again with a new one.
 */
internal class OneBlockCalls(private val chain: VerifiedChain) {
    /** The block the read is at, once its first call answered. */
    var block: Long? = null
        private set

    /** The hash of [block], where the engine named one. */
    var hash: String? = null
        private set

    /** How the read ended early; meaningful once a [call] returned null. */
    var ended: Ended = Ended.NO_ANSWER
        private set

    /** Ends the read for [why]; null, for a caller to return. */
    fun <T> end(why: Ended): T? {
        ended = why
        return null
    }

    /**
     * What [data] called on [to] returned (lower-case hex, no `0x`), at the one block of this read;
     * null when the read ended: no answer, an answer at another block, a revert (a pinned getter
     * never reverts: [Ended.NOT_THE_CONTRACTS_ANSWER]) or a refusal.
     */
    fun call(to: Address, data: ByteArray): String? {
        val pinned = block
        val asked = if (pinned == null) {
            chain.call(to, data)
        } else {
            held(to, data, pinned) ?: held(to, data, pinned) ?: chain.call(to, data)
        }
        val returned = when (asked) {
            null -> return end(Ended.NO_ANSWER)
            is CallOutcome.Returned -> asked
            is CallOutcome.Reverted -> return end(Ended.NOT_THE_CONTRACTS_ANSWER)
            CallOutcome.Refused -> return end(Ended.REFUSED)
        }
        val at = returned.blockNumber ?: return end(Ended.NO_ANSWER)
        if (pinned == null) {
            block = at
            hash = returned.blockHash
        } else if (pinned != at || hash != returned.blockHash) {
            return end(Ended.STRADDLED)
        }
        return returned.hex.removePrefix("0x").lowercase()
    }

    /**
     * [data] called on [to] at block [at]. A refusal there is no answer: the first call, at the
     * head, showed that the engine runs these calls on this build, so it is that block it will not
     * serve, which the next read's first call replaces.
     */
    private fun held(to: Address, data: ByteArray, at: Long): CallOutcome? =
        chain.callAt(to, data, at).takeUnless { it == CallOutcome.Refused }

    /** Why a read stopped short. */
    enum class Ended {
        /** Two calls answered at two blocks: read again. */
        STRADDLED,

        /** A call gave no verified answer this time. */
        NO_ANSWER,

        /** A call answered what the pinned contract's getter never answers: unproven, not read again. */
        NOT_THE_CONTRACTS_ANSWER,

        /** The engine refuses the calls on this build. */
        REFUSED,
    }
}
