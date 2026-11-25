package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCoverage
import eth.serotine.domain.model.AddressIndexCursor
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexRow
import eth.serotine.domain.model.AddressIndexVerdict
import eth.serotine.domain.repository.AddressIndexArchive
import eth.serotine.domain.repository.AddressIndexCheck
import eth.serotine.domain.repository.AddressIndexHistory
import eth.serotine.domain.repository.AddressIndexQuery
import eth.serotine.domain.repository.AddressIndexReading
import eth.serotine.domain.repository.AddressIndexRefusal
import eth.serotine.domain.repository.AddressIndexSetting
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Loads the next page of an account's history from the addr1 index, for its *Activity* list alone (issue #334; owner
 * ruling, 2026-11-21, and the owner's decisions of that day on #335, `.claude/rules/data-sources.md`, *The history
 * may also take the addr1 index from Swarm*): it reads one page of candidates from [history], has the engine check
 * them, and gives back the verified ones only, as rows, newest first, with what the index covers, the importer key
 * the list rests on, and how many entries it left out.
 *
 * **As it goes** (owner's request, 2026-11-24: entries shown one by one, not the whole page at once): the load is a
 * flow. Once the page is read it emits [AddressIndexProgress.Read], the entries it will check; then an
 * [AddressIndexProgress.Checked] for each entry as soon as its check gives a lasting verdict, so that a list can put
 * each verified row in place at once; and last, always, the [AddressIndexLoad] the whole load came to, whose counts
 * are the ones to keep. Only a [AddressIndexVerdict.Verified] one is ever a row; a `Checked` of any other verdict says
 * only that the entry is done with.
 *
 * - **The pinned key only.** A page whose index is signed by any key but [pinnedImporter], the key pinned in the
 *   release, is refused ([AddressIndexRefusal.WrongImporter]) before anything of it is checked.
 * - **Verified rows only.** An entry becomes a row only on an [AddressIndexVerdict.Verified] of it. Every other
 *   entry is left out and counted ([AddressIndexLeftOut]); a contradicted one apart, a warning against the key.
 * - **TRACE-only entries are not sent to the check**: the engine never verifies one, so asking would only spend a
 *   slot of the check. They are counted apart ([AddressIndexLeftOut.traceOnly]), among the entries that could not
 *   be verified.
 * - **The check runs block by block**, newest first (the page's order): one call for the entries of one block (in
 *   chunks of at most [AddressIndexHistory.MAX_CHECKED] where a block holds more). The engine reads each block once
 *   either way; one block at a time lets each verdict out as soon as it is known, instead of after the slowest
 *   block of the page. A verdict is matched to the entry asked by block and slot; one whose roles are not the
 *   roles asked, or two different verdicts for one entry, count as no verdict. An entry that comes back
 *   [AddressIndexVerdict.Unavailable], or with no verdict, is asked again, up to [attempts] checks in all,
 *   [retryPause] apart. One still unavailable after that is left out and counted apart
 *   ([AddressIndexLeftOut.unavailable]), since a later load may verify it.
 * - **A passing refusal of a check** ([AddressIndexRefusal.NotNow]) is asked again like an unavailable entry; still
 *   refused at the last attempt, that chunk's entries count as unavailable, and the rows already verified and the
 *   page's continuation are kept.
 * - **A lasting refusal is said, never an empty list.** A page refused for any reason refuses the load with it
 *   ([AddressIndexLoad.Refused]). So does a check refused for a lasting reason (not in this build, the setting off,
 *   Tor, a network not served, the checkpoint table's alarm, a stale checkpoint, no engine), since it holds for
 *   every chunk, and the alarm withdraws the trust the rows rest on. [AddressIndexRefusal.Rejected] refuses it too:
 *   the engine refusing an argument this port checked is a bug, and no part of the page is shown over it.
 * - **The engine bounds every call itself**: a page's read at about 140 seconds (its Swarm node's start and the
 *   read's deadline), a check at 90 seconds. An adapter that blocks on the engine cannot be left earlier, so
 *   [budget] and [checkBudget] are a bound for an adapter that can be: at least the engine's, they cut nothing
 *   short that the engine would still answer.
 * - **A page not read within [budget]** refuses the load as passing ([AddressIndexRefusal.NotNow],
 *   [ReadUnavailable.UNPROVEN]: Swarm did not deliver it).
 * - **A block's check past [checkBudget]** gave its entries no verdict, and they are not asked again in this load:
 *   they count as unavailable, and the other blocks go on. So a slow block costs its own entries their verdicts,
 *   never the rest of the page theirs, and a page takes at most its blocks times [checkBudget], with the retries of
 *   the blocks that answered.
 * - **A page none of whose entries could be checked now** (every check refused in passing, or unavailable, or past
 *   its bound) refuses the load as passing, with the checks' reason: asking again reads the page again, instead of
 *   moving on below entries that were never checked.
 *
 * Deterministic: everything it knows comes from [history], called from the load's own coroutine (the flow's
 * producer), one call at a time.
 */
class LoadAddressIndexPage(
    private val history: AddressIndexHistory,
    /** The importer key pinned in the release: the one key whose index the list may rest on. */
    private val pinnedImporter: Address,
    /** How many checks an entry gets at most, the first one included: 1 or more. */
    private val attempts: Int = DEFAULT_ATTEMPTS,
    /** How long to wait before asking again for the entries no check answered. */
    private val retryPause: Duration = DEFAULT_RETRY_PAUSE,
    /** The longest the page's read may take. */
    private val budget: Duration = DEFAULT_BUDGET,
    /** The longest one block's check may take; past it, its entries are asked again. */
    private val checkBudget: Duration = DEFAULT_CHECK_BUDGET,
) {
    init {
        require(attempts >= 1) { "an entry is checked once at least" }
        require(!retryPause.isNegative()) { "a pause is never negative" }
        require(budget.isPositive()) { "a page has time to be read" }
        require(checkBudget.isPositive()) { "a block has time to be checked" }
    }

    /**
     * The page of [account]'s addr1 history below [after] (the [AddressIndexLoad.Loaded.next] of the page before;
     * null: the newest page), of at most [limit] entries (1 to [AddressIndexHistory.MAX_PAGE]), read on
     * [account]'s network and checked there, as it goes (see the class): its last value is always the
     * [AddressIndexLoad]. Nothing is read until it is collected. [after] of another account is the caller's error
     * and throws when collected.
     *
     * For a list kept from an earlier read ([KeepAddressIndex]):
     * - [range], when given, reads only blocks [range] (a range an earlier read could not get), its gaps clipped to it;
     * - [known] are the entries the list has an answer for already (a row, left out for good, or waiting for its
     *   check), by block and slot: the page passes them over, neither checked nor counted;
     * - [newerThan] is the newest block the kept list was read through: only the page's entries above it are new, the
     *   others passed over, and once the page reaches it nothing newer lies below, so the page's
     *   [AddressIndexProgress.Read.next] and [AddressIndexLoad.Loaded.next] are null.
     */
    operator fun invoke(
        account: AccountId,
        after: AddressIndexContinuation? = null,
        limit: Int = AddressIndexHistory.DEFAULT_PAGE,
        newerThan: Long? = null,
        known: Set<Pair<Long, Int>> = emptySet(),
        range: LongRange? = null,
    ): Flow<AddressIndexProgress> = channelFlow {
        require(after == null || after.account == account) { "a continuation of another account's history" }
        require(newerThan == null || newerThan >= 0) { "a block number is never negative" }
        require(range == null || (!range.isEmpty() && range.first >= 0)) { "a range of one block at least" }
        val query = AddressIndexQuery(
            cursor = after?.cursor,
            limit = limit,
            fromBlock = range?.first,
            toBlock = range?.last,
        )
        var read: Pick? = null
        var refused: AddressIndexRefusal? = null
        val verdicts = mutableMapOf<AddressIndexEntry, AddressIndexVerdict>()
        // A page not read within its budget is a passing refusal.
        val reading = withTimeoutOrNull(budget) { history.page(account.network, account.address, query) }
        val page = when (reading) {
            null -> null
            is AddressIndexReading.Refused -> {
                refused = reading.reason
                null
            }
            is AddressIndexReading.Page -> reading.page
        }
        if (page != null && page.coverage.importer != pinnedImporter) {
            refused = AddressIndexRefusal.WrongImporter(pinnedImporter, page.coverage.importer)
        } else if (page != null) {
            // Of a page read for what is newer than a kept list, only the entries above its top are new.
            val reachesKept = newerThan != null && page.entries.any { it.block <= newerThan }
            val entries = page.entries
                .filter { newerThan == null || it.block > newerThan }
                .filterNot { (it.block to it.slot) in known }
            val next = page.next?.takeUnless { reachesKept }?.let { AddressIndexContinuation(account, it) }
            read = Pick(entries, page.coverage, next, reached = page.entries.minOfOrNull { it.block })
            refused = checked(account, read, verdicts)
        }
        send(summary(account, read, refused, verdicts))
    }
        // Never waits on its collector: the budgets are the read's and the checks', not the time a list takes to draw.
        .buffer(Channel.UNLIMITED)

    /**
     * Checks [entries] of [account]'s history again, entries a load read before whose check gave no lasting answer
     * then ([AddressIndexLoad.Loaded.unavailable]), as it goes, like a page
     * (see the class): a [AddressIndexProgress.Read] of them, with [coverage] and [next], the list's own, which this
     * check reads nothing of and changes nothing of, then each verdict, and last the [AddressIndexLoad], whose
     * [AddressIndexLoad.Loaded.next] is [next] again. No page is read. [next] of another account is the caller's
     * error and throws when collected.
     */
    fun again(
        account: AccountId,
        entries: List<AddressIndexEntry>,
        coverage: AddressIndexCoverage,
        next: AddressIndexContinuation?,
    ): Flow<AddressIndexProgress> = channelFlow {
        require(next == null || next.account == account) { "a continuation of another account's history" }
        require(coverage.importer == pinnedImporter) { "a list resting on another key than the pinned one" }
        val verdicts = mutableMapOf<AddressIndexEntry, AddressIndexVerdict>()
        val pick = Pick(entries.distinct(), coverage, next)
        val refused = checked(account, pick, verdicts)
        send(summary(account, pick, refused, verdicts))
    }
        .buffer(Channel.UNLIMITED)

    /**
     * Says that [pick] is read, then checks its entries (but the `TRACE`-only ones) into [verdicts], telling each as
     * it comes: the refusal the checks came to, or null.
     */
    private suspend fun ProducerScope<AddressIndexProgress>.checked(
        account: AccountId,
        pick: Pick,
        verdicts: MutableMap<AddressIndexEntry, AddressIndexVerdict>,
    ): AddressIndexRefusal? {
        val toCheck = pick.entries.filterNot { it.traceOnly }
        send(AddressIndexProgress.Read(account, pick.coverage, pick.next, toCheck))
        val outcome = checkAll(account, toCheck, verdicts) { entry, verdict ->
            send(AddressIndexProgress.Checked(entry, verdict))
        }
        return when (outcome) {
            is Lasting -> outcome.reason
            is Passing -> outcome.reason
            Done -> null
        }
    }

    /**
     * The entries a load checks, with what the index covers, where the list goes on below them, and the lowest block
     * the page read reached (null: a page of no entry, or no page).
     */
    private class Pick(
        val entries: List<AddressIndexEntry>,
        val coverage: AddressIndexCoverage,
        val next: AddressIndexContinuation?,
        val reached: Long? = null,
    )

    /**
     * What a load came to: [refused] if it was, else [read] (null: not read in time) with its [verdicts]. A verdict
     * against a block not final yet is no lasting answer: a verified one is a row for now, a contradiction is not
     * counted, and both are named ([AddressIndexLoad.Loaded.notFinal]) to be read again once their block is final.
     */
    private fun summary(
        account: AccountId,
        read: Pick?,
        refused: AddressIndexRefusal?,
        verdicts: Map<AddressIndexEntry, AddressIndexVerdict>,
    ): AddressIndexLoad {
        refused?.let { return AddressIndexLoad.Refused(it) }
        if (read == null) return AddressIndexLoad.Refused(AddressIndexRefusal.NotNow(ReadUnavailable.UNPROVEN))
        val checked = read.entries.filterNot { it.traceOnly }
        val rows = read.entries.mapNotNull { (verdicts[it] as? AddressIndexVerdict.Verified)?.row }
        val unavailable = checked.filterNot { it in verdicts }
        val notFinal = read.entries.filter { entry ->
            when (val verdict = verdicts[entry]) {
                is AddressIndexVerdict.Verified -> !verdict.row.final
                is AddressIndexVerdict.Contradicted -> !verdict.final
                else -> false
            }
        }
        val out = AddressIndexLeftOutEntries(
            unverified = read.entries.filter { verdicts[it] is AddressIndexVerdict.Unverified },
            contradicted = read.entries.filter { (verdicts[it] as? AddressIndexVerdict.Contradicted)?.final == true },
            traceOnly = read.entries.filter { it.traceOnly },
        )
        return AddressIndexLoad.Loaded(
            account = account,
            rows = rows,
            coverage = read.coverage,
            leftOut = out.counted.copy(unavailable = unavailable.size),
            next = read.next,
            unavailable = unavailable,
            out = out,
            notFinal = notFinal,
            reached = read.reached,
        )
    }

    /**
     * Checks [entries] block by block, in their order, putting into [verdicts] the lasting verdict of each that gets
     * one, all but those still unavailable after [attempts] checks, and telling [onVerdict] of each as it comes.
     * [Lasting] when a check is refused for a lasting reason, else [Done].
     */
    private suspend fun checkAll(
        account: AccountId,
        entries: List<AddressIndexEntry>,
        verdicts: MutableMap<AddressIndexEntry, AddressIndexVerdict>,
        onVerdict: suspend (AddressIndexEntry, AddressIndexVerdict) -> Unit,
    ): Outcome {
        var pending = entries
        // A block whose check ran to its bound is not asked again in this load: it is unlikely to answer moments later,
        // and asking again would hold the page for another bound each time.
        val givenUp = mutableSetOf<AddressIndexEntry>()
        var passing: AddressIndexRefusal? = null
        for (attempt in 1..attempts) {
            if (pending.isEmpty()) break
            if (attempt > 1) delay(retryPause)
            for (chunk in byBlock(pending)) {
                val answer = withTimeoutOrNull(checkBudget) { history.verify(account.network, account.address, chunk) }
                when (answer) {
                    null -> givenUp += chunk
                    // A passing refusal leaves the chunk pending: asked again, or unavailable after the last attempt.
                    is AddressIndexCheck.Refused -> if (answer.reason.retryable) {
                        passing = answer.reason
                    } else {
                        return Lasting(answer.reason)
                    }
                    is AddressIndexCheck.Verdicts -> chunk.forEach { entry ->
                        verdictOf(entry, answer.verdicts)?.let {
                            verdicts[entry] = it
                            onVerdict(entry, it)
                        }
                    }
                }
            }
            pending = pending.filterNot { it in verdicts || it in givenUp }
        }
        // Not one entry could be checked now (the light client catching up, no peer serving the blocks): the page is
        // refused as passing, so that asking again reads it again, instead of moving on below entries never checked.
        if (entries.isNotEmpty() && verdicts.isEmpty()) {
            return Passing(passing ?: AddressIndexRefusal.NotNow(ReadUnavailable.UNPROVEN))
        }
        return Done
    }

    /**
     * [entries] as the checks ask for them: the entries of one block together, in their order, a block of more than
     * [AddressIndexHistory.MAX_CHECKED] in chunks of that many.
     */
    private fun byBlock(entries: List<AddressIndexEntry>): List<List<AddressIndexEntry>> {
        val blocks = mutableListOf<MutableList<AddressIndexEntry>>()
        for (entry in entries) {
            val last = blocks.lastOrNull()
            if (last != null && last.first().block == entry.block) last += entry else blocks += mutableListOf(entry)
        }
        return blocks.flatMap { it.chunked(AddressIndexHistory.MAX_CHECKED) }
    }

    /**
     * The one lasting verdict [answer] gives [entry]: matched by block and slot, with the roles asked; null for none,
     * for one whose roles differ, for two that differ, and for [AddressIndexVerdict.Unavailable].
     */
    private fun verdictOf(entry: AddressIndexEntry, answer: List<AddressIndexVerdict>): AddressIndexVerdict? =
        answer.filter { it.entry.block == entry.block && it.entry.slot == entry.slot }
            .distinct()
            .singleOrNull()
            ?.takeIf { it.entry.roles == entry.roles && it !is AddressIndexVerdict.Unavailable }

    /**
     * How [checkAll] ended: [Done]; refused for a [Lasting] reason; or [Passing], when not one entry could be checked
     * now, for the passing reason the checks gave (or Swarm and the peers not delivering).
     */
    private sealed interface Outcome
    private data object Done : Outcome
    private data class Lasting(val reason: AddressIndexRefusal) : Outcome
    private data class Passing(val reason: AddressIndexRefusal) : Outcome

    private companion object {
        /** A first check and two more for what it could not read. */
        const val DEFAULT_ATTEMPTS = 3

        /** Long enough for the engine to try other peers for a block or its receipts. */
        val DEFAULT_RETRY_PAUSE = 2.seconds

        /** The engine's bound on a page's read (about 140 seconds: its node's start and the read), with room. */
        val DEFAULT_BUDGET = 3.minutes

        /** The engine's bound on one check (90 seconds), with room to spare. */
        val DEFAULT_CHECK_BUDGET = 2.minutes
    }
}

/**
 * Where [account]'s addr1 history goes on: below [cursor]. Made by [LoadAddressIndexPage] only, so it is always a
 * page's own.
 */
@ConsistentCopyVisibility
data class AddressIndexContinuation internal constructor(val account: AccountId, val cursor: AddressIndexCursor)

/**
 * The entries of a page left out of the history, by why (`.claude/rules/data-sources.md`: the list says how many it
 * could not verify, and, separately, how many the chain contradicts). The counts are disjoint: [contradicted], the
 * chain says otherwise, a warning against the importer's key; and those that could not be verified ([notVerified]):
 * [unverified], the engine cannot verify them and asking again does not help; [traceOnly], their one role is
 * `TRACE`, which the engine never verifies, so they were not sent to the check; [unavailable], no check read their
 * block this time (no peer served it, the light client not ready, the load's time spent), and a later load may
 * verify them.
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

    /** The entries that could not be verified: every one left out but the contradicted. */
    val notVerified: Int
        get() = unverified + traceOnly + unavailable

    /** Every entry left out, whatever the reason. */
    val total: Int
        get() = notVerified + contradicted

    /** These and [other] together: what a list of several pages left out. */
    operator fun plus(other: AddressIndexLeftOut): AddressIndexLeftOut = AddressIndexLeftOut(
        unverified + other.unverified,
        contradicted + other.contradicted,
        traceOnly + other.traceOnly,
        unavailable + other.unavailable,
    )
}

/**
 * The entries left out for good themselves, by why, each once: [unverified], the engine cannot verify them;
 * [contradicted], the chain says otherwise against a finalized block; [traceOnly], never checked. What a kept list
 * holds of them, so that a later read neither checks nor counts them again.
 */
data class AddressIndexLeftOutEntries(
    val unverified: List<AddressIndexEntry> = emptyList(),
    val contradicted: List<AddressIndexEntry> = emptyList(),
    val traceOnly: List<AddressIndexEntry> = emptyList(),
) {
    /** These as counts, [AddressIndexLeftOut.unavailable] none. */
    val counted: AddressIndexLeftOut
        get() = AddressIndexLeftOut(unverified.size, contradicted.size, traceOnly.size)

    /** Every entry left out, by block and slot. */
    val keys: Set<Pair<Long, Int>>
        get() = (unverified + contradicted + traceOnly).mapTo(HashSet()) { it.block to it.slot }

    /** These and [other] together, each entry once. */
    operator fun plus(other: AddressIndexLeftOutEntries): AddressIndexLeftOutEntries = AddressIndexLeftOutEntries(
        (unverified + other.unverified).distinctBy { it.block to it.slot },
        (contradicted + other.contradicted).distinctBy { it.block to it.slot },
        (traceOnly + other.traceOnly).distinctBy { it.block to it.slot },
    )
}

/**
 * What [LoadAddressIndexPage] emits as it goes: the page [Read], each entry [Checked], and last, always, the
 * [AddressIndexLoad] it came to.
 */
sealed interface AddressIndexProgress {

    /**
     * The page of [account]'s history is read, signed by the pinned key: what the index covers ([coverage]), which
     * the list states before any row of the page, where it goes on ([next], null at the end of its coverage), and
     * [entries], the ones it checks next, newest first (its `TRACE`-only entries are never checked, so not among
     * them). Each is a candidate: the importer's word that an entry is there, nothing about the chain.
     */
    data class Read(
        val account: AccountId,
        val coverage: AddressIndexCoverage,
        val next: AddressIndexContinuation?,
        val entries: List<AddressIndexEntry>,
    ) : AddressIndexProgress

    /**
     * The lasting [verdict] on [entry]. Only an [AddressIndexVerdict.Verified] one is a row; any other leaves the
     * entry out, which the [AddressIndexLoad] at the end counts.
     */
    data class Checked(val entry: AddressIndexEntry, val verdict: AddressIndexVerdict) : AddressIndexProgress
}

/** What a [LoadAddressIndexPage] came to: the last value it emits. */
sealed interface AddressIndexLoad : AddressIndexProgress {

    /**
     * One page of [account]'s history: its verified [rows], newest first, and nothing else of it; what the index
     * covers and the importer key it rests on ([coverage]), which the list names, saying that entries may be
     * missing; the entries it [leftOut]; [next], where the history goes on, null at the end of what the index
     * covers; [unavailable], the entries counted in [AddressIndexLeftOut.unavailable] themselves, which a later
     * check may verify; [out], the entries left out for good themselves; and [notFinal], the entries whose verdict
     * rests on a block not final yet (a row for now, or a contradiction not counted), to be read again; and
     * [reached], the lowest block of the page read, the entries passed over among it (null: none, or no page read).
     */
    data class Loaded(
        val account: AccountId,
        val rows: List<AddressIndexRow>,
        val coverage: AddressIndexCoverage,
        val leftOut: AddressIndexLeftOut,
        val next: AddressIndexContinuation?,
        val unavailable: List<AddressIndexEntry> = emptyList(),
        val out: AddressIndexLeftOutEntries = AddressIndexLeftOutEntries(),
        val notFinal: List<AddressIndexEntry> = emptyList(),
        val reached: Long? = null,
    ) : AddressIndexLoad {
        /** Whether this page reaches the end of what the index covers: no page lies below it. */
        val endOfCoverage: Boolean
            get() = next == null
    }

    /** Nothing loaded, for [reason], which the screen states. */
    data class Refused(val reason: AddressIndexRefusal) : AddressIndexLoad
}

/**
 * Checks one entry of an account's addr1 history again, for the detail of its row (docs/wallet-ux.md, screen 26):
 * the detail never shows what the list once found, only what the engine's check finds now, against verified chain
 * data. [importer] is the key pinned in the release that the list rests on, which the detail names. Only while the
 * experimental setting is wanted ([AddressIndexStatus.wanted] for [importer]: [setting]; on by default, an explicit
 * off kept; an engine without the reader refuses the check itself): otherwise it is refused
 * ([AddressIndexRefusal.SettingOff]) and nothing is fetched, as the privacy policy promises. A
 * `TRACE`-only entry is never sent to the check, since the engine never verifies one: it is unverified. A verdict is
 * matched to the entry asked by block, slot and roles, as [LoadAddressIndexPage] matches it; none matched is
 * [AddressIndexVerdict.Unavailable]. A refused check is [AddressIndexEntryCheck.Refused], never a verdict; refused for
 * the checkpoint table's alarm, it also deletes the account's list kept between runs ([archive]), since the alarm
 * withdraws the trust its rows rest on.
 */
class CheckAddressIndexEntry(
    private val history: AddressIndexHistory,
    /** The user's choice: the check runs only while the setting is wanted ([AddressIndexStatus.wanted]). */
    private val setting: AddressIndexSetting,
    /** The importer key pinned in the release: what the list's completeness rests on. */
    val importer: Address,
    /** Each account's list kept between runs, on a host that keeps one; null: none is kept. */
    private val archive: AddressIndexArchive? = null,
) {
    /** [entry] of [account]'s history, checked on [account]'s network, while the setting is on. */
    suspend operator fun invoke(account: AccountId, entry: AddressIndexEntry): AddressIndexEntryCheck {
        // The user's choice alone, without waiting for the engine's word on its reader: an engine without one refuses
        // the check itself (AddressIndexRefusal.NotInThisBuild).
        if (!AddressIndexStatus(importer, null, setting.observe().first()).wanted) {
            return AddressIndexEntryCheck.Refused(AddressIndexRefusal.SettingOff)
        }
        if (entry.traceOnly) {
            return AddressIndexEntryCheck.Checked(AddressIndexVerdict.Unverified(entry, TRACE_ONLY))
        }
        return when (val answer = history.verify(account.network, account.address, listOf(entry))) {
            is AddressIndexCheck.Refused -> {
                if (answer.reason is AddressIndexRefusal.CheckpointAlarm) archive?.forget(account)
                AddressIndexEntryCheck.Refused(answer.reason)
            }
            is AddressIndexCheck.Verdicts -> AddressIndexEntryCheck.Checked(
                answer.verdicts
                    .filter { it.entry.block == entry.block && it.entry.slot == entry.slot }
                    .distinct()
                    .singleOrNull()
                    ?.takeIf { it.entry.roles == entry.roles }
                    ?: AddressIndexVerdict.Unavailable(entry, NO_VERDICT),
            )
        }
    }

    private companion object {
        const val TRACE_ONLY = "its one role is a trace, which the engine never verifies"
        const val NO_VERDICT = "the check gave no verdict for it"
    }
}

/** What [CheckAddressIndexEntry] answers. */
sealed interface AddressIndexEntryCheck {
    /** The engine's [verdict] of the entry now. */
    data class Checked(val verdict: AddressIndexVerdict) : AddressIndexEntryCheck

    /** Nothing checked, for [reason], which the screen states. */
    data class Refused(val reason: AddressIndexRefusal) : AddressIndexEntryCheck
}
