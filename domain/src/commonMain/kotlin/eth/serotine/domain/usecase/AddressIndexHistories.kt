package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCoverage
import eth.serotine.domain.model.AddressIndexCursor
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexRow
import eth.serotine.domain.model.AddressIndexVerdict
import eth.serotine.domain.repository.AddressIndexCheck
import eth.serotine.domain.repository.AddressIndexHistory
import eth.serotine.domain.repository.AddressIndexQuery
import eth.serotine.domain.repository.AddressIndexReading
import eth.serotine.domain.repository.AddressIndexRefusal
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Loads the next page of an account's history from the addr1 index (issue #334; owner ruling, 2026-11-21,
 * `.claude/rules/data-sources.md`, *The history may also take the addr1 index from Swarm*): it reads one page of
 * candidates from [history], has the engine check them, and gives back the verified ones only, as rows, newest
 * first, with what the index covers, the importer key the list rests on, and how many entries it left out.
 *
 * - **Verified rows only.** An entry becomes a row only on an [AddressIndexVerdict.Verified] of it. Every other
 *   entry is left out and counted ([AddressIndexLeftOut]).
 * - **TRACE-only entries are not sent to the check**: the engine never verifies one, so asking would only spend a
 *   slot of the check. They are counted apart ([AddressIndexLeftOut.traceOnly]), among the entries that could not
 *   be verified.
 * - **The check runs in chunks** of at most [AddressIndexHistory.MAX_CHECKED] entries, one after the other, in page
 *   order. An entry that comes back [AddressIndexVerdict.Unavailable], or with no verdict, is asked again, up to
 *   [attempts] checks in all, [retryPause] apart; one still unavailable after that is left out and counted apart
 *   ([AddressIndexLeftOut.unavailable]), since a later load may verify it.
 * - **A refusal is said, never an empty list.** A page or a check refused for a lasting reason refuses the load
 *   with it ([AddressIndexLoad.Refused]). A check refused for a passing reason ([AddressIndexRefusal.NotNow]) is
 *   asked again like an unavailable entry; still refused at the last attempt, it refuses the load, so the caller
 *   loads the same page again later rather than pass its entries by.
 * - **One importer per list.** A page after the first must name the importer the list began with
 *   ([AddressIndexContinuation.importer]); one that names another ends the list ([AddressIndexLoad.ImporterChanged]),
 *   whose completeness rests on that one key.
 *
 * Pure: everything it knows comes from [history], and it calls it from the caller's coroutine only.
 */
class LoadAddressIndexPage(
    private val history: AddressIndexHistory,
    /** How many checks an entry gets at most, the first one included: 1 or more. */
    private val attempts: Int = DEFAULT_ATTEMPTS,
    /** How long to wait before asking again for the entries no check answered. */
    private val retryPause: Duration = DEFAULT_RETRY_PAUSE,
) {
    init {
        require(attempts >= 1) { "an entry is checked once at least" }
        require(!retryPause.isNegative()) { "a pause is never negative" }
    }

    /**
     * The page of [account]'s addr1 history below [after] (the [AddressIndexLoad.Loaded.next] of the page before;
     * null: the newest page), of at most [limit] entries (1 to [AddressIndexHistory.MAX_PAGE]), read on
     * [account]'s network and checked there. [after] of another account is the caller's error and throws.
     */
    suspend operator fun invoke(
        account: AccountId,
        after: AddressIndexContinuation? = null,
        limit: Int = AddressIndexHistory.DEFAULT_PAGE,
    ): AddressIndexLoad {
        require(after == null || after.account == account) { "a continuation of another account's history" }
        val query = AddressIndexQuery(cursor = after?.cursor, limit = limit)
        val page = when (val reading = history.page(account.network, account.address, query)) {
            is AddressIndexReading.Refused -> return AddressIndexLoad.Refused(reading.reason)
            is AddressIndexReading.Page -> reading.page
        }
        val importer = page.coverage.importer
        if (after != null && importer != after.importer) {
            return AddressIndexLoad.ImporterChanged(trusted = after.importer, named = importer)
        }
        val checked = page.entries.filterNot { it.traceOnly }
        val verdicts = when (val outcome = check(account, checked)) {
            is Checked.Refused -> return AddressIndexLoad.Refused(outcome.reason)
            is Checked.Done -> outcome.verdicts
        }
        val rows = page.entries.mapNotNull { (verdicts[it] as? AddressIndexVerdict.Verified)?.row }
        val leftOut = AddressIndexLeftOut(
            unverified = verdicts.values.count { it is AddressIndexVerdict.Unverified },
            contradicted = verdicts.values.count { it is AddressIndexVerdict.Contradicted },
            traceOnly = page.entries.size - checked.size,
            unavailable = checked.count { it !in verdicts },
        )
        return AddressIndexLoad.Loaded(
            account = account,
            rows = rows,
            coverage = page.coverage,
            leftOut = leftOut,
            next = page.next?.let { AddressIndexContinuation(account, importer, it) },
        )
    }

    /**
     * The lasting verdicts of [entries]: every one but those still unavailable after [attempts] checks, which have
     * none. A verdict is taken only for an entry asked, and only when the answer gives it one verdict.
     */
    private suspend fun check(account: AccountId, entries: List<AddressIndexEntry>): Checked {
        val verdicts = mutableMapOf<AddressIndexEntry, AddressIndexVerdict>()
        var pending = entries
        for (attempt in 1..attempts) {
            if (pending.isEmpty()) break
            if (attempt > 1) delay(retryPause)
            var notNow: AddressIndexRefusal? = null
            for (chunk in pending.chunked(AddressIndexHistory.MAX_CHECKED)) {
                when (val answer = history.verify(account.network, account.address, chunk)) {
                    is AddressIndexCheck.Refused ->
                        if (answer.reason.retryable) notNow = answer.reason else return Checked.Refused(answer.reason)
                    is AddressIndexCheck.Verdicts -> chunk.forEach { entry ->
                        answer.verdicts.filter { it.entry == entry }.distinct().singleOrNull()
                            ?.takeUnless { it is AddressIndexVerdict.Unavailable }
                            ?.let { verdicts[entry] = it }
                    }
                }
            }
            pending = pending.filterNot { it in verdicts }
            if (attempt == attempts && notNow != null) return Checked.Refused(notNow)
        }
        return Checked.Done(verdicts)
    }

    /** What [check] found: the lasting [Done.verdicts], or why nothing could be checked. */
    private sealed interface Checked {
        data class Done(val verdicts: Map<AddressIndexEntry, AddressIndexVerdict>) : Checked
        data class Refused(val reason: AddressIndexRefusal) : Checked
    }

    private companion object {
        /** A first check and two more for what it could not read. */
        const val DEFAULT_ATTEMPTS = 3

        /** Long enough for the engine to try other peers for a block or its receipts. */
        val DEFAULT_RETRY_PAUSE = 2.seconds
    }
}

/**
 * Where an account's addr1 history goes on: below [cursor], in the index signed by [importer], the key the list's
 * rows so far rest on. Made by [LoadAddressIndexPage] only, so it is always a page's own.
 */
@ConsistentCopyVisibility
data class AddressIndexContinuation internal constructor(
    val account: AccountId,
    val importer: Address,
    val cursor: AddressIndexCursor,
)

/**
 * The entries of a page left out of the history because none of them could be shown as verified, by why
 * (`.claude/rules/data-sources.md`: the list says how many, and how many of those the chain contradicts). The
 * counts are disjoint: [unverified], the engine cannot verify them and asking again does not help; [contradicted],
 * the chain says otherwise; [traceOnly], their one role is `TRACE`, which the engine never verifies, so they were not
 * sent to the check; [unavailable], no check could read their block this time, and a later load may verify them.
 */
data class AddressIndexLeftOut(
    val unverified: Int = 0,
    val contradicted: Int = 0,
    val traceOnly: Int = 0,
    val unavailable: Int = 0,
) {
    init {
        require(unverified >= 0 && contradicted >= 0 && traceOnly >= 0 && unavailable >= 0) { "a count of entries" }
    }

    /** Every entry left out, whatever the reason: those that could not be verified. */
    val total: Int
        get() = unverified + contradicted + traceOnly + unavailable

    /** These and [other] together: what a list of several pages left out. */
    operator fun plus(other: AddressIndexLeftOut): AddressIndexLeftOut = AddressIndexLeftOut(
        unverified + other.unverified,
        contradicted + other.contradicted,
        traceOnly + other.traceOnly,
        unavailable + other.unavailable,
    )
}

/** What [LoadAddressIndexPage] gives back. */
sealed interface AddressIndexLoad {

    /**
     * One page of [account]'s history: its verified [rows], newest first, and nothing else of it; what the index
     * covers and the importer key it rests on ([coverage]), which the list names, saying that entries may be
     * missing; the entries it [leftOut]; and [next], where the history goes on, null at the end of what the index
     * covers.
     */
    data class Loaded(
        val account: AccountId,
        val rows: List<AddressIndexRow>,
        val coverage: AddressIndexCoverage,
        val leftOut: AddressIndexLeftOut,
        val next: AddressIndexContinuation?,
    ) : AddressIndexLoad {
        /** Whether this page reaches the end of what the index covers: no page lies below it. */
        val endOfCoverage: Boolean
            get() = next == null
    }

    /** Nothing loaded, for [reason], which the screen states. */
    data class Refused(val reason: AddressIndexRefusal) : AddressIndexLoad

    /**
     * The index now read is signed by [named], not by [trusted], whose key the list so far rests on (the setting
     * changed): the list starts over from the newest page rather than mix two importers' words.
     */
    data class ImporterChanged(val trusted: Address, val named: Address) : AddressIndexLoad
}
