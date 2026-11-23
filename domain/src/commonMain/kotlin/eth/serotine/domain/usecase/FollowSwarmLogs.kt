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
 * - **A batch the wallet bought** (owner's decision, 2026-11-23). While a Swarm node is in the
 *   wallet (an EOA with a candidate kept), or an account of the wallet paid the PostageStamp in
 *   xBZZ (it bought a batch), the index also follows the PostageStamp as an emitter, from the
 *   earliest start among those accounts' entries; with none left, the entry is dropped. A
 *   `BatchCreated` it holds makes its batch a candidate only when its owner, in its data, is an EOA
 *   of the wallet AND its transaction also holds an xBZZ `Transfer` to the PostageStamp from an
 *   account of the wallet: the wallet paid for it. `createBatch` takes any owner, so anyone can
 *   create a batch that names an account of the wallet; such a batch is added by hand, never by
 *   this.
 * - **Proven before it is kept.** Each candidate goes through [AddSwarmCandidate] as found
 *   ([AddSwarmCandidate.invoke] with `byHand` false): kept only once the light client proves it
 *   the account's, and never one the user removed. At most [MAX_PROOFS_PER_ROUND] are proven a
 *   round, the newest first; the rest wait for the next round. One that could not be verified is
 *   tried again next round. One proven not to be the account's is not tried again while this
 *   runs, unless a log of a later block names it again (its transaction mined anew).
 * - **Only what the index covers.** Each entry's covered range is read, from its start, never past
 *   it; a range not covered yet is never taken as "nothing there". What was read is not read again
 *   while this runs, but the top [REORG_DEPTH] blocks of it, which a reorganisation can still
 *   replace, are read again as the span grows: coverage is polled, so a rewind and its re-walk
 *   between two polls look like growth. An entry that starts anew is read again from scratch, and
 *   so is the PostageStamp's when an EOA joins the wallet.
 * - **An account that leaves the wallet** takes its candidates and removals with it
 *   ([SwarmCandidates.forget]), as [FollowAccountLogs] forgets its start.
 * - **Nothing outlives a run.** What was read, and what was proven not to be an account's, live
 *   in this instance: each start of the hosts' work reads the covered spans again (local, and
 *   cheap) and proves again what is neither kept nor removed, 16 a round (verified reads).
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

    /** The EOAs listed at the last round: one that joins has the PostageStamp's logs matched anew. */
    private var listedEoas: Set<Address> = emptySet()

    /** Transactions in which an account of the wallet paid the PostageStamp in xBZZ. */
    private val paid = mutableSetOf<String>()

    /** The accounts of the wallet seen paying the PostageStamp: the PostageStamp is followed for them. */
    private val payers = mutableSetOf<Address>()

    /** `BatchCreated`s for an EOA of the wallet whose transaction no payment of the wallet is known in yet. */
    private val unpaid = mutableMapOf<String, MutableSet<FoundBatch>>()

    /** Candidates found and not yet proven either way, per account, the newest last, with their log's block. */
    private val pending = mutableMapOf<AccountId, LinkedHashMap<SwarmCandidate, Long>>()

    /**
     * Candidates proven not to be the account's, with the newest block a log named them in then;
     * never tried again while this runs, unless a log of a later block names them.
     */
    private val refused = mutableMapOf<Pair<AccountId, SwarmCandidate>, Long>()

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
        val listed = (round.accounts as? AccountList.Loaded)?.accounts?.filter { it.network == network } ?: return
        val eoas = listed.filter(::canBeSwarmNode)
        val nodes = round.nodes ?: return
        val coverage = (round.coverage as? LogCoverageReading.Read)?.entries ?: return
        forgetWhatLeft(listed, eoas, deployment)
        // An account that left the wallet takes its candidates and removals with it.
        val addresses = listed.map { it.address }.toSet()
        nodes.keys.filter { it.address !in addresses }.forEach { candidates.forget(it) }
        val eoaIds = eoas.map { it.id }.toSet()
        for (account in listed) {
            for (transfer in xbzzSentBy(account, coverage, deployment)) {
                if (transfer.to == deployment.postageStamp) {
                    paid += transfer.transaction
                    payers += account.address
                } else if (account.id in eoaIds) {
                    found(account, SwarmCandidate.ChequebookAt(transfer.to), transfer.block)
                }
            }
        }
        followTheStamp(listed, nodes, coverage, deployment, !round.downloads.mayRun)
        val byAddress = eoas.associateBy { it.address }
        batchesIn(coverage, deployment).filter { it.owner in byAddress }.forEach {
            unpaid.getOrPut(it.transaction) { mutableSetOf() } += it
        }
        // Only a batch the wallet paid for (owner's decision, 2026-11-23).
        unpaid.keys.filter { it in paid }.forEach { transaction ->
            unpaid.remove(transaction)?.forEach { batch ->
                byAddress[batch.owner]?.let { found(it, SwarmCandidate.Batch(batch.id), batch.block) }
            }
        }
        prove(eoas, nodes)
    }

    /**
     * Drops what is held for accounts that left the wallet, whose entries may start anew later;
     * and, when an EOA joined, what was read of the PostageStamp's, so that its batches are matched.
     */
    private fun forgetWhatLeft(listed: List<Account>, eoas: List<Account>, deployment: SwarmDeployment) {
        val addresses = listed.map { it.address }.toSet()
        read.keys.removeAll { it is LogWatchKey.Mention && it.address !in addresses }
        pending.keys.removeAll { it.address !in addresses }
        payers.retainAll(addresses)
        refused.keys.removeAll { (id, _) -> id.address !in addresses }
        val now = eoas.map { it.address }.toSet()
        if (!listedEoas.containsAll(now)) read.remove(LogWatchKey.Emitter(deployment.postageStamp))
        unpaid.values.forEach { batches -> batches.removeAll { it.owner !in now } }
        unpaid.values.removeAll { it.isEmpty() }
        listedEoas = now
    }

    /** Notes [candidate] for [account], named by a log of [block], unless it was proven not to be the account's. */
    private fun found(account: Account, candidate: SwarmCandidate, block: Long) {
        val refusal = account.id to candidate
        val refusedAt = refused[refusal]
        if (refusedAt != null) {
            // Proven before against a log no later than this one: nothing new.
            if (block <= refusedAt) return
            refused.remove(refusal)
        }
        val queue = pending.getOrPut(account.id) { LinkedHashMap() }
        val newest = maxOf(block, queue.remove(candidate) ?: block)
        // Found again: moved to the newest.
        queue[candidate] = newest
    }

    /** Proves up to [MAX_PROOFS_PER_ROUND] pending candidates, the newest first; keeps what is the account's. */
    private suspend fun prove(eoas: List<Account>, nodes: Map<AccountId, SwarmCandidateList.Loaded>) {
        var budget = MAX_PROOFS_PER_ROUND
        for (account in eoas) {
            val queue = pending[account.id] ?: continue
            val kept = nodes[account.id]
            // Copied as pairs first: a map's entry is a live view on Kotlin/Native, and the queue
            // changes below.
            for ((candidate, block) in queue.map { (key, value) -> key to value }.asReversed()) {
                if (kept != null && (candidate in kept.dismissed || kept.holds(candidate))) {
                    queue.remove(candidate)
                    continue
                }
                if (budget == 0) return
                budget--
                when (add(account, candidate, byHand = false)) {
                    SwarmAddOutcome.Added, SwarmAddOutcome.AlreadyAdded, SwarmAddOutcome.Dismissed ->
                        queue.remove(candidate)
                    // Not verified this time, or not saved: tried again next round.
                    is SwarmAddOutcome.NotVerified, SwarmAddOutcome.NotSaved -> Unit
                    SwarmAddOutcome.NotASwarmAccount, SwarmAddOutcome.NotAChequebook, SwarmAddOutcome.NoSuchBatch,
                    is SwarmAddOutcome.OthersChequebook, is SwarmAddOutcome.OthersBatch,
                    -> {
                        queue.remove(candidate)
                        refused[account.id to candidate] = block
                    }
                }
            }
        }
    }

    /**
     * Asks the engine to follow the PostageStamp while a node is in the wallet, or an account of
     * it paid the PostageStamp, and to drop it after.
     */
    private suspend fun followTheStamp(
        listed: List<Account>,
        nodes: Map<AccountId, SwarmCandidateList.Loaded>,
        coverage: Map<LogWatchKey, LogCoverage>,
        deployment: SwarmDeployment,
        walkPaused: Boolean,
    ) {
        val key = LogWatchKey.Emitter(deployment.postageStamp)
        val swarmNodes = listed.filter {
            (canBeSwarmNode(it) && nodes[it.id]?.keepsAny == true) || it.address in payers
        }
        if (swarmNodes.isEmpty()) {
            // Dropped with the last of them, an entry kept from an earlier run included.
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

    /** The xBZZ [account] sent, as its own entry holds it over what is covered, oldest first. */
    private suspend fun xbzzSentBy(
        account: Account,
        coverage: Map<LogWatchKey, LogCoverage>,
        deployment: SwarmDeployment,
    ): List<Sent> {
        val key = LogWatchKey.Mention(account.address)
        return newLogs(key, coverage[key], listOf(TRANSFER), position = 1)
            .filter { it.log.address == deployment.bzz.address && it.log.topics.size == 3 }
            .mapNotNull { log ->
                topicAddress(log.log.topics[2])
                    ?.takeIf { it != account.address }
                    ?.let { Sent(it, log.transactionHash, log.blockNumber) }
            }
    }

    /** Each `BatchCreated` the PostageStamp's entry holds over what is covered. */
    private suspend fun batchesIn(
        coverage: Map<LogWatchKey, LogCoverage>,
        deployment: SwarmDeployment,
    ): List<FoundBatch> {
        val key = LogWatchKey.Emitter(deployment.postageStamp)
        if (emitterFrom == null && key !in coverage) return emptyList()
        return newLogs(key, coverage[key], listOf(BATCH_CREATED), position = null)
            .filter { it.log.address == deployment.postageStamp }
            .mapNotNull { log ->
                batchCreated(log)?.let { (owner, id) -> FoundBatch(owner, id, log.transactionHash, log.blockNumber) }
            }
    }

    /**
     * The logs of [key]'s entry over the part of its [coverage] not read yet, from its start, and
     * the top [REORG_DEPTH] blocks read before again, oldest first; none when nothing is covered,
     * or the index cannot answer now (that part is read again next round). An entry that starts
     * anew is read again from scratch.
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
            // The blocks a reorganisation can still replace are read again, as the span grows.
            val again = maxOf(done.first, done.last - REORG_DEPTH + 1)
            listOfNotNull(
                (span.first until done.first).takeUnless { it.isEmpty() },
                (again..span.last).takeUnless { it.isEmpty() || span.last == done.last },
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

    /** xBZZ sent [to] an address, in [transaction], mined in [block]. */
    private data class Sent(val to: Address, val transaction: String, val block: Long)

    /** A `BatchCreated` of [id] for [owner], in [transaction], mined in [block]. */
    private data class FoundBatch(val owner: Address, val id: BatchId, val transaction: String, val block: Long)

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

        /** The top blocks of a span read before that are read again as it grows, as `IndexedReader` does. */
        const val REORG_DEPTH = 64L

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
