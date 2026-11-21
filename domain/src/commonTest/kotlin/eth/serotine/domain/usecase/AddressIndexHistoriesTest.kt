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
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
        coveredFrom = 21_000_000,
        coveredTo = 21_200_000,
        headGap = AddressIndexHeadGap(21_200_001, 21_200_100),
        tailGap = null,
        gaps = listOf(21_100_000L..21_100_999L),
    )
    private val index = FakeAddressIndexHistory()

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

        val load = assertIs<AddressIndexLoad.Loaded>(LoadAddressIndexPage(index, attempts = 1)(account))

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

        val load = assertIs<AddressIndexLoad.Loaded>(LoadAddressIndexPage(index)(account))

        assertEquals(listOf(listOf(mixed)), index.checks)
        assertEquals(listOf(rowOf(mixed)), load.rows)
        assertEquals(setOf(TRACE), load.rows.single().unchecked)
        assertEquals(AddressIndexLeftOut(traceOnly = 1), load.leftOut)
    }

    @Test
    fun aPageOfTraceOnlyEntriesAsksNoCheckAtAll() = runTest {
        index.pages[null] = page(listOf(entry(21_150_000, 2, TRACE), entry(21_140_000, 0, TRACE)))

        val load = assertIs<AddressIndexLoad.Loaded>(LoadAddressIndexPage(index)(account))

        assertEquals(emptyList(), index.checks)
        assertEquals(emptyList(), load.rows)
        assertEquals(AddressIndexLeftOut(traceOnly = 2), load.leftOut)
    }

    @Test
    fun theCheckRunsInChunksOfAtMostFiftyInPageOrder() = runTest {
        val entries = (0 until 120).map { entry(21_199_000L - it, 1, FROM) }
        index.pages[null] = page(entries)
        verifyingAll()

        val load = assertIs<AddressIndexLoad.Loaded>(LoadAddressIndexPage(index)(account, limit = 120))

        assertEquals(listOf(50, 50, 20), index.checks.map { it.size })
        assertEquals(entries, index.checks.flatten())
        assertEquals(entries, load.rows.map { it.entry })
        assertEquals(120, index.queries.single().third.limit)
    }

    @Test
    fun anUnavailableEntryIsAskedAgainAfterThePauseAndThenShown() = runTest {
        val first = entry(21_150_000, 1, FROM)
        val second = entry(21_140_000, 1, FROM)
        index.pages[null] = page(listOf(first, second))
        index.check = { entries, before ->
            AddressIndexCheck.Verdicts(
                entries.map {
                    if (it == second && before == 0) AddressIndexVerdict.Unavailable(it, "busy") else verified(it)
                },
            )
        }

        val load = assertIs<AddressIndexLoad.Loaded>(LoadAddressIndexPage(index, retryPause = 3.seconds)(account))

        assertEquals(listOf(listOf(first, second), listOf(second)), index.checks)
        assertEquals(listOf(rowOf(first), rowOf(second)), load.rows)
        assertEquals(AddressIndexLeftOut(), load.leftOut)
        assertEquals(3_000, currentTime)
    }

    @Test
    fun anEntryStillUnavailableAfterTheLastAttemptIsCountedApart() = runTest {
        val unread = entry(21_150_000, 1, FROM)
        val missing = entry(21_140_000, 1, TO)
        index.pages[null] = page(listOf(unread, missing))
        // No verdict at all for one entry counts as unavailable too.
        index.check = { entries, _ ->
            AddressIndexCheck.Verdicts(entries.filter { it == unread }.map { AddressIndexVerdict.Unavailable(it, "x") })
        }

        val load = assertIs<AddressIndexLoad.Loaded>(LoadAddressIndexPage(index, attempts = 3)(account))

        assertEquals(3, index.checks.size)
        assertEquals(emptyList(), load.rows)
        assertEquals(AddressIndexLeftOut(unavailable = 2), load.leftOut)
    }

    @Test
    fun aVerdictForAnotherEntryOrTwoVerdictsForOneNeverMakeARow() = runTest {
        val asked = entry(21_150_000, 1, FROM)
        val torn = entry(21_140_000, 1, FROM)
        index.pages[null] = page(listOf(asked, torn))
        index.check = { _, _ ->
            AddressIndexCheck.Verdicts(
                listOf(
                    verified(entry(21_150_000, 1, TO)),
                    verified(torn),
                    AddressIndexVerdict.Contradicted(torn, "0x" + "dd".repeat(32), true, setOf(FROM)),
                ),
            )
        }

        val load = assertIs<AddressIndexLoad.Loaded>(LoadAddressIndexPage(index, attempts = 1)(account))

        assertEquals(emptyList(), load.rows)
        assertEquals(AddressIndexLeftOut(unavailable = 2), load.leftOut)
    }

    @Test
    fun theNextPageIsReadBelowTheCursorUntilTheEndOfCoverage() = runTest {
        val newer = entry(21_150_000, 1, FROM)
        val older = entry(21_010_000, 5, TO)
        index.pages[null] = page(listOf(newer), next = "7:21150000:1")
        index.pages[AddressIndexCursor("7:21150000:1")] = page(listOf(older))
        verifyingAll()
        val load = LoadAddressIndexPage(index)

        val first = assertIs<AddressIndexLoad.Loaded>(load(account))
        val next = assertIs<AddressIndexContinuation>(first.next)
        val second = assertIs<AddressIndexLoad.Loaded>(load(account, after = next))

        assertEquals(false, first.endOfCoverage)
        assertEquals(AddressIndexContinuation(account, importer, AddressIndexCursor("7:21150000:1")), next)
        assertEquals(
            listOf(AddressIndexQuery(), AddressIndexQuery(cursor = AddressIndexCursor("7:21150000:1"))),
            index.queries.map { it.third },
        )
        assertEquals(listOf(rowOf(newer)), first.rows)
        assertEquals(listOf(rowOf(older)), second.rows)
        assertTrue(second.endOfCoverage)
    }

    @Test
    fun aLaterPageSignedByAnotherImporterEndsTheList() = runTest {
        val other = Address.of("0x" + "0e".repeat(20))
        index.pages[null] = page(listOf(entry(21_150_000, 1, FROM)), next = "7:21150000:1")
        index.pages[AddressIndexCursor("7:21150000:1")] = page(listOf(entry(21_010_000, 5, TO)), signedBy = other)
        verifyingAll()
        val load = LoadAddressIndexPage(index)
        val first = assertIs<AddressIndexLoad.Loaded>(load(account))
        index.checks.clear()

        assertEquals(AddressIndexLoad.ImporterChanged(trusted = importer, named = other), load(account, first.next))
        assertEquals(emptyList(), index.checks)
    }

    @Test
    fun eachRefusalOfThePageIsSaidAndNothingIsChecked() = runTest {
        val reasons = listOf(
            AddressIndexRefusal.NotInThisBuild,
            AddressIndexRefusal.NoImporter,
            AddressIndexRefusal.TorRouting,
            AddressIndexRefusal.Rejected("a cursor of a newer head"),
            AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING),
            AddressIndexRefusal.NotNow(ReadUnavailable.UNPROVEN),
        )
        for (reason in reasons) {
            index.pageRefusal = reason

            assertEquals(AddressIndexLoad.Refused(reason), LoadAddressIndexPage(index)(account))
        }
        assertEquals(emptyList(), index.checks)
    }

    @Test
    fun aNetworkNoAdapterServesIsRefusedNeverAnsweredFromAnother() = runTest {
        index.pages[null] = page(listOf(entry(21_150_000, 1, FROM)))
        verifyingAll()
        val onGnosis = AccountId(Network.GNOSIS, alice)

        val load = LoadAddressIndexPage(index)(onGnosis)

        assertEquals(AddressIndexLoad.Refused(AddressIndexRefusal.NetworkNotServed(Network.GNOSIS)), load)
        assertEquals(listOf(Network.GNOSIS), index.queries.map { it.first })
    }

    @Test
    fun aLastingRefusalOfTheCheckRefusesTheLoadAtOnce() = runTest {
        index.pages[null] = page(listOf(entry(21_150_000, 1, FROM)))
        index.check = { _, _ -> AddressIndexCheck.Refused(AddressIndexRefusal.TorRouting) }

        val load = LoadAddressIndexPage(index)(account)

        assertEquals(AddressIndexLoad.Refused(AddressIndexRefusal.TorRouting), load)
        assertEquals(1, index.checks.size)
    }

    @Test
    fun aPassingRefusalOfTheCheckIsAskedAgainAndRefusesTheLoadOnlyAtTheLastAttempt() = runTest {
        val sent = entry(21_150_000, 1, FROM)
        index.pages[null] = page(listOf(sent))
        val syncing = AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING)
        index.check = { entries, before ->
            if (before == 0) AddressIndexCheck.Refused(syncing) else AddressIndexCheck.Verdicts(entries.map(::verified))
        }

        val passed = assertIs<AddressIndexLoad.Loaded>(LoadAddressIndexPage(index)(account))
        assertEquals(listOf(rowOf(sent)), passed.rows)

        index.checks.clear()
        index.check = { _, _ -> AddressIndexCheck.Refused(syncing) }
        assertEquals(AddressIndexLoad.Refused(syncing), LoadAddressIndexPage(index, attempts = 2)(account))
        assertEquals(2, index.checks.size)
    }

    @Test
    fun aContinuationOfAnotherAccountIsTheCallersError() = runTest {
        index.pages[null] = page(listOf(entry(21_150_000, 1, FROM)), next = "7:21150000:1")
        verifyingAll()
        val load = LoadAddressIndexPage(index)
        val next = assertIs<AddressIndexLoad.Loaded>(load(account)).next

        assertFailsWith<IllegalArgumentException> { load(AccountId(Network.ETHEREUM, bob), next) }
        assertFailsWith<IllegalArgumentException> { load(account, limit = 501) }
        assertFailsWith<IllegalArgumentException> { LoadAddressIndexPage(index, attempts = 0) }
    }

    @Test
    fun leftOutCountsAddUpOverPages() {
        val sum = AddressIndexLeftOut(1, 2, 3, 4) + AddressIndexLeftOut(10, 20, 30, 40)

        assertEquals(AddressIndexLeftOut(11, 22, 33, 44), sum)
        assertEquals(110, sum.total)
    }
}
