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
 * An account's addr1 history as it was read and verified: the [rows] only once their block was finalized, so that
 * none of them can change (a row of a block not final yet may still be replaced by a reorganisation: its entry is
 * kept in [recheck] instead, and checked again).
 *
 * - [coverage]: what the index covered at the newest read, and the importer key the list rests on;
 * - [top]: the newest block the list was read through. Every entry of the index at or below it, down to where
 *   [next] goes on, was read once: a row, left out and counted (below), or in [recheck]. An entry above it is
 *   new;
 * - [rows]: the verified rows, each of a finalized block, newest first, each entry once;
 * - [recheck]: the entries read whose check has not given a lasting answer yet (no peer served their block, or
 *   their block not final yet): checked again at the next read;
 * - the entries left out for good, counted: [unverified], the engine cannot verify them; [contradicted], the chain
 *   says otherwise, a warning against the importer's key; [traceOnly], their one role is `TRACE`, never checked.
 *   An entry no check answered yet is not among them: it is in [recheck];
 * - [next]: where the older pages go on, null once the end of what the index covers was reached.
 */
data class KeptAddressIndex(
    val coverage: AddressIndexCoverage,
    val top: Long,
    val rows: List<AddressIndexRow>,
    val recheck: List<AddressIndexEntry>,
    val unverified: Int,
    val contradicted: Int,
    val traceOnly: Int,
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
        require(unverified >= 0 && contradicted >= 0 && traceOnly >= 0) { "a count of entries" }
        require(recheck.none { entry -> rows.any { it.entry.block == entry.block && it.entry.slot == entry.slot } }) {
            "an entry is a row or waits for its check, never both"
        }
    }
}
