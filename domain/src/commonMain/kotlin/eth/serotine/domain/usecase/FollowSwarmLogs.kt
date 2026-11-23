package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.SwarmDeployment
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.IndexedLog
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.LogCoverage
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatch
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import eth.serotine.domain.repository.SwarmCandidate
import eth.serotine.domain.repository.SwarmCandidateList
import eth.serotine.domain.repository.SwarmCandidates
import eth.serotine.domain.repository.SwarmContracts
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate

/**
 * Finds Swarm nodes' chequebooks and batches in the engine's verified log index (docs/swarm.md,
 * *Where the candidates come from*), whatever screen is open: the hosts run it while the engine
 * runs, for Gnosis Chain, never `App`.
 *
 * - **A chequebook.** Every xBZZ `Transfer` an EOA of the wallet sent (its own log-index entry,
 *   [FollowAccountLogs]'s mention of it, the EOA as topic 1) names a recipient: other than the
 *   PostageStamp, a chequebook candidate.
 * - **A batch.** While a Swarm node is in the wallet (an EOA with a candidate kept), the index also
 *   follows the PostageStamp as an emitter, from the earliest start among those nodes' entries;
 *   with none left, the entry is dropped. Each `BatchCreated` it holds names its batch's owner in
 *   its data; one that is an EOA of the wallet makes the batch a candidate. That finds a batch
 *   bought for the node by anyone.
 * - **Proven before it is kept.** Each candidate goes through [AddSwarmCandidate]: kept only once
 *   the light client proves it the account's. At most [MAX_PROOFS_PER_ROUND] are proven a round,
 *   the newest first; the rest wait for the next round. One proven not to be the account's is not
 *   tried again while this runs; one that could not be verified is tried again next round. One the
 *   user removed ([SwarmCandidateList.Loaded.dismissed]) is never added back.
 * - **Only what the index covers.** Each entry's covered range is read, from its start, never past
 *   it; a range not covered yet is never taken as "nothing there". What was read is not read again
 *   while this runs, unless the entry starts anew (dropped and followed again).
 *
 * A change of the index is asked with [downloads]' pause, as [FollowAccountLogs] asks its own: the
 * engine keeps the pause asked last for every entry. Runs on Gnosis Chain, where Bee's contracts
 * are pinned.
 */
class FollowSwarmLogs(
    private val accounts: AccountRepository,
    private val candidates: SwarmCandidates,
    private val contracts: SwarmContracts,
    private val lightClient: LightClient,
    private val index: LogIndex,
    private val downloads: ObserveHeavyDownloads,
) {
    private val network = SWARM_NETWORK
    private val add = AddSwarmCandidate(candidates, contracts)

    /** The PostageStamp's entry's start as last asked of the engine; null when not asked, or dropped. */
    private var emitterFrom: Long? = null

    /** What each entry's logs were read over so far, with the start the entry had then. */
    private val read = mutableMapOf<LogWatchKey, ReadSpan>()

    /** Candidates found and not yet proven either way, per account, the newest last. */
    private val pending = mutableMapOf<AccountId, LinkedHashSet<SwarmCandidate>>()

    /** Candidates proven not to be the account's, never tried again while this runs. */
    private val refused = mutableSetOf<Pair<AccountId, SwarmCandidate>>()

    /** Runs until cancelled. */
    suspend operator fun invoke() {
        val deployment = contracts.deployment(network) ?: return
        combine(
            accounts.observeAccounts(),
            candidates.observeNodes(network),
            index.observeCoverage(network),
            lightClient.observeStatus(network),
            downloads(),
            ::Round,
        ).conflate().collect { round -> follow(round, deployment) }
    }

    private suspend fun follow(round: Round, deployment: SwarmDeployment) {
        // A stopped light client's chain is created anew at its next start: everything is asked again.
        if (round.status == LightClientStatus.Off || round.status == LightClientStatus.Idle) {
            emitterFrom = null
            read.clear()
            return
        }
        if (round.status !is LightClientStatus.Synced) return
        val eoas = (round.accounts as? AccountList.Loaded)?.accounts?.filter(::canBeSwarmNode) ?: return
        val nodes = round.nodes ?: return
        val coverage = (round.coverage as? LogCoverageReading.Read)?.entries ?: return
        forgetWhatLeft(eoas)
        followTheStamp(eoas, nodes, coverage, deployment, !round.downloads.mayRun)
        for (account in eoas) {
            chequebooksOf(account, coverage, deployment).forEach { found(account, it) }
        }
        val byAddress = eoas.associateBy { it.address }
        batchesIn(coverage, deployment).forEach { (owner, id) ->
            byAddress[owner]?.let { found(it, SwarmCandidate.Batch(id)) }
        }
        prove(eoas, nodes)
    }

    /** Drops what is held for accounts that left the wallet: their entries may start anew later. */
    private fun forgetWhatLeft(eoas: List<Account>) {
        val listed = eoas.map { it.address }.toSet()
        read.keys.removeAll { it is LogWatchKey.Mention && it.address !in listed }
        pending.keys.removeAll { it.address !in listed }
        refused.removeAll { (id, _) -> id.address !in listed }
    }

    /** Notes [candidate] for [account], unless it was proven not to be the account's. */
    private fun found(account: Account, candidate: SwarmCandidate) {
        if (account.id to candidate in refused) return
        val queue = pending.getOrPut(account.id) { LinkedHashSet() }
        // Found again: moved to the newest.
        queue.remove(candidate)
        queue.add(candidate)
    }

    /** Proves up to [MAX_PROOFS_PER_ROUND] pending candidates, the newest first; keeps what is the account's. */
    private suspend fun prove(eoas: List<Account>, nodes: Map<AccountId, SwarmCandidateList.Loaded>) {
        var budget = MAX_PROOFS_PER_ROUND
        for (account in eoas) {
            val queue = pending[account.id] ?: continue
            val kept = nodes[account.id]
            for (candidate in queue.toList().asReversed()) {
                if (kept != null && (candidate in kept.dismissed || kept.holds(candidate))) {
                    queue.remove(candidate)
                    continue
                }
                if (budget == 0) return
                budget--
                when (add(account, candidate)) {
                    SwarmAddOutcome.Added, SwarmAddOutcome.AlreadyAdded -> queue.remove(candidate)
                    // Not verified this time, or not saved: tried again next round.
                    is SwarmAddOutcome.NotVerified, SwarmAddOutcome.NotSaved -> Unit
                    SwarmAddOutcome.NotASwarmAccount, SwarmAddOutcome.NotAChequebook, SwarmAddOutcome.NoSuchBatch,
                    is SwarmAddOutcome.OthersChequebook, is SwarmAddOutcome.OthersBatch,
                    -> {
                        queue.remove(candidate)
                        refused += account.id to candidate
                    }
                }
            }
        }
    }

    /** Asks the engine to follow the PostageStamp while a node is in the wallet, and to drop it after. */
    private suspend fun followTheStamp(
        eoas: List<Account>,
        nodes: Map<AccountId, SwarmCandidateList.Loaded>,
        coverage: Map<LogWatchKey, LogCoverage>,
        deployment: SwarmDeployment,
        walkPaused: Boolean,
    ) {
        val key = LogWatchKey.Emitter(deployment.postageStamp)
        val swarmNodes = eoas.filter { nodes[it.id]?.keepsAny == true }
        if (swarmNodes.isEmpty()) {
            // Dropped with the last node, an entry kept from an earlier run included.
            val followed = emitterFrom != null || key in coverage
            if (followed && index.configure(network, emptyList(), listOf(key), walkPaused)) {
                emitterFrom = null
                read.remove(key)
            }
            return
        }
        // From the earliest start among the nodes' own entries; a node whose start is not fixed yet waits.
        val from = swarmNodes.mapNotNull { coverage[LogWatchKey.Mention(it.address)]?.fromBlock }.minOrNull() ?: return
        val asked = emitterFrom
        if (asked != null && asked <= from) return
        if (index.configure(network, listOf(LogWatch(key, from)), emptyList(), walkPaused)) emitterFrom = from
    }

    /** The chequebook candidates [account]'s own xBZZ transfers name, read over what is covered, oldest first. */
    private suspend fun chequebooksOf(
        account: Account,
        coverage: Map<LogWatchKey, LogCoverage>,
        deployment: SwarmDeployment,
    ): List<SwarmCandidate.ChequebookAt> {
        val key = LogWatchKey.Mention(account.address)
        return newLogs(key, coverage[key], listOf(TRANSFER), position = 1)
            .filter { it.log.address == deployment.bzz && it.log.topics.size == 3 }
            .mapNotNull { topicAddress(it.log.topics[2]) }
            .filter { it != deployment.postageStamp && it != account.address }
            .map { SwarmCandidate.ChequebookAt(it) }
    }

    /** Each `BatchCreated` the PostageStamp's entry holds over what is covered: its owner and id. */
    private suspend fun batchesIn(
        coverage: Map<LogWatchKey, LogCoverage>,
        deployment: SwarmDeployment,
    ): List<Pair<Address, BatchId>> {
        val key = LogWatchKey.Emitter(deployment.postageStamp)
        if (emitterFrom == null && key !in coverage) return emptyList()
        return newLogs(key, coverage[key], listOf(BATCH_CREATED), position = null)
            .filter { it.log.address == deployment.postageStamp }
            .mapNotNull { batchCreated(it) }
    }

    /**
     * The logs of [key]'s entry over the part of its [coverage] not read yet, from its start, oldest
     * first; none when nothing is covered, or the index cannot answer now (that part is read again
     * next round). An entry that starts anew is read again from scratch.
     */
    private suspend fun newLogs(
        key: LogWatchKey,
        coverage: LogCoverage?,
        topic0s: List<String>,
        position: Int?,
    ): List<IndexedLog> {
        val covered = coverage?.covered ?: return emptyList()
        val first = maxOf(covered.first, coverage.fromBlock)
        if (first > covered.last) return emptyList()
        val span = first..covered.last
        // What was read stands only for the same start, and only inside what is covered now.
        val done = read[key]
            ?.takeIf { it.fromBlock == coverage.fromBlock }
            ?.range
            ?.takeIf { it.first >= span.first && it.last <= span.last }
        val parts = if (done == null) {
            listOf(span)
        } else {
            listOfNotNull(
                (span.first until done.first).takeUnless { it.isEmpty() },
                (done.last + 1..span.last).takeUnless { it.isEmpty() },
            )
        }
        val logs = mutableListOf<IndexedLog>()
        var reached = done
        for (part in parts) {
            val reading = index.logs(network, LogQuery(key, part.first, part.last, topic0s, position))
            if (reading !is LogsReading.Logs) continue
            logs += reading.logs
            reached = reached?.let { minOf(it.first, part.first)..maxOf(it.last, part.last) } ?: part
        }
        if (reached != null) read[key] = ReadSpan(coverage.fromBlock, reached) else read.remove(key)
        return logs.sortedWith(compareBy({ it.blockNumber }, { it.logIndex }))
    }

    private fun SwarmCandidateList.Loaded.holds(candidate: SwarmCandidate): Boolean = when (candidate) {
        is SwarmCandidate.ChequebookAt -> candidate.address in chequebooks
        is SwarmCandidate.Batch -> candidate.id in batches
    }

    /** The blocks [range] of an entry read while it started at [fromBlock]. */
    private data class ReadSpan(val fromBlock: Long, val range: LongRange)

    private class Round(
        val accounts: AccountList,
        val nodes: Map<AccountId, SwarmCandidateList.Loaded>?,
        val coverage: LogCoverageReading,
        val status: LightClientStatus,
        val downloads: HeavyDownloads,
    )

    internal companion object {
        /** ERC-20's `Transfer(address indexed from, address indexed to, uint256 value)`. */
        const val TRANSFER = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

        /**
         * The PostageStamp's `BatchCreated(bytes32 indexed batchId, uint256 totalAmount, uint256
         * normalisedBalance, address owner, uint8 depth, uint8 bucketDepth, bool immutableFlag)`.
         */
        const val BATCH_CREATED = "0x9b088e2c89b322a3c1d81515e1c88db3d386d022926f0e2d0b9b5813b7413d58"

        /**
         * Candidates proven a round, the newest first: an account that pays many in xBZZ does not
         * cost a proof each at once. The rest wait for the next round, none dropped.
         */
        const val MAX_PROOFS_PER_ROUND = 16

        /** The address an indexed address topic holds, or null when its upper 12 bytes are not zero. */
        fun topicAddress(topic: String): Address? {
            val hex = topic.removePrefix("0x")
            if (hex.length != 64 || hex.take(24).any { it != '0' }) return null
            return Address.parse("0x" + hex.drop(24))
        }

        /** A `BatchCreated` log's owner (its data's third word) and batch id (topic 1); null when malformed. */
        fun batchCreated(log: IndexedLog): Pair<Address, BatchId>? {
            val topics = log.log.topics
            if (topics.size != 2 || topics[0] != BATCH_CREATED) return null
            val data = log.log.data.removePrefix("0x")
            if (data.length != 6 * 64) return null
            val owner = topicAddress(data.substring(2 * 64, 3 * 64)) ?: return null
            val id = BatchId.parse(topics[1]) ?: return null
            return owner to id
        }
    }
}
