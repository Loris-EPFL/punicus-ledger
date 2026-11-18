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
     * What the module at [module], enabled on the Safe at [safe], is on [network], read again
     * every few verified heads as the adapter says, a reading kept until then served to every
     * subscriber; consecutive equal readings are not repeated. What a Delay holds that an execution
     * depends on is not taken from here. The [safe] is
     * applied, never ignored: a Delay whose avatar, target or owner is another address is
     * [DelayFinding.NotWired], not this Safe's. A [network] no adapter serves here is refused
     * ([ReadUnavailable.NO_ENGINE]), never answered from another network. Never fails.
     */
    fun observeDelay(network: Network, safe: Address, module: Address): Flow<DelayReading>

    /**
     * Where the call of hash [hash] stands in the queue of the Delay at [delay], enabled on the Safe
     * at [safe], on [network] (docs/gnosis-pay.md, *Sending*), all of it read fresh at one block,
     * the head when its first call answers, never from a reading kept before: the Delay's code proven to be a proxy of the
     * current mastercopy; its avatar, target and owner, which must be [safe] (else
     * [DelayQueueReading.NotTheSafes]); its next entry to execute and its next to be added, its
     * cooldown and expiration; and the first entry of that hash from the next to execute on,
     * searched through at most [MAX_QUEUE_READ] entries, with the time it was queued; and the time
     * the next entry to execute was queued, whatever its hash, which tells whether it expired and
     * so blocks every entry behind it. With them,
     * the time of the verified head they were read at, which is when "now" is for the cooldown.
     * With [withMembers], the Delay's members too, in its own order, at the same block
     * ([DelayQueue.members], never null then): what a change of its members is checked against
     * (owner ruling, 2026-11-18). Without, they are not read, and [DelayQueue.members] is null.
     * The one read that what is quoted or sent through a Delay rests on. Blocks on the engine,
     * bounded by it; never throws. A [network] no adapter serves here is refused
     * ([ReadUnavailable.NO_ENGINE]).
     */
    suspend fun queue(
        network: Network,
        safe: Address,
        delay: Address,
        hash: Digest,
        withMembers: Boolean = false,
    ): DelayQueueReading

    companion object {
        /** Entries searched from the next to execute on: an entry further back is not looked for. */
        const val MAX_QUEUE_READ = 8
    }
}

/** One answer of [DelayModules.queue]. */
sealed interface DelayQueueReading {

    /** [queue], read at block [blockNumber], whose verified head was produced at [headTime] (unix seconds). */
    data class Read(val queue: DelayQueue, val blockNumber: Long, val headTime: Long) : DelayQueueReading

    /**
     * The Delay is not [safe]'s own of the current mastercopy, as proven now: a proxy of the
     * retired one ([generation] RETIRED), one whose avatar, target or owner is another address,
     * or (with [generation] null) no proxy of a pinned mastercopy at all. Nothing is sent through it.
     */
    data class NotTheSafes(val generation: DelayGeneration?, val blockNumber: Long) : DelayQueueReading

    /** No verified answer can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: ReadUnavailable) : DelayQueueReading
}

/**
 * A Delay's queue at one block, as far as one call's place in it goes: [txNonce], the next entry
 * to execute, and [queueNonce], the next to be added; [cooldownSeconds] and [expirationSeconds]
 * (0: for ever) as they are at that block; [entry], the first entry of the call's hash from
 * [txNonce] on, or null when none was found among the [searched] entries from [txNonce] on; and
 * [headCreatedAt], when the entry at [txNonce] was queued, null when the queue is empty; and
 * [members], the Delay's members in its own order at that block, null when they were not asked for.
 */
data class DelayQueue(
    val txNonce: Long,
    val queueNonce: Long,
    val cooldownSeconds: Long,
    val expirationSeconds: Long,
    val entry: DelayEntry?,
    val searched: Int,
    val headCreatedAt: Long? = entry?.takeIf { it.nonce == txNonce }?.createdAt,
    val members: List<Address>? = null,
) {
    init {
        require(txNonce in 0..queueNonce) { "a Delay's next entry is within its queue" }
        require(searched in 0..(queueNonce - txNonce)) { "a queue holds no entry past its end" }
        require(entry == null || entry.nonce == txNonce + searched - 1) { "the search ends at the entry found" }
    }

    /** Whether every entry from [txNonce] on was searched. */
    val searchedAll: Boolean
        get() = searched.toLong() == queueNonce - txNonce

    /** How many entries wait from [txNonce] on. */
    val waiting: Long
        get() = queueNonce - txNonce

    /**
     * Whether the entry at [txNonce] expired by [headTime] (unix seconds): `executeNextTx` then
     * reverts on it, so every entry behind it waits until the Delay's `skipExpired` removes it.
     * Never, with no expiration or an empty queue.
     */
    fun headExpired(headTime: Long): Boolean {
        val createdAt = headCreatedAt ?: return false
        return expirationSeconds != 0L && headTime > createdAt + cooldownSeconds + expirationSeconds
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
