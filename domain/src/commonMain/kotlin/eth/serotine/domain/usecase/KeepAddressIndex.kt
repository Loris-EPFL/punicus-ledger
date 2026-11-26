package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AddressIndexImporters
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
 * - **Only what can no longer change is kept as an answer:** a row, or a contradiction, against a block the beacon
 *   chain had finalized. A verdict against a block not final yet may still be undone by a reorganisation: the list's
 *   top is kept below its block, so that the index is read there again, and a reorganisation's corrected entries
 *   with it.
 * - **Each entry by itself**, a row, left out for good, or waiting for its check, so that a later read neither checks
 *   nor counts it again ([AddressIndexResume.known]), and the ranges a read could not get, to be read again.
 * - **Only the pinned key's.** A list resting on any other key than the one [pinned] for its account's network is
 *   never kept, and one kept under another key (a release that pins a new one) is never given back: the list starts
 *   again from the index.
 * - **Only an account of the wallet's**, written under the app's [changes]: a page an Activity still finishes after
 *   the account's removal writes nothing back ([RemoveAccount] forgets it under the same [changes]).
 * - **Nothing is taken on the archive's word but what was verified here before**: a kept row was the engine's
 *   verdict on a finalized block. A row's detail checks its entry again, as before.
 */
class KeepAddressIndex(
    private val archive: AddressIndexArchive,
    /** The wallet's accounts: a list is written only for one of them. */
    private val accounts: AccountRepository,
    /** The app's one [WalletChanges]: a list is never written between a removal's read and its end. */
    private val changes: WalletChanges,
    /** The importer keys pinned in the release: on each network, the one key whose list may be kept and given back. */
    private val pinned: AddressIndexImporters,
) {
    /** What is kept of [account]'s history, under the pinned key; null when nothing is, or it can't be read. */
    suspend fun restore(account: AccountId): AddressIndexResume? {
        val kept = archive.read(account) ?: return null
        if (kept.coverage.importer != pinned.of(account.network)) return null
        return AddressIndexResume(kept, kept.next?.let { AddressIndexContinuation(account, it) })
    }

    /**
     * Keeps [account]'s history as read so far: [coverage] at the newest read, [top] the newest block it was read
     * through, its verified [rows], the entries waiting for a check ([recheck]), those left out for good ([out]), the
     * entries whose verdict rests on a block not final yet ([notFinal]: the top is kept below them), the ranges a
     * read could not get ([unread]), and [next], where the older pages go on (null: the end of the index's coverage).
     * False when nothing was kept: a list resting on another key, an account no longer in the wallet (or a list of
     * accounts that cannot be read), nothing final to keep, or the archive not written. [next] of another account is
     * the caller's error and throws.
     */
    suspend fun keep(
        account: AccountId,
        coverage: AddressIndexCoverage,
        top: Long,
        rows: List<AddressIndexRow>,
        recheck: List<AddressIndexEntry>,
        out: AddressIndexLeftOutEntries,
        notFinal: List<AddressIndexEntry>,
        unread: List<LongRange>,
        next: AddressIndexContinuation?,
    ): Boolean {
        require(next == null || next.account == account) { "a continuation of another account's history" }
        if (coverage.importer != pinned.of(account.network)) return false
        // Below every verdict that may still be undone, so that the index is read there again.
        val unsettled = (rows.filterNot { it.final }.map { it.entry.block } + notFinal.map { it.block }).minOrNull()
        val keptTop = unsettled?.let { minOf(top, it - 1) } ?: top
        if (keptTop < 0) return false
        val final = rows.filter { it.final }
            .distinctBy { it.entry.block to it.entry.slot }
            .sortedWith(compareByDescending<AddressIndexRow> { it.entry.block }.thenByDescending { it.entry.slot })
        // Each entry once: a row, else left out for good, else waiting.
        val taken = final.mapTo(HashSet()) { it.entry.block to it.entry.slot }
        fun List<AddressIndexEntry>.fresh() = distinctBy { it.block to it.slot }
            .filter { taken.add(it.block to it.slot) }
        val unverified = out.unverified.fresh()
        val contradicted = out.contradicted.fresh()
        val traceOnly = out.traceOnly.fresh()
        val waiting = recheck.fresh()
        val kept = KeptAddressIndex(
            coverage = coverage,
            top = keptTop,
            rows = final,
            recheck = waiting,
            unverified = unverified,
            contradicted = contradicted,
            traceOnly = traceOnly,
            unread = unread.mapNotNull { range ->
                (range.first..minOf(range.last, keptTop)).takeUnless { it.isEmpty() }
            }.distinct(),
            next = next?.cursor,
        )
        return changes.serialized(keys = false) {
            val listed = (accounts.observeAccounts().first() as? AccountList.Loaded)?.accounts
            if (listed?.any { it.id == account } != true) false else archive.write(account, kept)
        }
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
    /** The entries the kept list left out for good. */
    val out: AddressIndexLeftOutEntries
        get() = AddressIndexLeftOutEntries(kept.unverified, kept.contradicted, kept.traceOnly)

    /** What the kept list left out for good, as a list counts it. */
    val leftOut: AddressIndexLeftOut
        get() = out.counted
}
