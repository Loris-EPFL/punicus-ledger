package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCoverage
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexRow
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.AddressIndexArchive
import eth.serotine.domain.repository.KeptAddressIndex
import kotlinx.coroutines.flow.first

/**
 * Keeps an account's addr1 history between runs of the app, and gives it back (owner's request, 2026-11-25: "Make the
 * downloaded list persistent. The results will never change. So it's ok to store them locally. And prepend the
 * newest txs on the next displaying of the screen"; `.claude/rules/data-sources.md`, *The history may also take the
 * addr1 index from Swarm*).
 *
 * - **Only what can no longer change is kept as a row:** a row verified against a block the beacon chain had
 *   finalized. A row of a block not final yet may still be replaced by a reorganisation: its entry is kept to be
 *   checked again instead, like an entry no check answered.
 * - **Only the pinned key's.** A list resting on any other key than [pinnedImporter] is never kept, and one kept
 *   under another key (a release that pins a new one) is never given back: the list starts again from the index.
 * - **Only an account of the wallet's.** A list is written only while its account is listed: a page an Activity
 *   still finishes after the account's removal writes nothing back ([RemoveAccount] forgets it).
 * - **Nothing is taken on the archive's word but what was verified here before**: a kept row was the engine's
 *   verdict on a finalized block, and stays out of totals like every addr1 row. A row's detail checks its entry
 *   again, as before.
 */
class KeepAddressIndex(
    private val archive: AddressIndexArchive,
    /** The wallet's accounts: a list is written only for one of them. */
    private val accounts: AccountRepository,
    /** The importer key pinned in the release: the one key whose list may be kept and given back. */
    private val pinnedImporter: Address,
) {
    /** What is kept of [account]'s history, under the pinned key; null when nothing is, or it can't be read. */
    suspend fun restore(account: AccountId): AddressIndexResume? {
        val kept = archive.read(account) ?: return null
        if (kept.coverage.importer != pinnedImporter) return null
        return AddressIndexResume(kept, kept.next?.let { AddressIndexContinuation(account, it) })
    }

    /**
     * Keeps [account]'s history as read so far: [coverage] at the newest read, [top] the newest block it was read
     * through, its verified [rows] (only those of a finalized block kept as rows; the others' entries to be checked
     * again, with [recheck]), what it [leftOut] for good and [next], where the older pages go on (null: the end of
     * the index's coverage). False when nothing was kept: a list resting on another key, an account no longer in the
     * wallet (or a list of accounts that cannot be read), or the archive not written.
     * [next] of another account is the caller's error and throws.
     */
    suspend fun keep(
        account: AccountId,
        coverage: AddressIndexCoverage,
        top: Long,
        rows: List<AddressIndexRow>,
        recheck: List<AddressIndexEntry>,
        leftOut: AddressIndexLeftOut,
        next: AddressIndexContinuation?,
    ): Boolean {
        require(next == null || next.account == account) { "a continuation of another account's history" }
        if (coverage.importer != pinnedImporter) return false
        val listed = (accounts.observeAccounts().first() as? AccountList.Loaded)?.accounts ?: return false
        if (listed.none { it.id == account }) return false
        val final = rows.filter { it.final }
            .distinctBy { it.entry.block to it.entry.slot }
            .sortedWith(compareByDescending<AddressIndexRow> { it.entry.block }.thenByDescending { it.entry.slot })
        val rowKeys = final.mapTo(HashSet()) { it.entry.block to it.entry.slot }
        val waiting = (recheck + rows.filterNot { it.final }.map { it.entry })
            .distinctBy { it.block to it.slot }
            .filterNot { (it.block to it.slot) in rowKeys }
        val kept = KeptAddressIndex(
            coverage = coverage,
            top = top,
            rows = final,
            recheck = waiting,
            unverified = leftOut.unverified,
            contradicted = leftOut.contradicted,
            traceOnly = leftOut.traceOnly,
            next = next?.cursor,
        )
        return archive.write(account, kept)
    }

    /**
     * Keeps nothing more of [account]'s history: on the checkpoint table's alarm, which withdraws the trust its rows
     * rest on (`.claude/rules/trust.md`, *The addr1 history*). False when what is kept could not be removed.
     */
    suspend fun forget(account: AccountId): Boolean = archive.forget(account)
}

/**
 * An account's addr1 history as [KeepAddressIndex] kept it: [kept], and [next], where its older pages go on (null:
 * the end of the index's coverage was reached).
 */
data class AddressIndexResume(val kept: KeptAddressIndex, val next: AddressIndexContinuation?) {
    /** The entries the kept list left out for good, as a list counts them. */
    val leftOut: AddressIndexLeftOut
        get() = AddressIndexLeftOut(
            unverified = kept.unverified,
            contradicted = kept.contradicted,
            traceOnly = kept.traceOnly,
        )
}
