package eth.serotine.domain.model

import eth.serotine.domain.model.AddressIndexRole.BLOCK
import eth.serotine.domain.model.AddressIndexRole.FROM
import eth.serotine.domain.model.AddressIndexRole.TO
import eth.serotine.domain.model.AddressIndexRole.TRACE
import eth.serotine.domain.repository.AddressIndexPage
import eth.serotine.domain.repository.AddressIndexQuery
import eth.serotine.domain.repository.AddressIndexRefusal
import eth.serotine.domain.repository.ReadUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the addr1 history's value objects say, and what they refuse at construction. */
class AddressIndexTest {

    private val alice = Address.of("0x" + "11".repeat(20))
    private val importer = Address.of("0x" + "1e".repeat(20))
    private val hash = "0x" + "bb".repeat(32)

    private fun coverage(
        headGap: AddressIndexHeadGap? = null,
        tailGap: LongRange? = null,
        gaps: List<LongRange> = emptyList(),
    ) = AddressIndexCoverage(importer, 3, 1_000, 2_000, headGap, tailGap, gaps)

    @Test
    fun anEntryNamesItsTransactionBySlotAndKnowsWhenItRestsOnATraceAlone() {
        assertNull(AddressIndexEntry(10, 0, setOf(BLOCK)).transactionIndex)
        assertEquals(4, AddressIndexEntry(10, 5, setOf(FROM)).transactionIndex)
        assertTrue(AddressIndexEntry(10, 5, setOf(TRACE)).traceOnly)
        assertFalse(AddressIndexEntry(10, 5, setOf(TRACE, TO)).traceOnly)

        assertFailsWith<IllegalArgumentException> { AddressIndexEntry(-1, 0, setOf(BLOCK)) }
        assertFailsWith<IllegalArgumentException> { AddressIndexEntry(10, -1, setOf(FROM)) }
        assertFailsWith<IllegalArgumentException> { AddressIndexEntry(10, 1, emptySet()) }
    }

    @Test
    fun aCursorIsNeverBlank() {
        assertEquals("7:100:1", AddressIndexCursor("7:100:1").toString())
        assertFailsWith<IllegalArgumentException> { AddressIndexCursor(" ") }
    }

    @Test
    fun theGapsLieOutsideTheCoverageOrInsideItAsTheirKindSays() {
        coverage(AddressIndexHeadGap(2_001, null), 0L..999L, listOf(1_500L..1_600L))
        coverage(AddressIndexHeadGap(2_001, 2_050))

        assertFailsWith<IllegalArgumentException> { coverage(headGap = AddressIndexHeadGap(2_000, 2_050)) }
        assertFailsWith<IllegalArgumentException> { AddressIndexHeadGap(2_001, 2_000) }
        assertFailsWith<IllegalArgumentException> { coverage(tailGap = 900L..1_000L) }
        assertFailsWith<IllegalArgumentException> { coverage(gaps = listOf(1_900L..2_001L)) }
        assertFailsWith<IllegalArgumentException> { coverage(gaps = listOf(1_500L..1_400L)) }
        assertFailsWith<IllegalArgumentException> {
            AddressIndexCoverage(importer, 3, 2_000, 1_000, null, null, emptyList())
        }
    }

    @Test
    fun aRowIsProvenInOneRoleAtLeastAndCarriesATransactionOnlyForATransactionEntry() {
        val tx = AddressIndexTransaction("0x" + "cc".repeat(32), alice, null, Wei.of("0"), null)
        AddressIndexRow(AddressIndexEntry(10, 1, setOf(FROM, TRACE)), hash, true, setOf(FROM), setOf(TRACE), tx)
        AddressIndexRow(AddressIndexEntry(10, 0, setOf(BLOCK)), hash, false, setOf(BLOCK), emptySet(), null)

        val sent = AddressIndexEntry(10, 1, setOf(FROM))
        assertFailsWith<IllegalArgumentException> { AddressIndexRow(sent, hash, true, emptySet(), emptySet(), tx) }
        assertFailsWith<IllegalArgumentException> { AddressIndexRow(sent, hash, true, setOf(TO), emptySet(), tx) }
        assertFailsWith<IllegalArgumentException> { AddressIndexRow(sent, hash, true, setOf(FROM), emptySet(), null) }
        assertFailsWith<IllegalArgumentException> { AddressIndexRow(sent, "0xBB", true, setOf(FROM), emptySet(), tx) }
        assertFailsWith<IllegalArgumentException> {
            AddressIndexRow(AddressIndexEntry(10, 0, setOf(BLOCK)), hash, true, setOf(BLOCK), emptySet(), tx)
        }
        assertFailsWith<IllegalArgumentException> {
            AddressIndexTransaction("0x" + "CC".repeat(32), alice, null, Wei.of("0"), null)
        }
    }

    @Test
    fun aContradictionNamesTheEntrysOwnRoles() {
        val entry = AddressIndexEntry(10, 1, setOf(FROM))
        AddressIndexVerdict.Contradicted(entry, hash, true, setOf(FROM))

        assertFailsWith<IllegalArgumentException> { AddressIndexVerdict.Contradicted(entry, hash, true, emptySet()) }
        assertFailsWith<IllegalArgumentException> { AddressIndexVerdict.Contradicted(entry, hash, true, setOf(TO)) }
    }

    @Test
    fun aPageListsItsEntriesNewestFirstEachOnce() {
        val newer = AddressIndexEntry(20, 1, setOf(FROM))
        val sameBlockEarlier = AddressIndexEntry(20, 0, setOf(BLOCK))
        val older = AddressIndexEntry(10, 3, setOf(TO))
        AddressIndexPage(coverage(), listOf(newer, sameBlockEarlier, older), null)

        assertFailsWith<IllegalArgumentException> { AddressIndexPage(coverage(), listOf(older, newer), null) }
        assertFailsWith<IllegalArgumentException> {
            AddressIndexPage(coverage(), listOf(sameBlockEarlier, newer), null)
        }
        assertFailsWith<IllegalArgumentException> {
            AddressIndexPage(coverage(), listOf(newer, newer.copy(roles = setOf(TO))), null)
        }
    }

    @Test
    fun aQueryAsksForOneToFiveHundredEntriesOfARangeThatRunsForward() {
        AddressIndexQuery(limit = 1)
        AddressIndexQuery(limit = 500, fromBlock = 5, toBlock = 5)
        AddressIndexQuery(toBlock = 0)

        assertFailsWith<IllegalArgumentException> { AddressIndexQuery(limit = 0) }
        assertFailsWith<IllegalArgumentException> { AddressIndexQuery(limit = 501) }
        assertFailsWith<IllegalArgumentException> { AddressIndexQuery(fromBlock = -1) }
        assertFailsWith<IllegalArgumentException> { AddressIndexQuery(fromBlock = 6, toBlock = 5) }
    }

    @Test
    fun onlyAPassingRefusalIsRetryable() {
        assertTrue(AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING).retryable)
        assertFalse(AddressIndexRefusal.NoImporter.retryable)
        assertFalse(AddressIndexRefusal.NetworkNotServed(Network.GNOSIS).retryable)
        assertFailsWith<IllegalArgumentException> { AddressIndexRefusal.NotNow(ReadUnavailable.REFUSED) }
    }
}
