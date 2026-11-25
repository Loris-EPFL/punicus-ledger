package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCoverage
import eth.serotine.domain.model.AddressIndexCursor
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexHeadGap
import eth.serotine.domain.model.AddressIndexRole
import eth.serotine.domain.model.AddressIndexRole.FROM
import eth.serotine.domain.model.AddressIndexRole.TO
import eth.serotine.domain.model.AddressIndexRole.TRACE
import eth.serotine.domain.model.AddressIndexRow
import eth.serotine.domain.model.AddressIndexTransaction
import eth.serotine.domain.model.AddressIndexVerdict
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AddressIndexCheck
import eth.serotine.domain.repository.AddressIndexPage
import eth.serotine.domain.repository.AddressIndexQuery
import eth.serotine.domain.repository.AddressIndexRefusal
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** [LoadAddressIndexPage] over a fake addr1 index. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddressIndexHistoriesTest {

    private val alice = Address.of("0x" + "11".repeat(20))
    private val bob = Address.of("0x" + "22".repeat(20))
    private val importer = Address.of("0x" + "1e".repeat(20))
    private val account = AccountId(Network.ETHEREUM, alice)
    private val coverage = AddressIndexCoverage(
        importer = importer,
        headUpdate = 7,
        covered = 21_000_000L..21_200_000L,
        headGap = AddressIndexHeadGap(21_200_001, 21_200_100),
        tailGap = null,
        gaps = listOf(21_100_000L..21_100_999L),
    )
    private val index = FakeAddressIndexHistory()

    private fun loader(
        attempts: Int = 3,
        retryPause: Duration = 2.seconds,
        budget: Duration = 5.minutes,
        checkBudget: Duration = 2.minutes,
    ) = LoadAddressIndexPage(index, importer, attempts, retryPause, budget, checkBudget)

    private fun entry(block: Long, slot: Int, vararg roles: AddressIndexRole) =
        AddressIndexEntry(block, slot, roles.toSet())

    private fun rowOf(entry: AddressIndexEntry) = AddressIndexRow(
        entry = entry,
        blockHash = "0x" + "bb".repeat(32),
        final = true,
        confirmed = entry.roles - TRACE,
        unchecked = entry.roles intersect setOf(TRACE),
        transaction = if (entry.slot == 0) {
            null
        } else {
            AddressIndexTransaction("0x" + "cc".repeat(32), alice, bob, Wei.of("1000"), transfer = null)
        },
    )

    private fun verified(entry: AddressIndexEntry) = AddressIndexVerdict.Verified(rowOf(entry))

    private fun page(entries: List<AddressIndexEntry>, next: String? = null, signedBy: Address = importer) =
        AddressIndexPage(coverage.copy(importer = signedBy), entries, next?.let(::AddressIndexCursor))

    private fun verifyingAll() {
        index.check = { entries, _ -> AddressIndexCheck.Verdicts(entries.map(::verified)) }
    }

    @Test
    fun onlyVerifiedEntriesBecomeRowsNewestFirstAndEveryOtherIsCounted() = runTest {
        val sent = entry(21_150_000, 4, FROM)
        val wrong = entry(21_140_000, 2, TO)
        val unanchored = entry(21_130_000, 1, FROM)
        val traced = entry(21_120_000, 3, TRACE)
        val reward = entry(21_110_000, 0, AddressIndexRole.BLOCK)
        val unread = entry(21_105_000, 9, TO)
        index.pages[null] = page(listOf(sent, wrong, unanchored, traced, reward, unread))
        index.check = { entries, _ ->
            AddressIndexCheck.Verdicts(
                entries.map {
                    when (it) {
                        sent, reward -> verified(it)
                        wrong -> AddressIndexVerdict.Contradicted(it, "0x" + "dd".repeat(32), true, setOf(TO))
                        unanchored -> AddressIndexVerdict.Unverified(it, "below what the node can anchor")
                        else -> AddressIndexVerdict.Unavailable(it, "no peer served the receipts")
                    }
                },
            )
        }

        val load = assertIs<AddressIndexLoad.Loaded>(loader(attempts = 1)(account).last())

        assertEquals(listOf(rowOf(sent), rowOf(reward)), load.rows)
        val counted = AddressIndexLeftOut(unverified = 1, contradicted = 1, traceOnly = 1, unavailable = 1)
        assertEquals(counted, load.leftOut)
        assertEquals(4, load.leftOut.total)
        assertEquals(coverage, load.coverage)
        assertEquals(importer, load.coverage.importer)
        assertTrue(load.endOfCoverage)
    }

    @Test
    fun aTraceOnlyEntryIsNeverCheckedNorShownButOneWithAnotherRoleIs() = runTest {
        val traced = entry(21_150_000, 2, TRACE)
        val mixed = entry(21_140_000, 1, FROM, TRACE)
        index.pages[null] = page(listOf(traced, mixed))
        verifyingAll()

        val load = assertIs<AddressIndexLoad.Loaded>(loader()(account).last())

        assertEquals(listOf(listOf(mixed)), index.checks)
        assertEquals(listOf(rowOf(mixed)), load.rows)
        assertEquals(setOf(TRACE), load.rows.single().unchecked)
        assertEquals(AddressIndexLeftOut(traceOnly = 1), load.leftOut)
    }

    @Test
    fun aPageOfTraceOnlyEntriesAsksNoCheckAtAll() = runTest {
        index.pages[null] = page(listOf(entry(21_150_000, 2, TRACE), entry(21_140_000, 0, TRACE)))

        val load = assertIs<AddressIndexLoad.Loaded>(loader()(account).last())

        assertEquals(emptyList(), index.checks)
        assertEquals(emptyList(), load.rows)
        assertEquals(AddressIndexLeftOut(traceOnly = 2), load.leftOut)
    }

    @Test
    fun theCheckRunsBlockByBlockNewestFirstAndABlockOfMoreThanFiftyInChunks() = runTest {
        // One busy block of 120 entries, then two blocks of one and two.
        val busy = (0 until 120).map { entry(21_199_000L, 200 - it, FROM) }
        val single = entry(21_198_000L, 3, TO)
        val pair = listOf(entry(21_197_000L, 9, FROM), entry(21_197_000L, 2, TO))
        val entries = busy + single + pair
        index.pages[null] = page(entries)
        verifyingAll()

        val load = assertIs<AddressIndexLoad.Loaded>(loader()(account, limit = 123).last())

        assertEquals(listOf(50, 50, 20, 1, 2), index.checks.map { it.size })
        assertEquals(entries, index.checks.flatten())
        assertEquals(entries, load.rows.map { it.entry })
        assertEquals(123, index.queries.single().third.limit)
    }

    @Test
    fun eachVerdictIsEmittedAsItsBlocksCheckAnswersBetweenTheReadAndTheLoad() = runTest {
        val newest = entry(21_150_000, 4, FROM)
        val wrong = entry(21_140_000, 2, TO)
        val traced = entry(21_130_000, 3, TRACE)
        val older = entry(21_120_000, 1, FROM)
        index.pages[null] = page(listOf(newest, wrong, traced, older))
        index.checkTime = 10.seconds
        val contradicted = AddressIndexVerdict.Contradicted(wrong, "0x" + "dd".repeat(32), true, setOf(TO))
        index.check = { entries, _ ->
            AddressIndexCheck.Verdicts(entries.map { if (it == wrong) contradicted else verified(it) })
        }

        val progress = loader()(account).toList()

        assertEquals(AddressIndexProgress.Read(account, coverage, null, listOf(newest, wrong, older)), progress[0])
        assertEquals(
            listOf(
                AddressIndexProgress.Checked(newest, verified(newest)),
                AddressIndexProgress.Checked(wrong, contradicted),
                AddressIndexProgress.Checked(older, verified(older)),
            ),
            progress.subList(1, 4),
        )
        val load = assertIs<AddressIndexLoad.Loaded>(progress.last())
        assertEquals(5, progress.size)
        assertEquals(listOf(rowOf(newest), rowOf(older)), load.rows)
        assertEquals(AddressIndexLeftOut(contradicted = 1, traceOnly = 1), load.leftOut)
    }

    @Test
    fun aRefusedPageEmitsItsRefusalAloneAndNothingIsReadUntilCollected() = runTest {
        index.pageRefusal = AddressIndexRefusal.TorRouting
        val load = loader()(account)
        assertEquals(emptyList(), index.queries, "nothing is read before the flow is collected")

        val refused = AddressIndexLoad.Refused(AddressIndexRefusal.TorRouting)
        assertEquals(listOf<AddressIndexProgress>(refused), load.toList())
    }

    @Test
    fun anUnavailableEntryIsAskedAgainAfterThePauseAndThenShown() = runTest {
        val first = entry(21_150_000, 1, FROM)
        val second = entry(21_140_000, 1, FROM)
        index.pages[null] = page(listOf(first, second))
        var secondAsked = 0
        index.check = { entries, _ ->
            if (second in entries) secondAsked++
            AddressIndexCheck.Verdicts(
                entries.map {
                    if (it == second && secondAsked == 1) AddressIndexVerdict.Unavailable(it, "busy") else verified(it)
                },
            )
        }

        val load = assertIs<AddressIndexLoad.Loaded>(loader(retryPause = 3.seconds)(account).last())

        // One block at a time: the second block's entry is asked again after the pause.
        assertEquals(listOf(listOf(first), listOf(second), listOf(second)), index.checks)
        assertEquals(listOf(rowOf(first), rowOf(second)), load.rows)
        assertEquals(AddressIndexLeftOut(), load.leftOut)
        assertEquals(3_000, currentTime)
    }

    @Test
    fun anEntryStillUnavailableAfterTheLastAttemptIsCountedApart() = runTest {
        val unread = entry(21_150_000, 1, FROM)
        val missing = entry(21_140_000, 1, TO)
        val read = entry(21_130_000, 1, FROM)
        index.pages[null] = page(listOf(unread, missing, read))
        // No verdict at all for one entry counts as unavailable too.
        index.check = { entries, _ ->
            AddressIndexCheck.Verdicts(
                entries.mapNotNull {
                    when (it) {
                        unread -> AddressIndexVerdict.Unavailable(it, "x")
                        read -> verified(it)
                        else -> null
                    }
                },
            )
        }

        val load = assertIs<AddressIndexLoad.Loaded>(loader(attempts = 3)(account).last())

        // Three blocks: the two unanswered ones asked three times each, the verified one once.
        assertEquals(7, index.checks.size)
        assertEquals(listOf(rowOf(read)), load.rows)
        assertEquals(AddressIndexLeftOut(unavailable = 2), load.leftOut)
    }

    @Test
    fun aVerdictForAnotherEntryOrTwoVerdictsForOneNeverMakeARow() = runTest {
        val asked = entry(21_150_000, 1, FROM)
        val torn = entry(21_140_000, 1, FROM)
        val plain = entry(21_130_000, 1, FROM)
        index.pages[null] = page(listOf(asked, torn, plain))
        index.check = { _, _ ->
            AddressIndexCheck.Verdicts(
                listOf(
                    verified(entry(21_150_000, 1, TO)),
                    verified(torn),
                    AddressIndexVerdict.Contradicted(torn, "0x" + "dd".repeat(32), true, setOf(FROM)),
                    verified(plain),
                ),
            )
        }

        val load = assertIs<AddressIndexLoad.Loaded>(loader(attempts = 1)(account).last())

        assertEquals(listOf(rowOf(plain)), load.rows)
        assertEquals(AddressIndexLeftOut(unavailable = 2), load.leftOut)
    }

    @Test
    fun theNextPageIsReadBelowTheCursorUntilTheEndOfCoverage() = runTest {
        val newer = entry(21_150_000, 1, FROM)
        val older = entry(21_010_000, 5, TO)
        index.pages[null] = page(listOf(newer), next = "7:21150000:1")
        index.pages[AddressIndexCursor("7:21150000:1")] = page(listOf(older))
        verifyingAll()
        val load = loader()

        val first = assertIs<AddressIndexLoad.Loaded>(load(account).last())
        val next = assertIs<AddressIndexContinuation>(first.next)
        val second = assertIs<AddressIndexLoad.Loaded>(load(account, after = next).last())

        assertEquals(false, first.endOfCoverage)
        assertEquals(AddressIndexContinuation(account, AddressIndexCursor("7:21150000:1")), next)
        assertEquals(
            listOf(AddressIndexQuery(), AddressIndexQuery(cursor = AddressIndexCursor("7:21150000:1"))),
            index.queries.map { it.third },
        )
        assertEquals(listOf(rowOf(newer)), first.rows)
        assertEquals(listOf(rowOf(older)), second.rows)
        assertTrue(second.endOfCoverage)
    }

    @Test
    fun aPageSignedByAKeyOtherThanThePinnedOneIsRefusedUnchecked() = runTest {
        val other = Address.of("0x" + "0e".repeat(20))
        index.pages[null] = page(listOf(entry(21_150_000, 1, FROM)), next = "7:21150000:1")
        index.pages[AddressIndexCursor("7:21150000:1")] = page(listOf(entry(21_010_000, 5, TO)), signedBy = other)
        verifyingAll()
        val load = loader()
        val first = assertIs<AddressIndexLoad.Loaded>(load(account).last())
        index.checks.clear()

        val refused = AddressIndexLoad.Refused(AddressIndexRefusal.WrongImporter(importer, other))
        assertEquals(refused, load(account, first.next).last())
        assertEquals(emptyList(), index.checks)

        index.pages[null] = page(listOf(entry(21_150_000, 1, FROM)), signedBy = other)
        assertEquals(refused, load(account).last())
        assertEquals(emptyList(), index.checks)
    }

    @Test
    fun eachRefusalOfThePageIsSaidAndNothingIsChecked() = runTest {
        val reasons = listOf(
            AddressIndexRefusal.NotInThisBuild,
            AddressIndexRefusal.SettingOff,
            AddressIndexRefusal.TorRouting,
            AddressIndexRefusal.CheckpointAlarm("checkpoint table alarm: block 1024 differs"),
            AddressIndexRefusal.StaleAnchor,
            AddressIndexRefusal.NoEngine,
            AddressIndexRefusal.Rejected("an argument the engine does not take"),
            AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING),
            AddressIndexRefusal.NotNow(ReadUnavailable.UNPROVEN),
        )
        for (reason in reasons) {
            index.pageRefusal = reason

            assertEquals(AddressIndexLoad.Refused(reason), loader()(account, limit = 60).last())
        }
        assertEquals(emptyList(), index.checks)
    }

    @Test
    fun aNetworkNoAdapterServesIsRefusedNeverAnsweredFromAnother() = runTest {
        index.pages[null] = page(listOf(entry(21_150_000, 1, FROM)))
        verifyingAll()
        val onGnosis = AccountId(Network.GNOSIS, alice)

        val load = loader()(onGnosis).last()

        assertEquals(AddressIndexLoad.Refused(AddressIndexRefusal.NetworkNotServed(Network.GNOSIS)), load)
        assertEquals(listOf(Network.GNOSIS), index.queries.map { it.first })
    }

    @Test
    fun aLastingRefusalOfAnyChunksCheckRefusesTheWholeLoadAtOnce() = runTest {
        val entries = (0 until 60).map { entry(21_199_000L, 200 - it, FROM) }
        index.pages[null] = page(entries, next = "7:21198941:1")
        val reasons = listOf(
            AddressIndexRefusal.TorRouting,
            AddressIndexRefusal.CheckpointAlarm("checkpoint table alarm: block 1024 differs"),
            AddressIndexRefusal.Rejected("an entry the engine does not take"),
            AddressIndexRefusal.SettingOff,
        )
        for (reason in reasons) {
            index.checks.clear()
            // The first chunk verifies; the second is refused for good.
            index.check = { chunk, before ->
                if (before == 0) {
                    AddressIndexCheck.Verdicts(chunk.map(::verified))
                } else {
                    AddressIndexCheck.Refused(reason)
                }
            }

            assertEquals(AddressIndexLoad.Refused(reason), loader()(account, limit = 60).last())
            assertEquals(2, index.checks.size)
        }
    }

    @Test
    fun aPassingRefusalIsAskedAgainAndAtTheLastAttemptCountsItsChunkUnavailableKeepingThePage() = runTest {
        val sent = entry(21_150_000, 1, FROM)
        index.pages[null] = page(listOf(sent))
        val syncing = AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING)
        index.check = { entries, before ->
            if (before == 0) AddressIndexCheck.Refused(syncing) else AddressIndexCheck.Verdicts(entries.map(::verified))
        }

        val passed = assertIs<AddressIndexLoad.Loaded>(loader()(account).last())
        assertEquals(listOf(rowOf(sent)), passed.rows)

        val entries = (0 until 60).map { entry(21_199_000L, 200 - it, FROM) }
        index.pages[null] = page(entries, next = "7:21198941:1")
        index.checks.clear()
        // The first chunk verifies; the second is refused every time.
        index.check = { chunk, _ ->
            if (chunk.first() == entries.first()) {
                AddressIndexCheck.Verdicts(chunk.map(::verified))
            } else {
                AddressIndexCheck.Refused(syncing)
            }
        }

        val kept = assertIs<AddressIndexLoad.Loaded>(loader(attempts = 2)(account, limit = 60).last())

        assertEquals(listOf(50, 10, 10), index.checks.map { it.size })
        assertEquals(entries.take(50), kept.rows.map { it.entry })
        assertEquals(AddressIndexLeftOut(unavailable = 10), kept.leftOut)
        assertEquals(AddressIndexContinuation(account, AddressIndexCursor("7:21198941:1")), kept.next)
    }

    @Test
    fun aBlockWhoseCheckRunsToItsBoundIsNotAskedAgainAndTheOtherBlocksGoOn() = runTest {
        val slow = entry(21_150_000, 1, FROM)
        val quick = entry(21_140_000, 1, FROM)
        index.pages[null] = page(listOf(slow, quick), next = "7:21140000:1")
        index.checkTimeOf = { entries -> if (slow in entries) 150.seconds else 10.seconds }
        verifyingAll()

        val load = assertIs<AddressIndexLoad.Loaded>(loader(checkBudget = 100.seconds)(account).last())

        assertEquals(listOf(listOf(slow), listOf(quick)), index.checks, "the slow block is given up, not asked again")
        assertEquals(listOf(rowOf(quick)), load.rows)
        assertEquals(AddressIndexLeftOut(unavailable = 1), load.leftOut)
        // 100 s cut, then 10 s for the quick block: the page is bounded by its blocks times the bound.
        assertEquals(110_000, currentTime)
        assertEquals(AddressIndexContinuation(account, AddressIndexCursor("7:21140000:1")), load.next)
    }

    @Test
    fun aPageNoneOfWhoseEntriesCouldBeCheckedNowIsAPassingRefusalWithTheChecksReason() = runTest {
        index.pages[null] = page(listOf(entry(21_150_000, 1, FROM), entry(21_140_000, 1, TO)), next = "7:21140000:1")
        val syncing = AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING)
        index.check = { _, _ -> AddressIndexCheck.Refused(syncing) }

        val load = loader(attempts = 2)(account).last()

        assertEquals(AddressIndexLoad.Refused(syncing), load, "asking again reads this page again")
        assertEquals(4, index.checks.size)
    }

    @Test
    fun aBlockStillPastItsBoundAtTheLastAttemptCountsUnavailableKeepingTheRest() = runTest {
        val slow = entry(21_150_000, 1, FROM)
        val quick = entry(21_140_000, 1, FROM)
        index.pages[null] = page(listOf(slow, quick))
        index.checkTimeOf = { entries -> if (slow in entries) 150.seconds else 10.seconds }
        verifyingAll()

        val load = assertIs<AddressIndexLoad.Loaded>(loader(attempts = 2, checkBudget = 100.seconds)(account).last())

        assertEquals(listOf(rowOf(quick)), load.rows)
        assertEquals(AddressIndexLeftOut(unavailable = 1), load.leftOut)
    }

    @Test
    fun aPageNotReadWithinTheBudgetIsAPassingRefusal() = runTest {
        index.pages[null] = page(listOf(entry(21_199_000L, 1, FROM)))
        index.pageTime = 200.seconds
        verifyingAll()

        val load = assertIs<AddressIndexLoad.Refused>(loader(budget = 100.seconds)(account).last())

        assertEquals(AddressIndexRefusal.NotNow(ReadUnavailable.UNPROVEN), load.reason)
        assertEquals(100_000, currentTime)
        assertEquals(emptyList(), index.checks)
    }

    @Test
    fun aVerdictIsMatchedByBlockAndSlotAndOneWithOtherRolesIsNone() = runTest {
        val sent = entry(21_150_000, 1, FROM)
        index.pages[null] = page(listOf(sent))
        index.check = { _, before ->
            val roles = if (before == 0) setOf(FROM, TO) else setOf(FROM)
            AddressIndexCheck.Verdicts(listOf(verified(AddressIndexEntry(21_150_000, 1, roles))))
        }

        val load = assertIs<AddressIndexLoad.Loaded>(loader()(account).last())

        assertEquals(2, index.checks.size)
        assertEquals(listOf(rowOf(sent)), load.rows)
    }

    @Test
    fun aContinuationOfAnotherAccountIsTheCallersError() = runTest {
        index.pages[null] = page(listOf(entry(21_150_000, 1, FROM)), next = "7:21150000:1")
        verifyingAll()
        val load = loader()
        val next = assertIs<AddressIndexLoad.Loaded>(load(account).last()).next

        assertFailsWith<IllegalArgumentException> { load(AccountId(Network.ETHEREUM, bob), next).last() }
        assertFailsWith<IllegalArgumentException> { load(account, limit = 501).last() }
        assertFailsWith<IllegalArgumentException> { loader(attempts = 0) }
    }

    @Test
    fun leftOutCountsAddUpOverPagesAndTheContradictedStandApart() {
        val sum = AddressIndexLeftOut(1, 2, 3, 4) + AddressIndexLeftOut(10, 20, 30, 40)

        assertEquals(AddressIndexLeftOut(11, 22, 33, 44), sum)
        assertEquals(11 + 33 + 44, sum.notVerified)
        assertEquals(110, sum.total)
    }

    @Test
    fun aPageReadForWhatIsNewerChecksOnlyTheEntriesAboveTheKeptTopAndEndsThere() = runTest {
        val newer = entry(21_190_000, 2, FROM)
        val atTop = entry(21_180_000, 1, TO)
        val older = entry(21_170_000, 3, FROM)
        index.pages[null] = page(listOf(newer, atTop, older), next = "7:21170000:3")
        verifyingAll()

        val progress = loader()(account, newerThan = 21_180_000).toList()

        val read = assertIs<AddressIndexProgress.Read>(progress.first())
        assertEquals(listOf(newer), read.entries)
        assertEquals(null, read.next, "the page reaches the kept top: nothing newer lies below")
        assertEquals(listOf(listOf(newer)), index.checks, "only the new entry is checked")
        val load = assertIs<AddressIndexLoad.Loaded>(progress.last())
        assertEquals(listOf(rowOf(newer)), load.rows)
        assertEquals(AddressIndexLeftOut(), load.leftOut, "the entries passed over are not counted")
        assertEquals(null, load.next)
    }

    @Test
    fun aPageOfNewerEntriesThatDoesNotReachTheKeptTopGoesOn() = runTest {
        index.pages[null] = page(listOf(entry(21_190_000, 2, FROM)), next = "7:21190000:2")
        verifyingAll()

        val load = assertIs<AddressIndexLoad.Loaded>(loader()(account, newerThan = 21_100_000).last())

        assertEquals(AddressIndexCursor("7:21190000:2"), load.next?.cursor)
    }

    @Test
    fun aLoadNamesTheEntriesNoCheckAnswered() = runTest {
        val answered = entry(21_150_000, 4, FROM)
        val unread = entry(21_140_000, 2, TO)
        index.pages[null] = page(listOf(answered, unread))
        index.check = { entries, _ ->
            AddressIndexCheck.Verdicts(
                entries.map { if (it == answered) verified(it) else AddressIndexVerdict.Unavailable(it, "no peer") },
            )
        }

        val load = assertIs<AddressIndexLoad.Loaded>(loader(attempts = 1)(account).last())

        assertEquals(listOf(unread), load.unavailable)
        assertEquals(1, load.leftOut.unavailable)
    }

    @Test
    fun entriesCheckedAgainReadNoPageAndKeepTheListsCoverageAndContinuation() = runTest {
        val waiting = entry(21_150_000, 4, FROM)
        val stillWaiting = entry(21_140_000, 2, TO)
        index.pages[null] = page(listOf(entry(1, 1, FROM)), next = "7:1:1")
        verifyingAll()
        val next = assertIs<AddressIndexLoad.Loaded>(loader()(account).last()).next
        index.queries.clear()
        index.checks.clear()
        index.check = { entries, _ ->
            AddressIndexCheck.Verdicts(
                entries.map { if (it == waiting) verified(it) else AddressIndexVerdict.Unavailable(it, "no peer") },
            )
        }

        val progress = loader(attempts = 1).again(account, listOf(waiting, stillWaiting), coverage, next).toList()

        assertEquals(emptyList(), index.queries, "no page is read")
        val read = assertIs<AddressIndexProgress.Read>(progress.first())
        assertEquals(listOf(waiting, stillWaiting), read.entries)
        val load = assertIs<AddressIndexLoad.Loaded>(progress.last())
        assertEquals(listOf(rowOf(waiting)), load.rows)
        assertEquals(listOf(stillWaiting), load.unavailable)
        assertEquals(coverage, load.coverage)
        assertEquals(next, load.next)
    }

    @Test
    fun entriesTheListKnowsArePassedOverNeitherCheckedNorCounted() = runTest {
        val known = entry(21_190_000, 2, FROM)
        val traced = entry(21_185_000, 1, TRACE)
        val new = entry(21_180_000, 1, TO)
        index.pages[null] = page(listOf(known, traced, new))
        verifyingAll()

        val load = assertIs<AddressIndexLoad.Loaded>(
            loader()(account, known = setOf(known.block to known.slot, traced.block to traced.slot)).last(),
        )

        assertEquals(listOf(listOf(new)), index.checks)
        assertEquals(listOf(rowOf(new)), load.rows)
        assertEquals(AddressIndexLeftOut(), load.leftOut)
        assertEquals(21_180_000, load.reached, "the page reached its lowest entry, a known one or not")
    }

    @Test
    fun aRangeIsAskedOfTheIndex() = runTest {
        index.pages[null] = page(emptyList())

        loader()(account, range = 21_100_000L..21_100_999L).last()

        val query = index.queries.single().third
        assertEquals(21_100_000, query.fromBlock)
        assertEquals(21_100_999, query.toBlock)
    }

    @Test
    fun aVerdictAgainstABlockNotFinalIsNamedAndAContradictionOfItNotCounted() = runTest {
        val recent = entry(21_195_000, 2, FROM)
        val wrongRecent = entry(21_194_000, 1, TO)
        val wrongFinal = entry(21_150_000, 3, TO)
        index.pages[null] = page(listOf(recent, wrongRecent, wrongFinal))
        index.check = { entries, _ ->
            AddressIndexCheck.Verdicts(
                entries.map {
                    when (it) {
                        recent -> AddressIndexVerdict.Verified(rowOf(it).copy(final = false))
                        wrongRecent -> AddressIndexVerdict.Contradicted(it, "0x" + "dd".repeat(32), false, setOf(TO))
                        else -> AddressIndexVerdict.Contradicted(it, "0x" + "dd".repeat(32), true, setOf(TO))
                    }
                },
            )
        }

        val load = assertIs<AddressIndexLoad.Loaded>(loader()(account).last())

        assertEquals(listOf(recent, wrongRecent), load.notFinal)
        assertEquals(1, load.leftOut.contradicted, "only the contradiction against a finalized block")
        assertEquals(listOf(wrongFinal), load.out.contradicted)
        assertEquals(1, load.rows.size, "a row for now")
    }
}
