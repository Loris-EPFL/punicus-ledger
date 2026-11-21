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
    ) = AddressIndexCoverage(importer, 3, 1_000L..2_000L, headGap, tailGap, gaps)

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
            AddressIndexCoverage(importer, 3, 2_000L..1_000L, null, null, emptyList())
        }
    }

    @Test
    fun aRangeTheIndexDoesNotReachIsAllGapNeverAnInventedCoverage() {
        // An index that covers nothing yet: the range asked is gap above and below, and no inner gap can be named.
        AddressIndexCoverage(importer, 0, null, AddressIndexHeadGap(500, null), 0L..499L, emptyList())
        AddressIndexCoverage(importer, 0, null, AddressIndexHeadGap(0, 900), null, emptyList())
        // Newest blocks asked inside the head gap: the coverage below is the index's, and the gap spans the range.
        coverage(headGap = AddressIndexHeadGap(2_500, 2_600))
        // Blocks asked below where coverage starts: all tail gap.
        coverage(tailGap = 100L..200L)

        assertFailsWith<IllegalArgumentException> {
            AddressIndexCoverage(importer, 0, null, null, null, listOf(10L..20L))
        }
        assertFailsWith<IllegalArgumentException> {
            AddressIndexCoverage(importer, 0, null, AddressIndexHeadGap(400, null), 0L..499L, emptyList())
        }
    }

    @Test
    fun aRowIsProvenInOneRoleAtLeastAndCarriesATransactionOnlyForATransactionEntry() {
        val tx = AddressIndexTransaction("0x" + "cc".repeat(32), alice, null, Wei.of("0"), null)
        AddressIndexRow(AddressIndexEntry(10, 1, setOf(FROM, TRACE)), hash, true, setOf(FROM), setOf(TRACE), tx)
        val both = AddressIndexEntry(10, 1, setOf(FROM, TO))

        // No role but the trace rests on the importer's word, and every role of the entry is accounted for.
        assertFailsWith<IllegalArgumentException> { AddressIndexRow(both, hash, true, setOf(FROM), setOf(TO), tx) }
        assertFailsWith<IllegalArgumentException> { AddressIndexRow(both, hash, true, setOf(FROM), emptySet(), tx) }
        assertFailsWith<IllegalArgumentException> {
            AddressIndexRow(AddressIndexEntry(10, 1, setOf(FROM)), hash, true, setOf(FROM), setOf(TRACE), tx)
        }
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
        val token = Address.of("0x" + "7c".repeat(20))
        val tokens = TokenTransfer.Fungible(token, alice, token, UInt256.of(1))
        AddressIndexTransaction(tx.hash, alice, null, Wei.of("0"), tokens)
        val eth = TokenTransfer.Fungible(TokenTransfers.ETH, alice, token, UInt256.of(1))
        assertFailsWith<IllegalArgumentException> { AddressIndexTransaction(tx.hash, alice, token, Wei.of("1"), eth) }
    }

    @Test
    fun aContradictionNamesTheEntrysOwnRoles() {
        val entry = AddressIndexEntry(10, 1, setOf(FROM))
        AddressIndexVerdict.Contradicted(entry, hash, true, setOf(FROM))

        assertFailsWith<IllegalArgumentException> { AddressIndexVerdict.Contradicted(entry, "0xbb", true, setOf(FROM)) }
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
    fun onlyAPassingRefusalIsRetryableAndOnlySyncingUnprovenOrOffPass() {
        assertTrue(AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING).retryable)
        assertTrue(AddressIndexRefusal.NotNow(ReadUnavailable.UNPROVEN).retryable)
        assertTrue(AddressIndexRefusal.NotNow(ReadUnavailable.NETWORK_OFF).retryable)
        assertFalse(AddressIndexRefusal.SettingOff.retryable)
        assertFalse(AddressIndexRefusal.StaleAnchor.retryable)
        assertFalse(AddressIndexRefusal.NoEngine.retryable)
        assertFalse(AddressIndexRefusal.CheckpointAlarm("checkpoint table alarm: x").retryable)
        assertFalse(AddressIndexRefusal.WrongImporter(importer, alice).retryable)
        assertFalse(AddressIndexRefusal.NetworkNotServed(Network.GNOSIS).retryable)
        for (lasting in listOf(ReadUnavailable.REFUSED, ReadUnavailable.STALE_ANCHOR, ReadUnavailable.NO_ENGINE)) {
            assertFailsWith<IllegalArgumentException> { AddressIndexRefusal.NotNow(lasting) }
        }
    }
}
