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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Whether a Safe in the wallet is a Gnosis Pay account (docs/gnosis-pay.md, *Detection*): a Safe
 * on Gnosis Chain (a Safe on any other network is none, read for nothing) with a Delay modifier of
 * its own enabled ([DelayFinding.Wired]), as the light client proves it; who controls that Delay,
 * its members followed down through Safes to the keys at the end ([DelayControl]); and which of
 * those keys the wallet holds, so whether a transfer can be queued through it here. Each module the
 * Safe's verified state lists is read on its own ([readEach]): a module enabled or disabled starts
 * or stops its own reading only.
 *
 * A Safe with several Delays of its own is read by the first of the current mastercopy in its
 * module order, else, once every module has answered, the first of the retired one. A Safe whose
 * modules are not all read yet is [GnosisPayReading.Checking] until one of them is its Delay or
 * all of them are read, and [GnosisPayReading.Unavailable] when a module that could still be its
 * Delay cannot be read: a Safe is never said not to be one on a read that did not answer.
 *
 * An answer once verified stands, with its block, while a later read of the same thing only did
 * not answer ([holdingVerifiedMarked]: no peer served its proof at that head, or the light client is
 * catching up): the Safe, each module, and each address on the way to the keys. A verified answer
 * that differs replaces it at once, and so does a reason that stands (the network turned off);
 * after an hour of reads that do not answer it is dropped ([HOLD_LIMIT]). A reading that stands on
 * a held Safe or Delay says so ([GnosisPayReading.Found.held]), and no transfer is planned on it.
 *
 * A key controls the account here when it reaches the Delay through Safes that each need one
 * signature ([DelayController.alone]; owner's request, 2026-11-17): it then queues a transfer by
 * itself, through those Safes. Who controls the Delay is read again only when its members or the
 * keys held here change, not with every reading of the Delay.
 */
class ObserveGnosisPay(
    private val kinds: AddressKinds,
    private val delays: DelayModules,
    private val accounts: AccountRepository,
) {
    private val controlReader = DelayControlReader(kinds)

    operator fun invoke(safe: AccountId): Flow<GnosisPayReading> {
        // Gnosis Pay runs on Gnosis Chain alone (owner's decisions, 2026-11-15): no other Safe is read.
        if (safe.network != Network.GNOSIS) return flowOf(GnosisPayReading.NotGnosisPay)
        // The last verified answer stands over a read that only did not answer this time: the Safe's
        // modules, and so their readers and who controls the Delay, are not started over for it.
        val kind = kinds.observeKind(safe.network, safe.address).holdingVerifiedMarked()
        return combine(kind, accounts.observeAccounts()) { k, l -> k to l }
            .readEach(
                keys = { (kind, _) -> modulesOf(kind.value).toSet() },
                read = { module -> delays.observeDelay(safe.network, safe.address, module).holdingVerifiedDelay() },
            )
            .map { (upstream, readings) ->
                val (kind, list) = upstream
                stage(kind.value, kind.held, list, readings)
            }
            .readEach(
                keys = { stage -> setOfNotNull((stage as? Stage.Chosen)?.controlKey) },
                read = { key -> controlReader.observe(safe.network, key.members, key.keys) },
            )
            .let { readings ->
                flow {
                    // The last answer for the same members, while a new key's reader has not answered
                    // yet (a key imported or removed): who is held is told from the list now, so the
                    // account is not said to be checked again for a moment. One per collector.
                    var last: DelayControl? = null
                    readings.collect { (stage, control) ->
                        emit(
                            when (stage) {
                                is Stage.Done -> stage.reading
                                is Stage.Chosen -> {
                                    val now = control[stage.controlKey]
                                    if (now != null) last = now
                                    // Its first answer comes as soon as its reader starts (a held key
                                    // needs no read, any other is said unread until it is): until then
                                    // the account is still being checked.
                                    val shown = now ?: last?.takeIf { it.memberAddresses == stage.delay.members }
                                    shown?.let { found(safe, stage, it) } ?: GnosisPayReading.Checking
                                }
                            },
                        )
                    }
                }
            }
            .distinctUntilChanged()
    }

    private fun modulesOf(kind: KindReading): List<Address> =
        ((kind as? KindReading.Verified)?.kind as? AddressKind.Safe)?.state?.modules.orEmpty()

    /** Where the reading stands before who controls the Delay is read: answered, or its Delay chosen. */
    private sealed interface Stage {
        data class Done(val reading: GnosisPayReading) : Stage

        data class Chosen(
            val delay: DelayModule,
            val blockNumber: Long,
            val list: AccountList,
            val modules: List<Address>,
            val held: Boolean,
        ) : Stage {
            /** What who controls the Delay is read for: its members, and the keys held here, which need no read. */
            val controlKey: ControlKey = ControlKey(
                delay.members,
                (list as? AccountList.Loaded)?.accounts.orEmpty()
                    .filter { it.custody == AccountCustody.InKeyStore && it.kind == AccountKind.EOA }
                    .mapTo(HashSet()) { it.address },
            )
        }
    }

    private data class ControlKey(val members: List<Address>, val keys: Set<Address>)

    private fun stage(
        kind: KindReading,
        kindHeld: Boolean,
        list: AccountList,
        readings: Map<Address, Held<DelayReading>>,
    ): Stage {
        val state = when (kind) {
            KindReading.Syncing -> return Stage.Done(GnosisPayReading.Checking)
            is KindReading.Unavailable -> return Stage.Done(GnosisPayReading.Unavailable(kind.reason))
            is KindReading.Verified ->
                (kind.kind as? AddressKind.Safe)?.state ?: return Stage.Done(GnosisPayReading.NotGnosisPay)
        }
        val wired = state.modules.mapNotNull { module ->
            val reading = readings[module] ?: return@mapNotNull null
            val verified = reading.value as? DelayReading.Verified ?: return@mapNotNull null
            (verified.finding as? DelayFinding.Wired)?.let { Triple(it.module, verified.blockNumber, reading.held) }
        }
        // Only a module that has answered, and answered that it is not the Safe's Delay, rules one out.
        val open = state.modules.map { readings[it]?.value }.filter { it !is DelayReading.Verified }
        val current = wired.firstOrNull { it.first.generation == DelayGeneration.CURRENT }
        // A retired Delay is the answer only once no module still unread could be a current one.
        val chosen = current ?: wired.firstOrNull()?.takeIf { open.isEmpty() }
        if (chosen == null) {
            return Stage.Done(
                when {
                    open.isEmpty() -> GnosisPayReading.NotGnosisPay
                    open.any { it == null || it == DelayReading.Syncing } -> GnosisPayReading.Checking
                    else ->
                        GnosisPayReading.Unavailable(open.filterIsInstance<DelayReading.Unavailable>().first().reason)
                },
            )
        }
        val (delay, block, delayHeld) = chosen
        return Stage.Chosen(delay, block, list, state.modules, held = kindHeld || delayHeld)
    }

    private fun found(safe: AccountId, chosen: Stage.Chosen, control: DelayControl): GnosisPayReading.Found {
        val loaded = chosen.list as? AccountList.Loaded
        val controllers = control.controllers
        val alone = controllers.filter { it.alone }
        val aloneKeys = alone.mapTo(HashSet()) { it.key }
        val held = loaded?.accounts.orEmpty().filter {
            it.custody == AccountCustody.InKeyStore && it.kind == AccountKind.EOA
        }
        // The shortest way first: a key that is a member itself sends straight to the Delay.
        val here = alone.sortedBy { it.via.size }.mapNotNull { controller ->
            held.firstOrNull { it.network == safe.network && it.address == controller.key }
                ?.let { HeldController(it, controller.via) }
        }.distinctBy { it.account.address }
        val hereAddresses = here.mapTo(HashSet()) { it.account.address }
        val elsewhere = held.filter { it.network != safe.network && it.address in aloneKeys && it.address !in hereAddresses }
            .distinctBy { it.address }
        // A controlling key watched on the Safe's network whose key is held under another network's entry.
        val watchedHere = loaded?.accounts.orEmpty().filter {
            it.network == safe.network && it.custody == AccountCustody.Watched && it.address in aloneKeys &&
                elsewhere.any { held -> held.address == it.address }
        }
        // Keys held here, under any network's entry, that act for the Delay only with co-owners: a Safe
        // on their way needs more signatures. One entry per address, the Safe's network's first.
        val coSigning = held.filter { account ->
            account.address !in hereAddresses && controllers.any { !it.alone && it.key == account.address }
        }.sortedBy { it.network != safe.network }.distinctBy { it.address }
        return GnosisPayReading.Found(
            chosen.delay,
            chosen.blockNumber,
            controllersHeld = here,
            keysHeldElsewhere = elsewhere,
            accountsRead = loaded != null,
            keysWatchedHere = watchedHere,
            safeModules = chosen.modules,
            control = control,
            keysCoSigning = coSigning,
            keysHere = held.filter { it.network == safe.network },
            held = chosen.held,
        )
    }
}

/**
 * Every Safe on Gnosis Chain in the wallet as a Gnosis Pay account ([ObserveGnosisPay]), for the
 * list's badges: which of them a key held here controls through its Delay (owner's request,
 * 2026-11-17). Each Safe is read on its own ([readEach]): one added or removed starts or stops its
 * own reading only. Safes on other networks are never read.
 */
class ObserveGnosisPays(
    private val accounts: AccountRepository,
    private val observeGnosisPay: ObserveGnosisPay,
) {
    operator fun invoke(): Flow<Map<AccountId, GnosisPayReading>> = accounts.observeAccounts()
        .readEach(
            keys = { list ->
                (list as? AccountList.Loaded)?.accounts.orEmpty()
                    .filter { it.kind == AccountKind.SAFE && it.network == Network.GNOSIS }
                    .mapTo(LinkedHashSet()) { it.id }
            },
            read = { id -> observeGnosisPay(id) },
        )
        .map { (_, read) -> read }
}

/**
 * An account whose key is held here, [account], that controls a Gnosis Pay account's Delay by
 * itself: through the Safes in [via], the Delay's member first and the Safe [account] owns last,
 * each needing one signature; empty when [account] is a member itself.
 */
data class HeldController(val account: Account, val via: List<Address>)

/** One answer of [ObserveGnosisPay]. */
sealed interface GnosisPayReading {

    /**
     * Whether a key held here controls the account through its Delay, so that it shows Full control
     * ([Found.sending] available); false for every other answer.
     */
    val controlledHere: Boolean
        get() = (this as? Found)?.sending == GnosisPaySending.AVAILABLE

    /** Not read yet: the Safe, or one of its modules that could still be its Delay. */
    data object Checking : GnosisPayReading

    /** No Delay modifier of the Safe's own is enabled on it, or the address holds no recognised Safe. */
    data object NotGnosisPay : GnosisPayReading

    /** No verified answer can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: ReadUnavailable) : GnosisPayReading

    /**
     * The Safe's Delay modifier, [delay], verified at block [blockNumber]; [controllersHeld], the
     * wallet's EOAs on the Safe's network whose key is held here and that control the Delay by
     * themselves, as members or through member Safes ([HeldController]), the shortest way first;
     * [keysHeldElsewhere], such keys held here only under an account on another network, one entry
     * per address; [accountsRead] false when the wallet's list could not be read, so which keys are
     * held cannot be told; [keysWatchedHere], the keys of [keysHeldElsewhere] that are in the
     * wallet on the Safe's network as watched entries; [safeModules], every module the Safe's
     * verified state lists, which no transfer may go to; [control], who controls the Delay, null
     * until it is read; and [keysCoSigning], keys held here (under any network's entry, one per
     * address) that act for the Delay only with co-owners; and [keysHere], every key held here on the Safe's network, from the
     * wallet's list alone: any of them may execute a transfer it queued, which needs no control.
     * [held] when the Safe's state or its Delay's is an earlier verified answer standing over a
     * read that did not answer since ([blockNumber] is then that answer's): shown, and no transfer
     * is planned on it, since [safeModules] and the Delay's periods could have changed.
     */
    data class Found(
        val delay: DelayModule,
        val blockNumber: Long,
        val controllersHeld: List<HeldController>,
        val keysHeldElsewhere: List<Account>,
        val accountsRead: Boolean = true,
        val keysWatchedHere: List<Account> = emptyList(),
        val safeModules: List<Address>,
        val control: DelayControl? = null,
        val keysCoSigning: List<Account> = emptyList(),
        val keysHere: List<Account> = controllersHeld.map { it.account },
        val held: Boolean = false,
    ) : GnosisPayReading {
        /** Whether a transfer can be queued through [delay] from this wallet, or what is missing. */
        val sending: GnosisPaySending
            get() = when {
                delay.generation == DelayGeneration.RETIRED -> GnosisPaySending.RETIRED_MODULE
                delay.expirationSeconds in 1..DELAY_EXPIRY_MARGIN_SECONDS -> GnosisPaySending.WINDOW_TOO_SHORT
                controllersHeld.isNotEmpty() -> GnosisPaySending.AVAILABLE
                !accountsRead -> GnosisPaySending.ACCOUNTS_UNREADABLE
                keysWatchedHere.isNotEmpty() -> GnosisPaySending.MEMBER_WATCHED_HERE
                keysHeldElsewhere.isNotEmpty() -> GnosisPaySending.MEMBER_ON_ANOTHER_NETWORK
                // Never said to be out of reach on a read that did not answer, or was never made.
                control == null || !control.complete -> GnosisPaySending.CONTROL_UNREAD
                control.overBudget -> GnosisPaySending.CONTROL_TOO_WIDE
                keysCoSigning.isNotEmpty() -> GnosisPaySending.CO_SIGNERS_NEEDED
                else -> GnosisPaySending.NO_MEMBER_KEY
            }
    }
}

/**
 * Whether a Gnosis Pay account can send from this wallet ([GnosisPayReading.Found.sending]): one
 * value per reason Send is greyed out, which the greyed item says (CLAUDE.md, *Conventions*).
 */
enum class GnosisPaySending {
    /**
     * A key that controls the Delay by itself is held here, on the Safe's network: a transfer can be
     * queued, by a member, or through member Safes that each need one signature.
     */
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

    /**
     * Who controls the Delay is not all read yet, or cannot be verified right now: no key held here
     * was found to control it, and one could still be on a way not read.
     */
    CONTROL_UNREAD,

    /**
     * The Delay is controlled through more accounts than Punicus reads for one
     * ([DelayControl.MAX_ADDRESSES]), and no key held here was found among those read: whether one
     * is behind the rest cannot be told.
     */
    CONTROL_TOO_WIDE,

    /**
     * A key held here acts for the Delay only together with co-owners: a Safe on its way needs more
     * than one signature, which Punicus does not gather for a Delay.
     */
    CO_SIGNERS_NEEDED,

    /** No key that controls the Delay is held here: the Safe is watched. */
    NO_MEMBER_KEY,
}
