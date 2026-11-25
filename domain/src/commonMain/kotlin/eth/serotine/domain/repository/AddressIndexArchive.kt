package eth.serotine.domain.repository

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AddressIndexCoverage
import eth.serotine.domain.model.AddressIndexCursor
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexRow

/**
 * What is kept of each account's addr1 history between runs of the app (owner's request, 2026-11-25: "Make the
 * downloaded list persistent. The results will never change"), so that the Activity shows what was verified before at
 * once and reads only what is newer (`.claude/rules/data-sources.md`, *The history may also take the addr1 index from
 * Swarm*). One [KeptAddressIndex] per account, public data only: blocks, transactions and the importer's key. :data
 * keeps it in an app-private file. Never throws.
 */
interface AddressIndexArchive {

    /** What is kept of [account]'s history; null when nothing is, or when what is kept cannot be read. */
    suspend fun read(account: AccountId): KeptAddressIndex?

    /** Keeps [kept] as [account]'s, in place of what was kept before; false when it could not be written. */
    suspend fun write(account: AccountId, kept: KeptAddressIndex): Boolean

    /** Keeps nothing more of [account]'s history; false when what is kept could not be removed. */
    suspend fun forget(account: AccountId): Boolean
}

/**
 * An account's addr1 history as it was read and verified, every entry it holds an answer for by itself, so that a
 * later read neither checks nor counts any of them again: the [rows] only once their block was finalized, so that none
 * of them can change.
 *
 * - [coverage]: what the index covered at the newest read, and the importer key the list rests on;
 * - [top]: the newest block the list was read through: every entry of the index at or below it, down to where [next]
 *   goes on, was read, but in the [unread] ranges. An entry above it is new. It lies below every block whose verdict
 *   was not final yet at the read (a row of such a block, or a contradiction against it), so that those are read
 *   again from the index, and a reorganisation's corrected entries with them;
 * - [rows]: the verified rows, each of a finalized block, newest first, each entry once;
 * - [recheck]: the entries read whose check gave no answer yet (no peer served their block): checked again later;
 * - the entries left out for good: [unverified], the engine cannot verify them; [contradicted], the chain says
 *   otherwise against a finalized block, a warning against the importer's key; [traceOnly], their one role is
 *   `TRACE`, never checked;
 * - [unread]: the ranges at or below [top] a read could not get from Swarm (a page's gaps), read again later;
 * - [next]: where the older pages go on, null once the end of what the index covers was reached.
 */
data class KeptAddressIndex(
    val coverage: AddressIndexCoverage,
    val top: Long,
    val rows: List<AddressIndexRow>,
    val recheck: List<AddressIndexEntry>,
    val unverified: List<AddressIndexEntry>,
    val contradicted: List<AddressIndexEntry>,
    val traceOnly: List<AddressIndexEntry>,
    val unread: List<LongRange>,
    val next: AddressIndexCursor?,
) {
    init {
        require(top >= 0) { "a block number is never negative" }
        require(rows.all { it.final }) { "only a row of a finalized block is kept: it can no longer change" }
        require(
            rows.zipWithNext().all { (a, b) ->
                a.entry.block > b.entry.block || (a.entry.block == b.entry.block && a.entry.slot > b.entry.slot)
            },
        ) { "the rows are kept newest first, each entry once" }
        val keys = rows.map { it.entry.block to it.entry.slot } +
            (recheck + unverified + contradicted + traceOnly).map { it.block to it.slot }
        require(keys.size == keys.toSet().size) { "each entry is kept once: a row, waiting, or left out" }
        require(unread.all { !it.isEmpty() && it.first >= 0 && it.last <= top }) {
            "an unread range lies at or below the top"
        }
    }

    /** Every entry the list holds an answer for, or waits for one: by block and slot. */
    val known: Set<Pair<Long, Int>>
        get() = (rows.map { it.entry } + recheck + unverified + contradicted + traceOnly)
            .mapTo(HashSet()) { it.block to it.slot }
}
