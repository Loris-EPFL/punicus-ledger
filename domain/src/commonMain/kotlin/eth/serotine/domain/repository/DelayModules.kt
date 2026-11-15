package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
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
}

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
