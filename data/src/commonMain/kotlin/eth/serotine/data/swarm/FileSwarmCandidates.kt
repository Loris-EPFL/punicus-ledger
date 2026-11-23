package eth.serotine.data.swarm

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.SwarmCandidate
import eth.serotine.domain.repository.SwarmCandidateChange
import eth.serotine.domain.repository.SwarmCandidateList
import eth.serotine.domain.repository.SwarmCandidates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [SwarmCandidates] kept in the file at [path] (a [VersionedJsonFile]), an app-private file of
 * public data (docs/swarm.md, *Where the candidates come from*): per node, by its network's chain
 * id and its address, the chequebook addresses and batch ids that may be its own. Nothing here is
 * chain data, nor key material.
 *
 * A damaged file is set aside and the candidates start empty, saying so
 * ([SwarmCandidateList.Loaded.setAside]); one that cannot be read is [SwarmCandidateList.Unreadable],
 * and nothing changes until it can be read. A node on a network this version does not know is
 * passed over and the file kept aside as it was, so it is never lost.
 */
class FileSwarmCandidates(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SwarmCandidates {

    /** One node's candidates, each list in the order added. */
    private data class Kept(val chequebooks: List<Address> = emptyList(), val batches: List<BatchId> = emptyList())

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "Swarm candidates",
        formatVersion = FORMAT_VERSION,
        empty = emptyMap<AccountId, Kept>(),
        decode = { _, text ->
            JSON.decodeFromString(StoredCandidates.serializer(), text).nodes.mapNotNull { it.toKept() }.toMap()
        },
        encode = { all ->
            StoredCandidates(
                FORMAT_VERSION,
                all.map { (node, kept) ->
                    StoredNode(
                        node.network.chainId,
                        node.address.hex,
                        kept.chequebooks.map { it.hex },
                        kept.batches.map { it.hex },
                    )
                },
            ).let { JSON.encodeToString(StoredCandidates.serializer(), it) }
        },
        incomplete = { text, all -> JSON.decodeFromString(StoredCandidates.serializer(), text).nodes.size > all.size },
    )

    override fun observe(node: AccountId): Flow<SwarmCandidateList> = file.observe().map { read ->
        when (read) {
            is VersionedJsonFile.Read.Loaded -> {
                val kept = read.value[node] ?: Kept()
                SwarmCandidateList.Loaded(kept.chequebooks, kept.batches, setAside = read.damagedSetAside)
            }
            is VersionedJsonFile.Read.Unreadable -> SwarmCandidateList.Unreadable
        }
    }.distinctUntilChanged()

    override fun observeNodes(network: Network): Flow<Map<AccountId, SwarmCandidateList.Loaded>?> =
        file.observe().map { read ->
            when (read) {
                is VersionedJsonFile.Read.Loaded -> read.value.filterKeys { it.network == network }
                    .mapValues { (_, kept) -> SwarmCandidateList.Loaded(kept.chequebooks, kept.batches) }
                is VersionedJsonFile.Read.Unreadable -> null
            }
        }.distinctUntilChanged()

    override suspend fun add(node: AccountId, candidate: SwarmCandidate): SwarmCandidateChange =
        file.change(SwarmCandidateChange.NOT_SAVED) { all ->
            val kept = all[node] ?: Kept()
            val next = when (candidate) {
                is SwarmCandidate.ChequebookAt ->
                    if (candidate.address in kept.chequebooks) null
                    else kept.copy(chequebooks = kept.chequebooks + candidate.address)
                is SwarmCandidate.Batch ->
                    if (candidate.id in kept.batches) null else kept.copy(batches = kept.batches + candidate.id)
            }
            if (next == null) {
                Decision(SwarmCandidateChange.ALREADY_PRESENT)
            } else {
                Decision(SwarmCandidateChange.CHANGED, all + (node to next))
            }
        }

    override suspend fun remove(node: AccountId, candidate: SwarmCandidate): SwarmCandidateChange =
        file.change(SwarmCandidateChange.NOT_SAVED) { all ->
            val kept = all[node] ?: return@change Decision(SwarmCandidateChange.NOT_FOUND)
            val next = when (candidate) {
                is SwarmCandidate.ChequebookAt ->
                    kept.copy(chequebooks = kept.chequebooks - candidate.address).takeIf { it != kept }
                is SwarmCandidate.Batch -> kept.copy(batches = kept.batches - candidate.id).takeIf { it != kept }
            } ?: return@change Decision(SwarmCandidateChange.NOT_FOUND)
            // A node with nothing left leaves the file: no address is kept without a reason.
            val rest = if (next.chequebooks.isEmpty() && next.batches.isEmpty()) all - node else all + (node to next)
            Decision(SwarmCandidateChange.CHANGED, rest)
        }

    /** The file's layout: [version] first, then each node with its candidates. */
    @Serializable
    private class StoredCandidates(val version: Int, val nodes: List<StoredNode>)

    @Serializable
    private class StoredNode(
        val chain: Long,
        val address: String,
        val chequebooks: List<String> = emptyList(),
        val batches: List<String> = emptyList(),
    ) {
        /**
         * Throws on anything [Address] or [BatchId] would not accept: the whole file is then
         * damaged. Null for a node on a network this version does not know.
         */
        fun toKept(): Pair<AccountId, Kept>? {
            val network = Network.ofChainId(chain) ?: return null
            return AccountId(network, Address.of(address)) to
                Kept(chequebooks.map(Address::of).distinct(), batches.map(BatchId::of).distinct())
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json
    }
}
