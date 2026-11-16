package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.DelayFinding
import eth.serotine.domain.repository.DelayGeneration
import eth.serotine.domain.repository.DelayModule
import eth.serotine.domain.repository.DelayModules
import eth.serotine.domain.repository.DelayReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Whether a Safe in the wallet is a Gnosis Pay account (docs/gnosis-pay.md, *Detection*): a Safe
 * on Gnosis Chain (a Safe on any other network is none, read for nothing) with a Delay modifier of
 * its own enabled ([DelayFinding.Wired]), as the light client proves it,
 * and which of that Delay's members the wallet holds a key for, so whether a transfer can be
 * queued through it here. Each module the Safe's verified state lists is read on its own
 * ([readEach]): a module enabled or disabled starts or stops its own reading only.
 *
 * A Safe with several Delays of its own is read by the first of the current mastercopy in its
 * module order, else, once every module has answered, the first of the retired one. A Safe whose
 * modules are not all read yet is [GnosisPayReading.Checking] until one of them is its Delay or
 * all of them are read, and [GnosisPayReading.Unavailable] when a module that could still be its
 * Delay cannot be read: a Safe is never said not to be one on a read that did not answer.
 */
class ObserveGnosisPay(
    private val kinds: AddressKinds,
    private val delays: DelayModules,
    private val accounts: AccountRepository,
) {
    operator fun invoke(safe: AccountId): Flow<GnosisPayReading> {
        // Gnosis Pay runs on Gnosis Chain alone (owner's decisions, 2026-11-15): no other Safe is read.
        if (safe.network != Network.GNOSIS) return flowOf(GnosisPayReading.NotGnosisPay)
        return combine(kinds.observeKind(safe.network, safe.address), accounts.observeAccounts()) { k, l -> k to l }
            .readEach(
                keys = { (kind, _) -> modulesOf(kind).toSet() },
                read = { module -> delays.observeDelay(safe.network, safe.address, module) },
            )
            .map { (upstream, readings) ->
                val (kind, list) = upstream
                reading(safe, kind, list, readings)
            }
            .distinctUntilChanged()
    }

    private fun modulesOf(kind: KindReading): List<Address> =
        ((kind as? KindReading.Verified)?.kind as? AddressKind.Safe)?.state?.modules.orEmpty()

    private fun reading(
        safe: AccountId,
        kind: KindReading,
        list: AccountList,
        readings: Map<Address, DelayReading>,
    ): GnosisPayReading {
        val state = when (kind) {
            KindReading.Syncing -> return GnosisPayReading.Checking
            is KindReading.Unavailable -> return GnosisPayReading.Unavailable(kind.reason)
            is KindReading.Verified ->
                (kind.kind as? AddressKind.Safe)?.state ?: return GnosisPayReading.NotGnosisPay
        }
        val wired = state.modules.mapNotNull { module ->
            val verified = readings[module] as? DelayReading.Verified ?: return@mapNotNull null
            (verified.finding as? DelayFinding.Wired)?.let { it.module to verified.blockNumber }
        }
        // Only a module that has answered, and answered that it is not the Safe's Delay, rules one out.
        val open = state.modules.map { readings[it] }.filter { it !is DelayReading.Verified }
        val current = wired.firstOrNull { it.first.generation == DelayGeneration.CURRENT }
        // A retired Delay is the answer only once no module still unread could be a current one.
        val chosen = current ?: wired.firstOrNull()?.takeIf { open.isEmpty() }
        if (chosen == null) {
            return when {
                open.isEmpty() -> GnosisPayReading.NotGnosisPay
                open.any { it == null || it == DelayReading.Syncing } -> GnosisPayReading.Checking
                else -> GnosisPayReading.Unavailable(open.filterIsInstance<DelayReading.Unavailable>().first().reason)
            }
        }
        val (delay, block) = chosen
        val loaded = list as? AccountList.Loaded
        val held = loaded?.accounts.orEmpty().filter {
            it.custody == AccountCustody.InKeyStore && it.kind == AccountKind.EOA && it.address in delay.members
        }
        val here = held.filter { it.network == safe.network }
        val hereAddresses = here.mapTo(HashSet()) { it.address }
        val elsewhere = held.filter { it.network != safe.network && it.address !in hereAddresses }
            .distinctBy { it.address }
        // A member watched on the Safe's network whose key is held under another network's entry.
        val watchedHere = loaded?.accounts.orEmpty().filter {
            it.network == safe.network && it.custody == AccountCustody.Watched && it.address in delay.members &&
                elsewhere.any { held -> held.address == it.address }
        }
        return GnosisPayReading.Found(
            delay,
            block,
            membersHeld = here,
            membersHeldElsewhere = elsewhere,
            accountsRead = loaded != null,
            membersWatchedHere = watchedHere,
            safeModules = state.modules,
        )
    }
}

/** One answer of [ObserveGnosisPay]. */
sealed interface GnosisPayReading {

    /** Not read yet: the Safe, or one of its modules that could still be its Delay. */
    data object Checking : GnosisPayReading

    /** No Delay modifier of the Safe's own is enabled on it, or the address holds no recognised Safe. */
    data object NotGnosisPay : GnosisPayReading

    /** No verified answer can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: ReadUnavailable) : GnosisPayReading

    /**
     * The Safe's Delay modifier, [delay], verified at block [blockNumber]; [membersHeld], the
     * wallet's EOAs on the Safe's network whose key is held here and that are among its members;
     * [membersHeldElsewhere], members whose key is held here only under an account on another
     * network, one entry per address; [accountsRead] false when the wallet's list could not be read,
     * so which members are held cannot be told; [membersWatchedHere], the members of
     * [membersHeldElsewhere] that are in the wallet on the Safe's network as watched entries; and
     * [safeModules], every module the Safe's verified state lists, which no transfer may go to.
     */
    data class Found(
        val delay: DelayModule,
        val blockNumber: Long,
        val membersHeld: List<Account>,
        val membersHeldElsewhere: List<Account>,
        val accountsRead: Boolean = true,
        val membersWatchedHere: List<Account> = emptyList(),
        val safeModules: List<Address>,
    ) : GnosisPayReading {
        /** Whether a transfer can be queued through [delay] from this wallet, or what is missing. */
        val sending: GnosisPaySending
            get() = when {
                delay.generation == DelayGeneration.RETIRED -> GnosisPaySending.RETIRED_MODULE
                delay.expirationSeconds in 1..DELAY_EXPIRY_MARGIN_SECONDS -> GnosisPaySending.WINDOW_TOO_SHORT
                membersHeld.isNotEmpty() -> GnosisPaySending.AVAILABLE
                !accountsRead -> GnosisPaySending.ACCOUNTS_UNREADABLE
                membersWatchedHere.isNotEmpty() -> GnosisPaySending.MEMBER_WATCHED_HERE
                membersHeldElsewhere.isNotEmpty() -> GnosisPaySending.MEMBER_ON_ANOTHER_NETWORK
                else -> GnosisPaySending.NO_MEMBER_KEY
            }
    }
}

/**
 * Whether a Gnosis Pay account can send from this wallet ([GnosisPayReading.Found.sending]): one
 * value per reason Send is greyed out, which the greyed item says (CLAUDE.md, *Conventions*).
 */
enum class GnosisPaySending {
    /** A member's key is held here, on the Safe's network: a transfer can be queued. */
    AVAILABLE,

    /** Its Delay is a proxy of the retired mastercopy: nothing is sent through it (owner's decisions, 2026-11-15). */
    RETIRED_MODULE,

    /**
     * Its Delay's expiration is no longer than [DELAY_EXPIRY_MARGIN_SECONDS]: an execution could
     * not be mined before it, so nothing is queued.
     */
    WINDOW_TOO_SHORT,

    /** A member's key is held here, but not as an account on the Safe's network: adding it there sends. */
    MEMBER_ON_ANOTHER_NETWORK,

    /**
     * A member's key is held here under another network's entry, while the member is watched on the
     * Safe's network: adding it again changes nothing, so the watched entry is removed first, and
     * the member then added on the Safe's network from the held one.
     */
    MEMBER_WATCHED_HERE,

    /** The wallet's account list cannot be read right now: whether a member's key is held cannot be told. */
    ACCOUNTS_UNREADABLE,

    /** No member's key is held here: the Safe is watched. */
    NO_MEMBER_KEY,
}
