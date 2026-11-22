package eth.serotine.data.settings

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AddressIndexChoice
import eth.serotine.domain.repository.AddressIndexSetting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [AddressIndexSetting] kept in the file at [path] (a [VersionedJsonFile]): the importer key the user turned the
 * experimental addr1 history on for, or none, an explicit off. No file is no choice ([AddressIndexChoice.NotChosen]),
 * which is on by default (owner's decision, 2026-11-22). A damaged file, a key that is not an address among it, is set
 * aside and an explicit off written in its place (it may have been one), so it reads as off, after a restart too, and
 * says so until the user makes a choice ([AddressIndexChoice.Chosen.setAside]); one that cannot be read is
 * [AddressIndexChoice.Unreadable], off too. It stores a key the user accepted, never one typed in: what may be
 * accepted is the use case's to say (`TurnAddressIndex`, the key pinned in the release).
 */
class FileAddressIndexSetting(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AddressIndexSetting {

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "addr1 history setting",
        formatVersion = FORMAT_VERSION,
        empty = Saved(chosen = false, importer = null),
        decode = { _, text ->
            val stored = JSON.decodeFromString(StoredChoice.serializer(), text)
            // A key that is not an address is damage, set aside: never read as "off" silently, nor as some key.
            val importer = stored.importer?.let { requireNotNull(Address.parse(it)) }
            // Only an off is ever written for a damaged choice.
            require(!stored.setAside || importer == null) { "a damaged choice is replaced by an off only" }
            Saved(chosen = true, importer = importer, setAside = stored.setAside)
        },
        encode = { saved ->
            JSON.encodeToString(
                StoredChoice.serializer(),
                StoredChoice(FORMAT_VERSION, saved.importer?.hex, setAside = saved.setAside),
            )
        },
    )

    override fun observe(): Flow<AddressIndexChoice> = file.observe()
        .onEach { read ->
            // A damaged choice may have been an explicit off: off is written in its place, so that the next start
            // reads off too, never the default (the set-aside file holds nothing any more). Once written, the file
            // holds a choice, and this asks nothing again.
            if (read is VersionedJsonFile.Read.Loaded && read.damagedSetAside && !read.value.chosen) {
                file.change(notSaved = Unit) { current ->
                    // Unless a choice was made meanwhile: that one stands.
                    val off = Saved(chosen = true, importer = null, setAside = true)
                    Decision(Unit, next = off.takeUnless { current.chosen })
                }
            }
        }
        .map { read ->
            when (read) {
                is VersionedJsonFile.Read.Loaded -> when {
                    // The off written for a damaged choice says so, until the user makes a choice.
                    read.value.chosen -> AddressIndexChoice.Chosen(read.value.importer, setAside = read.value.setAside)
                    // Damaged, before the off is written: off already.
                    read.damagedSetAside -> AddressIndexChoice.Chosen(importer = null, setAside = true)
                    else -> AddressIndexChoice.NotChosen
                }
                is VersionedJsonFile.Read.Unreadable -> AddressIndexChoice.Unreadable
            }
        }

    override suspend fun trust(importer: Address?): Boolean =
        file.change(notSaved = false) { _ -> Decision(true, next = Saved(chosen = true, importer = importer)) }

    /**
     * What the file holds: whether a choice was ever [chosen], and the key it was turned on for, null for off;
     * [setAside] for the off written in place of a damaged choice, until the user makes one.
     */
    private data class Saved(val chosen: Boolean, val importer: Address?, val setAside: Boolean = false)

    /**
     * The file's layout. [version] comes first and is always written; [importer] is null while it is off; [setAside]
     * is written, true, only for the off that replaced a damaged choice.
     */
    @Serializable
    private class StoredChoice(val version: Int, val importer: String? = null, val setAside: Boolean = false)

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json
    }
}
