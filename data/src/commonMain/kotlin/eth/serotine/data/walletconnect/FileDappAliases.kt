package eth.serotine.data.walletconnect

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppHost
import eth.serotine.domain.model.AppHostResult
import eth.serotine.domain.repository.PerAppAliases
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [PerAppAliases] kept in the file at [path] (a [VersionedJsonFile]): each aliased site with the per-app address it
 * sees, sites and addresses only (issue #252; docs/walletconnect.md, *Aliases*). App-private like the sessions' state
 * and the sites' memory beside it: the host keeps the file out of backups and device transfers, as it does theirs
 * (ruling 8). A file that cannot be used is handled as [VersionedJsonFile] says, and reads as no alias: no site is then
 * offered another's address. A site this version cannot read is passed over and the file kept aside as it was, so it
 * is never lost.
 */
class FileDappAliases(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PerAppAliases {

    private val file = VersionedJsonFile<Map<AppHost, Address>>(
        path,
        dispatcher,
        what = "per-app aliases",
        formatVersion = FORMAT_VERSION,
        empty = emptyMap(),
        decode = { _, text -> decoded(JSON.decodeFromString(StoredAliases.serializer(), text)) },
        encode = { aliases ->
            val stored = StoredAliases(
                FORMAT_VERSION,
                aliases.map { (site, address) -> StoredAlias(site.name, address.hex) },
            )
            JSON.encodeToString(StoredAliases.serializer(), stored)
        },
        incomplete = { text, aliases ->
            JSON.decodeFromString(StoredAliases.serializer(), text).aliases.size > aliases.size
        },
    )

    override fun observe(): Flow<Map<AppHost, Address>> = file.observe().map { read ->
        (read as? VersionedJsonFile.Read.Loaded)?.value.orEmpty()
    }

    override suspend fun link(site: AppHost, address: Address): Boolean =
        file.change(notSaved = false) { aliases -> Decision(true, aliases + (site to address)) }

    override suspend fun unlink(site: AppHost): Boolean =
        file.change(notSaved = false) { aliases -> if (site in aliases) Decision(true, aliases - site) else Decision(true) }

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredAliases(val version: Int, val aliases: List<StoredAlias>)

    /** The aliased [site] and the per-app [address] it sees. */
    @Serializable
    private class StoredAlias(val site: String, val address: String)

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json

        /** What [stored] holds that this version reads; an address that is none damages the file. */
        fun decoded(stored: StoredAliases): Map<AppHost, Address> = stored.aliases.mapNotNull { entry ->
            val site = (AppHost.parse(entry.site) as? AppHostResult.Host)?.host ?: return@mapNotNull null
            site to Address.of(entry.address)
        }.toMap()
    }
}
