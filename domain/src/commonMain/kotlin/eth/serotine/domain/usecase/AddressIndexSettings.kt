package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexImporters
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressIndexChoice
import eth.serotine.domain.repository.AddressIndexHistory
import eth.serotine.domain.repository.AddressIndexReaderSettings
import eth.serotine.domain.repository.AddressIndexSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * What is missing for the addr1 history's setting to be turned on (owner ruling, 2026-10-18, #55: a missing
 * prerequisite greys the setting out, and a tap says what is missing).
 */
enum class AddressIndexMissing {
    /** No importer key is pinned in this release: the setting has no key to name, and the user types none in. */
    NO_KEY_PINNED,

    /** This build's engine has no Swarm reader ([AddressIndexHistory.hasReader] false). */
    NO_READER,

    /**
     * The engine has not said yet whether it has a Swarm reader: it says so once one of its light clients runs
     * ([AddressIndexHistory.hasReader] not answered yet).
     */
    READER_NOT_KNOWN,

    /** The choice stored cannot be read, so it cannot be changed until it can. */
    CHOICE_UNREADABLE,
}

/**
 * Where the experimental addr1 history stands (owner ruling, 2026-11-21, #334; the owner's decisions of that day on
 * #335; on by default, the owner's decision of 2026-11-22): [pinned], the importer keys pinned in the release, one per
 * network that has an index (none: none pinned in this one), whether the engine has its Swarm [reader] (null: not
 * known yet), and the user's [choice]. It is [wanted] when a key is pinned and the user did not turn it off, and [on]
 * when it is wanted and the engine reads Swarm. The default applies to each network's key (owner's request,
 * 2026-11-26: "The default should apply for all chains that have an addr1 index"); one choice for every key is how
 * it is applied, the owner's to approve on #393:
 * - **no choice made** ([AddressIndexChoice.NotChosen]: a fresh install): on, by default;
 * - **an explicit off** (a stored choice of no key): off, whatever key is pinned, until the user turns it on again;
 * - **on for one of the keys pinned now**: on, for every one of them, since a key newly pinned beside it had no choice
 *   made for it and is on by default;
 * - **on for another key** ([trustsAnotherKey]): that choice does not carry over to the keys pinned now, which no
 *   choice was made for, so it is on by default for them, as on a fresh install; only an explicit off is kept across
 *   keys;
 * - **a choice that cannot be read**, or one found damaged and set aside: off, since it may have been an explicit off,
 *   and nothing is read on a guess. Unreadable, it cannot be changed until it can be read; set aside, it is off until
 *   the user chooses again.
 */
data class AddressIndexStatus(
    val pinned: AddressIndexImporters,
    val reader: Boolean?,
    val choice: AddressIndexChoice,
) {

    /** What keeps the setting from being turned on, or changed at all; empty when it can be. */
    val missing: Set<AddressIndexMissing>
        get() = buildSet {
            if (pinned.none) add(AddressIndexMissing.NO_KEY_PINNED)
            if (reader == false) add(AddressIndexMissing.NO_READER)
            if (reader == null) add(AddressIndexMissing.READER_NOT_KNOWN)
            if (choice is AddressIndexChoice.Unreadable) add(AddressIndexMissing.CHOICE_UNREADABLE)
        }

    /**
     * Whether *Activity* takes the addr1 index: a key pinned, a reader to read it, and no explicit off nor a choice
     * that cannot be read or was set aside (the cases above): on by default otherwise.
     */
    val on: Boolean
        get() = wanted && reader == true

    /**
     * Whether the user's choice asks for the history of the keys [pinned]: a key pinned, and no explicit off nor a
     * choice that cannot be read or was set aside, whatever the engine's reader. What the engine is told to read
     * ([FollowAddressIndexSetting]); an engine without the reader refuses every read on its own.
     */
    val wanted: Boolean
        get() = !pinned.none && when (choice) {
            AddressIndexChoice.NotChosen -> true
            // On for any key: for a key pinned now, or, for another one, no choice for these (default on).
            is AddressIndexChoice.Chosen -> choice.importer != null
            AddressIndexChoice.Unreadable -> false
        }

    /**
     * The importer key whose index [network]'s *Activity* takes now: its pinned key while the setting is [on]; null
     * while it is off, or when [network] has no index in this release.
     */
    fun readOn(network: Network): Address? = if (on) pinned.of(network) else null

    /** The key the setting was turned on for when that is none of the keys pinned now; null otherwise. */
    val trustsAnotherKey: Address?
        get() = (choice as? AddressIndexChoice.Chosen)?.importer?.takeIf { it !in pinned }

    /** Whether the stored choice was damaged and set aside: off, said until another choice is made. */
    val setAside: Boolean
        get() = (choice as? AddressIndexChoice.Chosen)?.setAside == true
}

/**
 * The [AddressIndexStatus] now, re-emitted when the choice changes: [setting], the user's choice; [history], whose
 * engine says once whether it has a Swarm reader, which is not known ([AddressIndexStatus.reader] null) until it has
 * said, so that Settings draws the row greyed out meanwhile rather than none (owner ruling, 2026-10-18, #55);
 * [pinned], the importer keys pinned in the release, none when this release pins none (none was until 2026-11-24,
 * owner's decision of 2026-11-21; Ethereum's `0xb11B…7241` since, owner's choice of 2026-11-24, #359; Gnosis Chain's
 * `0xE779…9335` beside it since 2026-11-26, owner's choice, #393).
 */
class ObserveAddressIndexStatus(
    private val history: AddressIndexHistory,
    private val setting: AddressIndexSetting,
    private val pinned: AddressIndexImporters,
) {
    operator fun invoke(): Flow<AddressIndexStatus> =
        combine(flow { emit(null); emit(history.hasReader()) }, setting.observe()) { reader, choice ->
            AddressIndexStatus(pinned, reader, choice)
        }.distinctUntilChanged()
}

/** What [TurnAddressIndex] did. */
sealed interface AddressIndexSwitch {
    /** Turned on or off as asked, and saved. */
    data object Done : AddressIndexSwitch

    /** The choice could not be saved: nothing changed. */
    data object NotSaved : AddressIndexSwitch

    /** It cannot be turned on: [missing] says what it needs (never empty). Nothing changed. */
    data class Unavailable(val missing: Set<AddressIndexMissing>) : AddressIndexSwitch
}

/**
 * Turns the addr1 history on, for the keys [pinned] in the release, or off: one choice for every network's key. On is
 * saved as the first network's key ([AddressIndexImporters.first]), which [AddressIndexStatus] reads as on for all of
 * them. On needs a pinned key and an engine with a Swarm reader ([history]); without either it is refused and nothing
 * is saved, never turned on for a key nobody pinned. Off always saves, so a choice made in an earlier build can always
 * be taken back.
 */
class TurnAddressIndex(
    private val history: AddressIndexHistory,
    private val setting: AddressIndexSetting,
    private val pinned: AddressIndexImporters,
) {
    suspend operator fun invoke(on: Boolean): AddressIndexSwitch {
        if (on) {
            val missing = buildSet {
                if (pinned.none) add(AddressIndexMissing.NO_KEY_PINNED)
                if (!history.hasReader()) add(AddressIndexMissing.NO_READER)
            }
            if (missing.isNotEmpty()) return AddressIndexSwitch.Unavailable(missing)
        }
        val saved = setting.trust(if (on) pinned.first else null)
        return if (saved) AddressIndexSwitch.Done else AddressIndexSwitch.NotSaved
    }
}

/**
 * Keeps the engine's addr1 reader to the user's choice, for as long as it runs (the composition root's app-wide work):
 * the importer keys [pinned] in the release, each for its network, while the user's choice ([setting]) wants them
 * ([AddressIndexStatus.wanted]), and none otherwise, so that the engine reads nothing of the index, nor fetches
 * anything for it, while the setting is off. It does not wait for the engine to say whether it has a Swarm reader: the
 * setting is applied from the start, before any read, and an engine without the reader refuses it, and every read, on
 * its own. Each change is applied once, in order, and waits until the engine took it or refused it; a value that did
 * not change is not applied again. A refusal is the engine's to report on every read, so it is not acted on here.
 */
class FollowAddressIndexSetting(
    private val setting: AddressIndexSetting,
    private val pinned: AddressIndexImporters,
    private val reader: AddressIndexReaderSettings,
) {
    suspend operator fun invoke() {
        setting.observe()
            .map { choice ->
                if (AddressIndexStatus(pinned, null, choice).wanted) pinned else AddressIndexImporters.NONE
            }
            .distinctUntilChanged()
            .collect { reader.apply(it) }
    }
}
