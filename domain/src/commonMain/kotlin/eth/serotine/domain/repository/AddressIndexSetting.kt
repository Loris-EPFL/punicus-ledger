package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import kotlinx.coroutines.flow.Flow

/**
 * The port for the experimental setting that lets an account's *Activity* also take the addr1 index from Swarm, on
 * the desktop and Android (owner ruling, 2026-11-21, #334, the owner's decisions of that day on #335, and of
 * 2026-11-24 and 2026-11-25 for Android;
 * `.claude/rules/data-sources.md`, *The history may also take the addr1 index from Swarm*). On by default (owner's
 * decision, 2026-11-22): until the user chooses, it holds no choice ([AddressIndexChoice.NotChosen]), which
 * `eth.serotine.domain.usecase.AddressIndexStatus` reads as on. A choice is the importer key the user turned it on
 * for, or none, an explicit off, which stays off. The key itself is pinned in the release and never typed by the
 * user; this port only remembers which pinned key the user accepted. :data keeps it in a file, so it survives a
 * restart. Never throws.
 */
interface AddressIndexSetting {
    /** The choice, re-emitted on each change, or that it cannot be read. Never throws. */
    fun observe(): Flow<AddressIndexChoice>

    /**
     * Turns the setting on for [importer], the key pinned in the release, or off (null). False when not saved, and
     * nothing changed.
     */
    suspend fun trust(importer: Address?): Boolean
}

/** What [AddressIndexSetting.observe] emits. */
sealed interface AddressIndexChoice {
    /** No choice was ever made (a fresh install): on by default. */
    data object NotChosen : AddressIndexChoice

    /**
     * The key the user turned the setting on for ([importer]), or null: the user turned it off. [setAside] when the
     * stored choice was found damaged and set aside: read as off, since it may have been one, until another is made.
     */
    data class Chosen(val importer: Address?, val setAside: Boolean = false) : AddressIndexChoice

    /** A choice is stored but cannot be read: taken as off, and nothing can be chosen until it can. */
    data object Unreadable : AddressIndexChoice
}
