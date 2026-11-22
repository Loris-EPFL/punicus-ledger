package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AddressIndexChoice
import eth.serotine.domain.repository.AddressIndexHistory
import eth.serotine.domain.repository.AddressIndexSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * What is missing for the addr1 history's setting to be turned on (owner ruling, 2026-10-18, #55: a missing
 * prerequisite greys the setting out, and a tap says what is missing).
 */
enum class AddressIndexMissing {
    /** No importer key is pinned in this release: the setting has no key to name, and the user types none in. */
    NO_KEY_PINNED,

    /** This build's engine has no Swarm reader ([AddressIndexHistory.hasReader] false). */
    NO_READER,

    /** The choice stored cannot be read, so it cannot be changed until it can. */
    CHOICE_UNREADABLE,
}

/**
 * Where the experimental addr1 history stands (owner ruling, 2026-11-21, #334; the owner's decisions of that day on
 * #335; on by default, the owner's decision of 2026-11-22): [pinned], the importer key pinned in the release (null:
 * none pinned in this one), whether the engine has its Swarm [reader], and the user's [choice]. It is [on] when a key
 * is pinned, the engine reads Swarm, and the user did not turn it off:
 * - **no choice made** ([AddressIndexChoice.NotChosen]: a fresh install): on, by default;
 * - **an explicit off** (a stored choice of no key): off, whatever key is pinned, until the user turns it on again;
 * - **on for the key pinned now**: on;
 * - **on for another key** ([trustsAnotherKey]): that choice does not carry over to the key pinned now, which no choice
 *   was made for, so it is on by default for it, as on a fresh install; only an explicit off is kept across keys;
 * - **a choice that cannot be read**, or one found damaged and set aside: off, since it may have been an explicit off,
 *   and nothing is read on a guess. Unreadable, it cannot be changed until it can be read; set aside, it is off until
 *   the user chooses again.
 */
data class AddressIndexStatus(val pinned: Address?, val reader: Boolean, val choice: AddressIndexChoice) {

    /** What keeps the setting from being turned on, or changed at all; empty when it can be. */
    val missing: Set<AddressIndexMissing>
        get() = buildSet {
            if (pinned == null) add(AddressIndexMissing.NO_KEY_PINNED)
            if (!reader) add(AddressIndexMissing.NO_READER)
            if (choice is AddressIndexChoice.Unreadable) add(AddressIndexMissing.CHOICE_UNREADABLE)
        }

    /**
     * Whether *Activity* takes the addr1 index: a key pinned, a reader to read it, and no explicit off nor a choice
     * that cannot be read or was set aside (the cases above): on by default otherwise.
     */
    val on: Boolean
        get() = missing.isEmpty() && when (choice) {
            AddressIndexChoice.NotChosen -> true
            // On for any key: for the key pinned now, or, for another one, no choice for this one (default on).
            is AddressIndexChoice.Chosen -> choice.importer != null
            AddressIndexChoice.Unreadable -> false
        }

    /** The key the setting was turned on for when that is not the key pinned now; null otherwise. */
    val trustsAnotherKey: Address?
        get() = (choice as? AddressIndexChoice.Chosen)?.importer?.takeIf { it != pinned }

    /** Whether the stored choice was damaged and set aside: off, said until another choice is made. */
    val setAside: Boolean
        get() = (choice as? AddressIndexChoice.Chosen)?.setAside == true
}

/**
 * The [AddressIndexStatus] now, re-emitted when the choice changes: [setting], the user's choice; [history], whose
 * engine says once whether it has a Swarm reader; [pinned], the importer key pinned in the release, null when this
 * release pins none (owner's decision, 2026-11-21: none is pinned yet).
 */
class ObserveAddressIndexStatus(
    private val history: AddressIndexHistory,
    private val setting: AddressIndexSetting,
    private val pinned: Address?,
) {
    operator fun invoke(): Flow<AddressIndexStatus> =
        combine(flow { emit(history.hasReader()) }, setting.observe()) { reader, choice ->
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
 * Turns the addr1 history on, for the key [pinned] in the release, or off. On needs a pinned key and an engine with a
 * Swarm reader ([history]); without either it is refused and nothing is saved, never turned on for a key nobody
 * pinned. Off always saves, so a choice made in an earlier build can always be taken back.
 */
class TurnAddressIndex(
    private val history: AddressIndexHistory,
    private val setting: AddressIndexSetting,
    private val pinned: Address?,
) {
    suspend operator fun invoke(on: Boolean): AddressIndexSwitch {
        if (on) {
            val missing = buildSet {
                if (pinned == null) add(AddressIndexMissing.NO_KEY_PINNED)
                if (!history.hasReader()) add(AddressIndexMissing.NO_READER)
            }
            if (missing.isNotEmpty()) return AddressIndexSwitch.Unavailable(missing)
        }
        return if (setting.trust(if (on) pinned else null)) AddressIndexSwitch.Done else AddressIndexSwitch.NotSaved
    }
}
