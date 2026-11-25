package eth.serotine.ui.activity

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCoverage
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexRole
import eth.serotine.domain.model.AddressIndexRow
import eth.serotine.domain.model.AddressIndexVerdict
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressIndexRefusal
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.AddressIndexContinuation
import eth.serotine.domain.usecase.AddressIndexLeftOut
import eth.serotine.domain.usecase.AddressIndexLeftOutEntries
import eth.serotine.domain.usecase.AddressIndexLoad
import eth.serotine.domain.usecase.AddressIndexProgress
import eth.serotine.domain.usecase.AddressIndexResume
import eth.serotine.domain.usecase.KeepAddressIndex
import eth.serotine.domain.usecase.LoadAddressIndexPage
import eth.serotine.domain.usecase.ObserveAddressIndexStatus
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.shortAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * What an account's Activity reads of the addr1 index, on the desktop and Android with its experimental setting on
 * (owner ruling, 2026-11-21, #334; the owner's decisions of that day on #335): whether the setting is on ([status]),
 * and its pages of verified rows ([load]). A host that offers no such setting (iOS), or a release that pins no
 * importer key, hands none to [AccountActivity], and the Activity is the log index's alone.
 *
 * **Kept between runs** (owner's request, 2026-11-25), where the host gives [keep]: what was read and verified is
 * shown at once when the Activity opens again, the rows of finalized blocks among it, and only what is newer is read,
 * then prepended; the entries no check answered before are checked again, and the older pages go on where the kept
 * list ended.
 */
class AddressIndexActivity(
    private val status: ObserveAddressIndexStatus,
    private val load: LoadAddressIndexPage,
    /** Keeps each account's list between runs; null: every opening reads the index from its newest page. */
    private val keep: KeepAddressIndex? = null,
) {
    /**
     * The pages of [account]'s addr1 history while the setting is on, null while it is off (and until it is read).
     * The newest page is read at once; each later value of [more] above the one it started with asks for the next
     * page, one at a time, or, after a passing refusal, the same page again. An ask that comes while a page is read
     * or checked is that page's: it asks for nothing more. Nothing is read while it is off.
     *
     * One by one (owner's request, 2026-11-24): a page holds [PAGE_SIZE] entries, about a screen of rows and one
     * batch more; once it is read, each entry is pending ([IndexPaging.pending], a ghost row in its place) until its
     * check answers, and a verified one is a row at once, whatever the others' checks still take.
     *
     * With a list kept from before, it is the first value, and instead of the newest page the entries newer than it
     * are read, page by page down to it, and prepended; then the entries it keeps for a check are checked again.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun of(account: AccountId, more: StateFlow<Int>): Flow<IndexPaging?> = status()
        // The key pinned for the account's network: none (Sepolia), and its Activity is the log index's alone.
        .map { if (it.on) it.pinned.of(account.network) else null }
        .distinctUntilChanged()
        .flatMapLatest { pinned -> if (pinned == null) flowOf(null) else pages(account, pinned, more) }
        .onStart { emit(null) }

    private fun pages(account: AccountId, pinned: Address, more: StateFlow<Int>): Flow<IndexPaging> = flow {
        var asked = more.value
        val resume = keep?.restore(account)
        var paging = resume?.let { IndexPaging.resumed(pinned, it) } ?: IndexPaging(pinned)
        emit(paging)
        // Whether the opening caught up with a kept list: until then, an ask goes on with that, not an older page.
        var caughtUp = resume == null
        if (resume == null) {
            paging = newest(account, paging)
        } else {
            val (list, done) = catchUp(account, paging)
            paging = list
            caughtUp = done
        }
        more.collect { value ->
            if (value <= asked) return@collect
            asked = value
            if (!paging.canAsk) return@collect
            // A new page: what the last one added is no longer this page's, so a refusal of it takes nothing away.
            paging = paging.copy(
                loading = true,
                refused = null,
                lastRows = emptyList(),
                lastLeftOut = AddressIndexLeftOut(),
            )
            emit(paging)
            when {
                // The kept list's catching up again, after a refusal of its newer entries.
                !caughtUp -> {
                    val (list, done) = catchUp(account, paging)
                    paging = list
                    caughtUp = done
                }
                // Nothing read through yet (the newest page refused before): this is the newest page again.
                paging.top == null -> paging = newest(account, paging)
                else -> load(account, paging.next, PAGE_SIZE, known = paging.known).collect { progress ->
                    paging = paging.after(progress)
                    emit(paging)
                    if (progress is AddressIndexLoad) settled(account, paging, progress)
                }
            }
            // What was asked while it was read and checked was asked of this page.
            asked = maxOf(asked, more.value)
        }
    }

    /**
     * The newest page of [account]'s history, with nothing read through yet: [paging] after it, read through the
     * newest block the index covers once it is loaded.
     */
    private suspend fun FlowCollector<IndexPaging>.newest(account: AccountId, paging: IndexPaging): IndexPaging {
        var list = paging
        var top: Long? = null
        load(account, limit = PAGE_SIZE).collect { progress ->
            if (progress is AddressIndexProgress.Read) top = progress.coverage.covered?.last
            list = list.after(progress)
            if (progress is AddressIndexLoad.Loaded) list = list.copy(top = top)
            emit(list)
            if (progress is AddressIndexLoad) settled(account, list, progress)
        }
        return list
    }

    /**
     * A kept list ([paging], resumed) brought up to date: the entries newer than it, then, once those are all read,
     * the entries waiting for a check and the ranges a read could not get, each once per opening. [paging] after it,
     * and whether the newer entries were all read.
     */
    private suspend fun FlowCollector<IndexPaging>.catchUp(
        account: AccountId,
        paging: IndexPaging,
    ): Pair<IndexPaging, Boolean> {
        val (prepended, done) = prepend(account, paging)
        if (!done) return prepended to false
        var list = recheck(account, prepended)
        if (list.refused == null) list = reread(account, list)
        // The list reads on as it nears its end, as after any page that added rows: the kept ones count as its last.
        list = list.copy(lastRows = list.lastRows.ifEmpty { list.rows })
        if (list.refused == null) settled(account, list, null)
        emit(list)
        return list to true
    }

    /**
     * The entries of [account]'s history newer than the list kept ([paging], resumed), read page by page down to its
     * top, each one the list has no answer for checked, each verified one prepended as it comes: [paging] after
     * them, and whether they were all read. The list's continuation below stays the kept one's. Each page is kept as
     * it loads, its entries by themselves, so that a refusal later reads none of them twice; the top moves to the
     * newest block read only once all are read.
     */
    private suspend fun FlowCollector<IndexPaging>.prepend(
        account: AccountId,
        paging: IndexPaging,
    ): Pair<IndexPaging, Boolean> {
        val top = paging.top ?: return paging to true
        val older = paging.next
        var list = paging
        var cursor: AddressIndexContinuation? = null
        var newTop: Long? = null
        while (true) {
            // A refusal of a page takes away that page's rows only, never the kept ones.
            list = list.copy(lastRows = emptyList(), lastLeftOut = AddressIndexLeftOut())
            var last: AddressIndexLoad? = null
            load(account, cursor, PAGE_SIZE, newerThan = top, known = list.known).collect { progress ->
                if (progress is AddressIndexProgress.Read && newTop == null) newTop = progress.coverage.covered?.last
                list = list.after(progress).copy(next = older)
                if (progress is AddressIndexLoad) last = progress
                emit(list)
            }
            val loaded = last as? AddressIndexLoad.Loaded
            if (loaded == null) {
                last?.let { settled(account, list, it) }
                return list.copy(lastRows = list.lastRows.ifEmpty { list.rows }) to false
            }
            if (loaded.next == null) {
                return list.copy(top = maxOf(top, newTop ?: top)) to true
            }
            settled(account, list, loaded)
            cursor = loaded.next
        }
    }

    /**
     * Up to [PAGE_SIZE] of the entries [paging] keeps for a check, newest first, checked again, each verified one a
     * row in its place as it comes: [paging] after them. A check none of them answers now (the light client catching
     * up, no peer serving their blocks) changes nothing: they wait for the next opening, and the list stands as it
     * was. A lasting refusal is said, and the checkpoint table's alarm withdraws the list.
     */
    private suspend fun FlowCollector<IndexPaging>.recheck(account: AccountId, paging: IndexPaging): IndexPaging {
        val coverage = paging.coverage ?: return paging
        val waiting = paging.recheck
            .sortedWith(compareByDescending<AddressIndexEntry> { it.block }.thenByDescending { it.slot })
            .take(PAGE_SIZE)
        if (waiting.isEmpty()) return paging
        var list = paging.copy(lastRows = emptyList(), lastLeftOut = AddressIndexLeftOut())
        var last: AddressIndexLoad? = null
        load.again(account, waiting, coverage, paging.next).collect { progress ->
            list = list.after(progress)
            if (progress is AddressIndexLoad) last = progress
            emit(list)
        }
        val refused = (last as? AddressIndexLoad.Refused)?.reason ?: return list
        if (refused.retryable) {
            emit(paging)
            return paging
        }
        settled(account, list, last)
        return list
    }

    /**
     * Each range [paging] could not get from Swarm before and that the reads of this opening did not miss again, read
     * again from the index, one page each, each entry the list has no answer for checked: [paging] after them. A
     * range read in part goes on below what that page reached at the next opening. A refusal stops it, the ranges
     * left to the next opening; the checkpoint table's alarm withdraws the list.
     */
    private suspend fun FlowCollector<IndexPaging>.reread(account: AccountId, paging: IndexPaging): IndexPaging {
        val missedNow = paging.coverage?.gaps.orEmpty()
        var list = paging
        for (range in paging.unread.filter { range -> missedNow.none { it.overlaps(range) } }) {
            val before = list
            list = list.copy(
                unread = list.unread - setOf(range),
                lastRows = emptyList(),
                lastLeftOut = AddressIndexLeftOut(),
            )
            var last: AddressIndexLoad? = null
            load(account, limit = PAGE_SIZE, known = list.known, range = range).collect { progress ->
                // A range's read says nothing of the index's coverage beyond it: the list keeps its own.
                list = list.after(progress).copy(coverage = before.coverage, next = before.next)
                if (progress is AddressIndexLoad) last = progress
                emit(list)
            }
            when (val end = last) {
                is AddressIndexLoad.Loaded -> {
                    // What lies below the page read in the range is read at a later opening.
                    val rest = end.reached?.let { range.first..minOf(range.last, it) }
                        ?.takeIf { end.next != null && !it.isEmpty() }
                    if (rest != null) list = list.copy(unread = list.unread + listOf(rest))
                }
                is AddressIndexLoad.Refused -> {
                    if (end.reason.retryable) return before
                    settled(account, list, end)
                    return list
                }
                null -> return before
            }
        }
        return list
    }

    /**
     * What [load], a page's or a check's end, means for what is kept of [account]'s list: [paging] kept after a page
     * loaded (or with no load: after the opening caught up); nothing kept any more on the checkpoint table's alarm,
     * which withdraws the trust the rows rest on (`.claude/rules/trust.md`, *The addr1 history*). A list not read
     * through any block yet, or after any other refusal, changes nothing of what is kept.
     */
    private suspend fun settled(account: AccountId, paging: IndexPaging, load: AddressIndexLoad?) {
        val keep = keep ?: return
        if (load is AddressIndexLoad.Refused) {
            if (load.reason is AddressIndexRefusal.CheckpointAlarm) keep.forget(account)
            return
        }
        val coverage = paging.coverage ?: return
        val top = paging.top ?: return
        keep.keep(
            account,
            coverage,
            top,
            paging.rows,
            paging.recheck,
            paging.out,
            paging.notFinal,
            paging.unread,
            paging.next,
        )
    }

    internal companion object {
        /**
         * The entries one page reads and checks: about a screen of rows and one batch more. The next page is read
         * when the list reaches the end of these, so no more old blocks are checked than the user may see soon.
         */
        const val PAGE_SIZE = 20
    }
}

/**
 * The pages of an account's addr1 history read so far, as one list: its verified [rows] (each entry once, by block
 * and slot), what the index covered at the last page read ([coverage]; null before the first) and the key [pinned]
 * in the release it rests on, the entries every page [leftOut], where it goes on ([next]), whether a page is being
 * read ([loading]), and why the last read was [refused], if it was. Of the last page read, its verified rows
 * ([lastRows]) and what it left out ([lastLeftOut]): whether it added a row to the list decides whether the next page
 * is read by itself. [pending] are the entries of the page read whose check has not answered yet, newest first: the
 * importer's word that they are there, nothing more, so a list shows each as a ghost row in its place, with nothing
 * of it but that it is being checked. [checking] while a page read is not done with: from its read to the end of its
 * load, its last check answered or not, so that nothing more is asked for in between. [top] is the newest block the
 * list was read through (null until a page is: every entry above it is new), and [recheck] the entries read whose
 * check gave no lasting answer yet, kept to be checked again: what [AddressIndexActivity] keeps between runs.
 */
internal data class IndexPaging(
    val pinned: Address,
    val rows: List<AddressIndexRow> = emptyList(),
    val pending: List<AddressIndexEntry> = emptyList(),
    val checking: Boolean = false,
    val coverage: AddressIndexCoverage? = null,
    val leftOut: AddressIndexLeftOut = AddressIndexLeftOut(),
    val next: AddressIndexContinuation? = null,
    val loading: Boolean = true,
    val refused: AddressIndexRefusal? = null,
    val lastRows: List<AddressIndexRow> = emptyList(),
    val lastLeftOut: AddressIndexLeftOut = AddressIndexLeftOut(),
    val top: Long? = null,
    val recheck: List<AddressIndexEntry> = emptyList(),
    val out: AddressIndexLeftOutEntries = AddressIndexLeftOutEntries(),
    val notFinal: List<AddressIndexEntry> = emptyList(),
    val unread: List<LongRange> = emptyList(),
) {
    /**
     * Every entry the list has an answer for, or waits for one, by block and slot: a later read neither checks nor
     * counts these again.
     */
    val known: Set<Pair<Long, Int>>
        get() = rows.mapTo(HashSet()) { it.entry.block to it.entry.slot } +
            out.keys + recheck.map { it.block to it.slot }

    /**
     * What the list says of the index's coverage: [coverage], with the ranges a read could not get before ([unread])
     * among its gaps, so that none of them goes unsaid.
     */
    val statedCoverage: AddressIndexCoverage?
        get() = coverage?.let { coverage ->
            val covered = coverage.covered ?: return@let coverage
            val gaps = (coverage.gaps + unread)
                .mapNotNull { gap ->
                    (maxOf(gap.first, covered.first)..minOf(gap.last, covered.last)).takeUnless { it.isEmpty() }
                }
                .sortedBy { it.first }
                .fold(emptyList<LongRange>()) { merged, gap ->
                    val last = merged.lastOrNull()
                    if (last != null && gap.first <= last.last + 1) {
                        merged.dropLast(1) + listOf(last.first..maxOf(last.last, gap.last))
                    } else {
                        merged + listOf(gap)
                    }
                }
            coverage.copy(gaps = gaps)
        }

    /** Whether a page was read: [coverage] is known, and [next] null means the end of its coverage. */
    val started: Boolean
        get() = coverage != null

    /**
     * Whether the end of what the index covers was reached: no page lies below. Not while a page is refused: its
     * coverage came with its read, but where the list goes on comes with its end, so asking again reads it again.
     */
    val ended: Boolean
        get() = started && next == null && refused == null

    /**
     * Whether another page may be asked for now: none is being read or checked, more lies below, nothing lasting
     * refused it.
     */
    val canAsk: Boolean
        get() = !loading && !checking && !ended && refused?.retryable != false

    /**
     * This list after [load], one step of a page's load: the page read, its entries pending; an entry checked, a
     * verified one a row at once; or the whole page's load, its rows added, newest first, and its counts; or the
     * refusal said, the rows of earlier pages kept but
     * on the checkpoint table's alarm, which withdraws the trust they rest on (`.claude/rules/trust.md`), and on an
     * index signed by another key than the pinned one, which the list may not rest on. With the alarm, what the
     * index said of its coverage and its counts go too. For now the alarm reaches the list only this way, as a read
     * refused for it; #363 feeds the engine's alarm itself (`StatusSnapshot.checkpointAlarm`) in, so that the rows go
     * the moment it is raised, whether or not a page is read.
     */
    fun after(load: AddressIndexProgress): IndexPaging = when (load) {
        // The page is read: its entries wait for their checks, each in its place.
        is AddressIndexProgress.Read -> copy(
            // An entry listed already (a page that repeats the one before at its edge) is that row alone.
            pending = load.entries.distinct().filterNot { entry ->
                rows.any { it.entry.block == entry.block && it.entry.slot == entry.slot }
            },
            checking = true,
            // What the index covers is said before any row of the page, never after its rows came in. Where the list
            // goes on is taken at the page's end only: a page whose checks could not be made now is refused as
            // passing, and asking again must read it again, not the page below it.
            coverage = load.coverage,
            loading = false,
            refused = null,
            lastRows = emptyList(),
            lastLeftOut = AddressIndexLeftOut(),
        )
        // One entry answered: a verified one is a row at once; any other is only no longer pending, counted at the end.
        is AddressIndexProgress.Checked -> {
            val row = (load.verdict as? AddressIndexVerdict.Verified)?.row
            // Only a row the list did not hold is this page's: a refusal takes this page's rows away, never another's.
            val added = row?.takeIf { new ->
                rows.none { it.entry.block == new.entry.block && it.entry.slot == new.entry.slot }
            }
            copy(
                rows = row?.let { listed(rows + it) } ?: rows,
                pending = pending - load.entry,
                lastRows = added?.let { lastRows + it } ?: lastRows,
                recheck = recheck.filterNot { it.block == load.entry.block && it.slot == load.entry.slot },
            )
        }
        is AddressIndexLoad.Loaded -> copy(
            rows = listed(rows + load.rows),
            pending = emptyList(),
            checking = false,
            coverage = load.coverage,
            leftOut = leftOut + load.leftOut,
            next = load.next,
            loading = false,
            refused = null,
            // The page's own rows are those its checks added, as `Checked` kept them: a row it only repeats (the
            // edge of the page before) is no row it added, so a page of repeats pauses the endless list.
            lastLeftOut = load.leftOut,
            // An entry no check answered this time is checked again at a later read; what it left out for good, and
            // its verdicts that may still be undone, are kept by entry; a range it could not get is read again.
            recheck = (recheck + load.unavailable).distinct(),
            out = out + load.out,
            notFinal = (notFinal + load.notFinal).distinct(),
            unread = (unread + load.coverage.gaps).distinct(),
        )
        is AddressIndexLoad.Refused -> when (load.reason) {
            is AddressIndexRefusal.CheckpointAlarm -> IndexPaging(pinned, loading = false, refused = load.reason)
            is AddressIndexRefusal.WrongImporter ->
                copy(
                    rows = emptyList(),
                    pending = emptyList(),
                    checking = false,
                    loading = false,
                    refused = load.reason,
                )
            // What this page verified before the refusal goes with it, as a page refused whole never added any.
            else -> copy(
                rows = rows - lastRows.toSet(),
                pending = emptyList(),
                checking = false,
                loading = false,
                refused = load.reason,
                lastRows = emptyList(),
            )
        }
    }

    /** [rows] as the list holds them: each entry once, by block and slot, newest first. */
    private fun listed(rows: List<AddressIndexRow>): List<AddressIndexRow> = rows
        .distinctBy { it.entry.block to it.entry.slot }
        .sortedWith(compareByDescending<AddressIndexRow> { it.entry.block }.thenByDescending { it.entry.slot })

    internal companion object {
        /**
         * The list [resume] kept, under [pinned]: its rows shown at once, as the last ones it added, and its counts,
         * coverage and continuation; [loading], since what is newer is read next.
         */
        fun resumed(pinned: Address, resume: AddressIndexResume): IndexPaging = IndexPaging(
            pinned = pinned,
            rows = resume.kept.rows,
            coverage = resume.kept.coverage,
            leftOut = resume.leftOut,
            next = resume.next,
            loading = true,
            lastRows = resume.kept.rows,
            top = resume.kept.top,
            recheck = resume.kept.recheck,
            out = resume.out,
            unread = resume.kept.unread,
        )
    }
}

/**
 * [paging] as the Activity says it (docs/wallet-ux.md, screen 25): what the index is and what it rests on, the
 * importer named in full; every range it does not cover; how many entries it left out, and apart, how many the chain
 * contradicts; and its footer. [network] names a network in a refusal. [shownFromLast] is how many of the last page's
 * rows the list shows (the rest were listed already, or are not listed); null while the rows cannot be said yet (the
 * tokens not read).
 */
internal fun indexSection(paging: IndexPaging, network: Network, shownFromLast: Int?): IndexSection {
    val key = Eip55.format(paging.pinned)
    val coverage = paging.statedCoverage
    val lines = buildList {
        add(
            "Also from the addr1 index on Swarm, each entry checked against the chain before it is listed. Which " +
                "entries it holds rests on its importer, $key, pinned in this release: entries may be missing.",
        )
        coverage?.let { addAll(coverageLines(it)) }
    }
    val simpleLines = buildList {
        add(
            "Older activity also comes from a shared index, each entry checked against the chain. Whether it is " +
                "complete rests on its publisher, key $key: some activity may be missing.",
        )
        coverage?.let { addAll(simpleCoverageLines(it)) }
    }
    val leftOut = paging.leftOut
    return IndexSection(
        lines = lines,
        simpleLines = simpleLines,
        leftOut = listOfNotNull(
            leftOut.cannotVerify.takeIf { it > 0 }?.let { n ->
                "${entries(n)} of the index ${if (n == 1) "is" else "are"} left out: " +
                    "${if (n == 1) "it" else "they"} could not be verified."
            },
            // Never checked, which is not "cannot be verified": the account's screen opened again checks them again.
            leftOut.unavailable.takeIf { it > 0 }?.let { n ->
                "${entries(n)} could not be checked right now (no peer served ${if (n == 1) "its" else "their"} " +
                    "blocks in time): open the account again to check ${if (n == 1) "it" else "them"} again."
            },
        ).joinToString(" ").ifEmpty { null },
        contradicted = leftOut.contradicted.takeIf { it > 0 }?.let { n ->
            "Warning: the importer's index contradicts the chain in ${entries(n)}, left out. An index that says " +
                "something the chain proves false may also leave entries out or be wrong elsewhere; its importer " +
                "is ${shortAddress(key)}."
        },
        simpleLeftOut = listOfNotNull(
            leftOut.cannotVerify.takeIf { it > 0 }?.let { n ->
                val shown = if (n == 1) "it isn't" else "they aren't"
                "${entries(n)} of the shared index couldn't be confirmed, so $shown shown."
            },
            leftOut.unavailable.takeIf { it > 0 }?.let { n ->
                "${entries(n)} couldn't be checked right now. Open the account again to try once more."
            },
        ).joinToString(" ").ifEmpty { null },
        simpleContradicted = leftOut.contradicted.takeIf { it > 0 }?.let { n ->
            "Warning: the shared index's publisher listed ${entries(n)} the chain shows to be wrong. " +
                "${if (n == 1) "It isn't" else "They aren't"} shown, and the index may also miss or get others wrong."
        },
        footer = footerOf(paging, network, shownFromLast),
    )
}

/**
 * The footer below the index's rows: loading, more, the end of its coverage, or why nothing (more) comes. More is read
 * by itself only while the last page added a row to the list ([shownFromLast]): each page sets off up to 20 checks of
 * old blocks against archive peers, so after a page that added none, the next is read only on the user's tap
 * ([IndexFooter.Paused]), which says what that page left out.
 */
private fun footerOf(paging: IndexPaging, network: Network, shownFromLast: Int?): IndexFooter {
    paging.refused?.let { reason ->
        return IndexFooter.Refused(
            indexRefusalText(reason, network),
            retry = reason.retryable,
            warning = reason is AddressIndexRefusal.CheckpointAlarm,
            simpleText = indexRefusalSimpleText(reason, network),
        )
    }
    if (paging.loading) {
        return if (paging.started) {
            IndexFooter.Loading("Reading more of the addr1 index and checking its entries…", CHECKING_MORE)
        } else {
            IndexFooter.Loading(
                "Reading the addr1 index from Swarm and checking its entries…",
                "Checking older activity from the shared index…",
            )
        }
    }
    // Entries of the page read still wait for their checks: each is a ghost row in its place; the footer counts them.
    if (paging.checking) {
        val n = paging.pending.size
        return if (n == 0) {
            IndexFooter.Checking("Checking the addr1 index's entries against the chain…", CHECKING_MORE)
        } else {
            IndexFooter.Checking(
                "Checking ${entries(n)} of the addr1 index against the chain…",
                "Checking ${if (n == 1) "1 more entry" else "$n more entries"} of the shared index…",
            )
        }
    }
    val covered = paging.coverage?.covered
    return when {
        !paging.ended && shownFromLast == null ->
            IndexFooter.Loading("Reading the tokens its rows are said in…", CHECKING_MORE)
        !paging.ended && shownFromLast == 0 -> IndexFooter.Paused(pausedText(paging), simplePausedText(paging))
        !paging.ended -> IndexFooter.More
        covered == null -> IndexFooter.End(
            "The addr1 index covers no block yet: nothing comes from it.",
            "The shared index holds nothing yet.",
        )
        covered.first == 0L -> IndexFooter.End(
            "The addr1 index starts at the first block: this is everything it holds.",
            "That's everything the shared index holds.",
        )
        else -> IndexFooter.End(
            "The addr1 index starts at block ${grouped(covered.first)}: nothing older comes from it.",
            "The shared index starts here: nothing older comes from it.",
        )
    }
}

/** Why the list waits for a tap: what the last page of [paging] held, none of it a new row. */
private fun pausedText(paging: IndexPaging): String {
    val left = paging.lastLeftOut
    val listed = paging.lastRows.size
    val parts = listOfNotNull(
        left.cannotVerify.takeIf { it > 0 }?.let { "${entries(it)} could not be verified" },
        left.unavailable.takeIf { it > 0 }?.let { "${entries(it)} could not be checked right now" },
        left.contradicted.takeIf { it > 0 }?.let {
            "${entries(it)} ${if (it == 1) "contradicts" else "contradict"} the chain"
        },
        listed.takeIf { it > 0 }?.let {
            "${entries(it)} ${if (it == 1) "was listed already or is" else "were listed already or are"} not listed"
        },
    )
    val what = if (parts.isEmpty()) {
        "held no entry to list"
    } else {
        "added no row: " + parts.joinToString(", ")
    }
    return "The last page of the addr1 index $what. The next is read only when you ask, since each page fetches " +
        "old blocks from archive peers."
}

/** [pausedText] in simple mode's words. */
private fun simplePausedText(paging: IndexPaging): String {
    val left = paging.lastLeftOut
    val listed = paging.lastRows.size
    val parts = listOfNotNull(
        left.cannotVerify.takeIf { it > 0 }?.let { "${entries(it)} couldn't be confirmed" },
        left.unavailable.takeIf { it > 0 }?.let { "${entries(it)} couldn't be checked right now" },
        left.contradicted.takeIf { it > 0 }?.let { "${entries(it)} the chain shows to be wrong" },
        listed.takeIf { it > 0 }?.let { "${entries(it)} ${if (it == 1) "is" else "are"} shown already" },
    )
    val what = if (parts.isEmpty()) "held nothing to show" else "added nothing here: " + parts.joinToString(", ")
    return "The last part of the shared index $what. More is checked only when you ask, since each part means a " +
        "lot of downloading."
}

/** The entries that cannot be verified, asking again or not: the engine's unverified ones and the `TRACE`-only. */
private val AddressIndexLeftOut.cannotVerify: Int
    get() = unverified + traceOnly

/** Simple mode's words while more of the index is read. */
private const val CHECKING_MORE = "Checking more of the shared index…"

/** Every range [coverage] leaves out, with block numbers: where it starts, the head gap, the gaps inside it. */
private fun coverageLines(coverage: AddressIndexCoverage): List<String> = buildList {
    val covered = coverage.covered
    if (covered == null) {
        add("It covers no block yet" + (coverage.tailGap?.let { ": ${range(it)} are not in it." } ?: "."))
    } else if (covered.first == 0L) {
        add("It covers the blocks from the first to ${grouped(covered.last)}.")
    } else {
        add(
            "It covers blocks ${grouped(covered.first)} to ${grouped(covered.last)}: older blocks are not in it.",
        )
    }
    coverage.headGap?.let { gap ->
        add(
            gap.toBlock?.let { "It has not reached the newest blocks yet, ${range(gap.fromBlock..it)}." }
                ?: "It has not reached the blocks from ${grouped(gap.fromBlock)} on yet.",
        )
    }
    if (coverage.gaps.isNotEmpty()) {
        val shown = coverage.gaps.take(MAX_GAPS_NAMED).joinToString(", ") { range(it) }
        val more = coverage.gaps.size - MAX_GAPS_NAMED
        add(
            "Inside it, ${if (coverage.gaps.size == 1) "a range" else "ranges"} could not be read: $shown" +
                (if (more > 0) " and $more more" else "") + ". Entries there may be missing.",
        )
    }
}

/** [coverageLines] in simple mode's words, with no block number. */
private fun simpleCoverageLines(coverage: AddressIndexCoverage): List<String> = buildList {
    if (coverage.covered == null) add("It holds nothing yet.")
    if (coverage.headGap != null) add("It hasn't reached the newest activity yet.")
    if (coverage.gaps.isNotEmpty()) add("Parts of it couldn't be read, so some activity may be missing.")
}

/** Blocks [range], as a line says them: "block 9", "blocks 9 to 12". */
private fun range(range: LongRange): String = if (range.first == range.last) {
    "block ${grouped(range.first)}"
} else {
    "blocks ${grouped(range.first)} to ${grouped(range.last)}"
}

/** Whether these blocks and [other] share one at least. */
private fun LongRange.overlaps(other: LongRange): Boolean = first <= other.last && other.first <= last

/** [n] entries, in words: "1 entry", "3 entries". */
private fun entries(n: Int): String = if (n == 1) "1 entry" else "$n entries"

/** At most this many inner gaps are named; the rest are counted. */
private const val MAX_GAPS_NAMED = 3

/**
 * Why the addr1 index was not read or checked, as the Activity and a row's detail say it (every reason stated, never
 * an empty list): [network] is the account's.
 */
internal fun indexRefusalText(reason: AddressIndexRefusal, network: Network): String = when (reason) {
    AddressIndexRefusal.NotInThisBuild ->
        "This build's engine has no Swarm reader, so nothing comes from the addr1 index here."
    AddressIndexRefusal.SettingOff ->
        "The engine reads no addr1 index: the experimental setting is off. Settings → Experimental turns it on."
    AddressIndexRefusal.TorRouting ->
        "Tor routing is on, and the addr1 index is not read over Tor: nothing comes from it while Tor is on."
    is AddressIndexRefusal.NetworkNotServed ->
        "The addr1 index is not read for ${reason.network.displayName} here."
    is AddressIndexRefusal.NoKeyPinned ->
        "No importer key is pinned for ${reason.network.displayName} in this release, so no addr1 index is read " +
            "for it."
    is AddressIndexRefusal.CheckpointAlarm ->
        "The engine's checkpoint table and the beacon chain disagree, so nothing that rests on the table is shown, " +
            "the index's rows among it. The engine says: ${reason.text}"
    AddressIndexRefusal.StaleAnchor ->
        "${network.displayName}'s light client is parked on a checkpoint too old to catch up from, so no entry " +
            "can be checked. Accounts shows the ways on."
    AddressIndexRefusal.NoEngine ->
        "No light client runs for ${network.displayName} on this device, so no entry of the index can be checked."
    is AddressIndexRefusal.WrongImporter ->
        "The index read is signed by ${Eip55.format(reason.named)}, not by the importer pinned in this release, " +
            "${Eip55.format(reason.pinned)}: none of its entries is shown, and no more is read."
    is AddressIndexRefusal.Rejected ->
        "The engine refused the request (${reason.reason}). That is a bug in Punicus or the engine: nothing more " +
            "comes from the index until it is fixed."
    is AddressIndexRefusal.NotNow -> when (reason.reason) {
        ReadUnavailable.SYNCING ->
            "The light client is still catching up, so the index's entries cannot be checked yet."
        ReadUnavailable.NETWORK_OFF -> "${network.displayName}'s light client is not running yet."
        else -> "Swarm or the peers did not deliver what the index or its check needs right now."
    }
}

/** [indexRefusalText] in simple mode's words: no block number, no quote of the engine. */
internal fun indexRefusalSimpleText(reason: AddressIndexRefusal, network: Network): String = when (reason) {
    AddressIndexRefusal.NotInThisBuild -> "This version can't read the shared index here."
    AddressIndexRefusal.SettingOff -> "The shared index is off. Settings → Experimental turns it on."
    AddressIndexRefusal.TorRouting -> "Tor is on, and the shared index isn't read over Tor."
    is AddressIndexRefusal.NetworkNotServed -> "The shared index isn't read for ${reason.network.displayName} here."
    is AddressIndexRefusal.NoKeyPinned -> "There's no shared index for ${reason.network.displayName} in this version."
    is AddressIndexRefusal.CheckpointAlarm -> "Older activity from the shared index can't be shown: the checkpoints " +
        "Punicus relies on disagree with the network."
    AddressIndexRefusal.StaleAnchor -> "${network.displayName} can't be checked right now. Accounts shows the ways on."
    AddressIndexRefusal.NoEngine ->
        "${network.displayName} isn't running on this device, so the shared index can't be checked."
    is AddressIndexRefusal.WrongImporter ->
        "The shared index found isn't the one this version trusts, so none of it is shown."
    is AddressIndexRefusal.Rejected -> "Something went wrong reading the shared index. Nothing more comes from it."
    is AddressIndexRefusal.NotNow -> when (reason.reason) {
        ReadUnavailable.SYNCING -> "Still catching up, so the shared index can't be checked yet."
        ReadUnavailable.NETWORK_OFF -> "${network.displayName} isn't running yet."
        else -> "The shared index couldn't be read right now."
    }
}

/** [roles] in words, in the order the index records them: "sender, a log's topic". */
internal fun rolesText(roles: Set<AddressIndexRole>): String =
    AddressIndexRole.entries.filter { it in roles }.joinToString(", ") { role ->
        when (role) {
            AddressIndexRole.FROM -> "sender"
            AddressIndexRole.TO -> "recipient"
            AddressIndexRole.EMITTER -> "emitter of a log"
            AddressIndexRole.TOPIC -> "a log's topic"
            AddressIndexRole.WORD -> "in its input or a log's data"
            AddressIndexRole.TRACE -> "reached by an inner call"
            AddressIndexRole.BLOCK -> "named by the block (fee recipient, withdrawal or uncle)"
            AddressIndexRole.AUTH -> "an EIP-7702 authority"
        }
    }
