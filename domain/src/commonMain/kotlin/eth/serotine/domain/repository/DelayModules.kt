package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import kotlinx.coroutines.flow.Flow

/**
 * The port for a Zodiac Delay modifier enabled on a Safe, the module a Gnosis Pay account's
 * controlling account sends through (docs/gnosis-pay.md; CLAUDE.md, *Trust*), as the embedded
 * light client proves it. :data implements it over the Myotis engine (`eth.serotine.data.engine`):
 * the module's code proven to be a minimal proxy of a pinned Delay mastercopy of its network,
 * and its settings read with verified calls of its own getters at one block. Nothing else may
 * answer it. What a module is, is per network: a network that pins no Delay mastercopy finds none.
 */
interface DelayModules {
    /**
     * What the module at [module], enabled on the Safe at [safe], is on [network], re-emitted
     * when the verified head moves; consecutive equal readings are not repeated. The [safe] is
     * applied, never ignored: a Delay whose avatar, target or owner is another address is
     * [DelayFinding.NotWired], not this Safe's. A [network] no adapter serves here is refused
     * ([ReadUnavailable.NO_ENGINE]), never answered from another network. Never fails.
     */
    fun observeDelay(network: Network, safe: Address, module: Address): Flow<DelayReading>

    /**
     * The queue of the Delay at [delay] on [network] as it is now (docs/gnosis-pay.md, *Sending*):
     * its next entry to execute and its next to be added, its cooldown and expiration, and each
     * entry from the next to execute on, at most [MAX_QUEUE_READ] of them, with its hash
     * (`txHash(n)`) and the time it was queued (`txCreatedAt(n)`), all read with verified calls at
     * one block; and the time of the verified head they were read at, which is when "now" is for
     * the cooldown. Read fresh at every call, never from a reading kept before. Blocks on the
     * engine, bounded by it; never throws. A [network] no adapter serves here is refused
     * ([ReadUnavailable.NO_ENGINE]).
     */
    suspend fun queue(network: Network, delay: Address): DelayQueueReading

    companion object {
        /** Entries read past the next to execute: a queue longer than this is read up to here. */
        const val MAX_QUEUE_READ = 16
    }
}

/** One answer of [DelayModules.queue]. */
sealed interface DelayQueueReading {

    /** [queue], read at block [blockNumber], whose verified head was produced at [headTime] (unix seconds). */
    data class Read(val queue: DelayQueue, val blockNumber: Long, val headTime: Long) : DelayQueueReading

    /** No verified answer can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: ReadUnavailable) : DelayQueueReading
}

/**
 * A Delay's queue at one block: [txNonce], the next entry to execute, and [queueNonce], the next to
 * be added; [cooldownSeconds] and [expirationSeconds] (0: for ever) as they are at that block; and
 * [entries], from [txNonce] on in order, at most [DelayModules.MAX_QUEUE_READ].
 */
data class DelayQueue(
    val txNonce: Long,
    val queueNonce: Long,
    val cooldownSeconds: Long,
    val expirationSeconds: Long,
    val entries: List<DelayEntry>,
) {
    init {
        require(txNonce in 0..queueNonce) { "a Delay's next entry is within its queue" }
        require(entries.map { it.nonce } == (txNonce until txNonce + entries.size).toList()) {
            "a queue's entries are read in order from the next to execute"
        }
        require(entries.size <= queueNonce - txNonce) { "a queue holds no entry past its end" }
    }
}

/** Entry [nonce] of a Delay's queue: the [hash] of the call it holds, queued at [createdAt] (unix seconds). */
data class DelayEntry(val nonce: Long, val hash: Digest, val createdAt: Long)

/** One answer of [DelayModules.observeDelay]. */
sealed interface DelayReading {

    /** The light client has no verified head yet. */
    data object Syncing : DelayReading

    /** The module is [finding], verified at block [blockNumber]. */
    data class Verified(val finding: DelayFinding, val blockNumber: Long) : DelayReading

    /** No verified answer can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: ReadUnavailable) : DelayReading
}

/** What a module enabled on a Safe is, as far as the Delay modifier goes. */
sealed interface DelayFinding {

    /** Its code is no minimal proxy of a pinned Delay mastercopy: some other module. */
    data object NotDelay : DelayFinding

    /**
     * A proxy of a pinned Delay mastercopy of [generation], whose avatar, target or owner is not
     * the Safe: whoever owns it can change its cooldown or skip its queue, so it is no Gnosis Pay
     * account's and nothing is sent through it.
     */
    data class NotWired(val generation: DelayGeneration) : DelayFinding

    /** The Safe's own Delay modifier, [module]. */
    data class Wired(val module: DelayModule) : DelayFinding
}

/**
 * Which pinned Delay mastercopy a proxy delegates to. Gnosis Pay replaced its Delay mastercopy on
 * 2026-06-03, after the exploit of 2026-06-01 (docs/gnosis-pay.md, *The mastercopies*).
 */
enum class DelayGeneration {
    /** The mastercopy deployed after the exploit: a member may send through it. */
    CURRENT,

    /**
     * The mastercopy the exploit of 2026-06-01 went through: recognised, shown, and never sent
     * through (owner's decisions, 2026-11-15).
     */
    RETIRED,
}

/**
 * A Safe's Delay modifier at [address], as its own getters return it at one block: the
 * mastercopy it delegates to ([generation]); the [cooldownSeconds] a queued transaction waits
 * before it may be executed, and the [expirationSeconds] it may be executed in after that (0:
 * for ever); the queue, [txNonce] the next entry to execute and [queueNonce] the next to be
 * added (the queue holds `queueNonce - txNonce` entries, expired ones among them); and its
 * [members], the modules enabled on it, in its own order: the accounts that may queue a
 * transaction for the Safe. Its avatar, target and owner are the Safe ([DelayFinding.Wired]).
 */
data class DelayModule(
    val address: Address,
    val generation: DelayGeneration,
    val cooldownSeconds: Long,
    val expirationSeconds: Long,
    val txNonce: Long,
    val queueNonce: Long,
    val members: List<Address>,
) {
    init {
        require(cooldownSeconds >= 0 && expirationSeconds >= 0) { "a Delay's periods are not negative" }
        require(txNonce in 0..queueNonce) { "a Delay's next entry is within its queue" }
    }

    /** How many entries the queue holds, expired ones among them. */
    val queued: Long
        get() = queueNonce - txNonce
}
