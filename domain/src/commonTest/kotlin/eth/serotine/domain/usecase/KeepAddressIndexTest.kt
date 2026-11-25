package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCoverage
import eth.serotine.domain.model.AddressIndexCursor
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexRole.FROM
import eth.serotine.domain.model.AddressIndexRole.TRACE
import eth.serotine.domain.model.AddressIndexRow
import eth.serotine.domain.model.AddressIndexTransaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AddressIndexPage
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [KeepAddressIndex] over an archive in memory. */
class KeepAddressIndexTest {

    private val alice = Address.of("0x" + "11".repeat(20))
    private val importer = Address.of("0x" + "1e".repeat(20))
    private val other = Address.of("0x" + "2e".repeat(20))
    private val account = AccountId(Network.ETHEREUM, alice)
    private val coverage = AddressIndexCoverage(importer, 7, 21_000_000L..21_200_000L, null, null, emptyList())
    private val archive = MemoryAddressIndexArchive()
    private val wallet = ListAccountRepository(
        Account(Network.ETHEREUM, alice, AccountKind.EOA, "Alice", AccountCustody.Watched),
    )
    private val keep = KeepAddressIndex(archive, wallet, WalletChanges(), importer)
    private val none = AddressIndexLeftOutEntries()

    private fun entry(block: Long, slot: Int) = AddressIndexEntry(block, slot, setOf(FROM))

    private fun row(block: Long, slot: Int, final: Boolean = true) = AddressIndexRow(
        entry = entry(block, slot),
        blockHash = "0x" + "bb".repeat(32),
        final = final,
        confirmed = setOf(FROM),
        unchecked = emptySet(),
        transaction = AddressIndexTransaction("0x" + "cc".repeat(32), alice, null, Wei.of("1"), transfer = null),
    )

    /** A continuation of [account]'s history below [cursor], as only a page's load makes one. */
    private suspend fun continuation(cursor: String): AddressIndexContinuation {
        val index = FakeAddressIndexHistory()
        index.pages[null] = AddressIndexPage(coverage, emptyList(), AddressIndexCursor(cursor))
        val load = assertIs<AddressIndexLoad.Loaded>(LoadAddressIndexPage(index, importer)(account).last())
        return assertNotNull(load.next)
    }

    private suspend fun keepPlain(
        top: Long = 21_200_000,
        rows: List<AddressIndexRow> = emptyList(),
        recheck: List<AddressIndexEntry> = emptyList(),
        out: AddressIndexLeftOutEntries = none,
        notFinal: List<AddressIndexEntry> = emptyList(),
        unread: List<LongRange> = emptyList(),
        next: AddressIndexContinuation? = null,
        on: AddressIndexCoverage = coverage,
        with: KeepAddressIndex = keep,
    ) = with.keep(account, on, top, rows, recheck, out, notFinal, unread, next)

    @Test
    fun everyAnswerIsKeptByItsEntryAndGivenBackWithTheContinuation() = runTest {
        val next = continuation("7:21140000:3")
        val out = AddressIndexLeftOutEntries(
            unverified = listOf(entry(21_130_000, 1)),
            contradicted = listOf(entry(21_120_000, 2)),
            traceOnly = listOf(AddressIndexEntry(21_110_000, 3, setOf(TRACE))),
        )
        val unread = listOf(21_100_000L..21_100_999L)
        val rows = listOf(row(21_150_000, 2))
        val recheck = listOf(entry(21_140_000, 3))
        assertTrue(keepPlain(rows = rows, recheck = recheck, out = out, unread = unread, next = next))

        val resume = assertNotNull(keep.restore(account))

        assertEquals(listOf(row(21_150_000, 2)), resume.kept.rows)
        assertEquals(listOf(entry(21_140_000, 3)), resume.kept.recheck)
        assertEquals(out, resume.out)
        assertEquals(AddressIndexLeftOut(unverified = 1, contradicted = 1, traceOnly = 1), resume.leftOut)
        assertEquals(unread, resume.kept.unread)
        assertEquals(next, resume.next)
        assertEquals(
            setOf(21_150_000L to 2, 21_140_000L to 3, 21_130_000L to 1, 21_120_000L to 2, 21_110_000L to 3),
            resume.kept.known,
        )
    }

    @Test
    fun aVerdictAgainstABlockNotFinalKeepsTheTopBelowItSoThatTheIndexIsReadThereAgain() = runTest {
        val final = row(21_150_000, 2)
        val notFinal = row(21_190_000, 1, final = false)
        val contradictedNotFinal = entry(21_180_000, 4)

        assertTrue(keepPlain(rows = listOf(notFinal, final), notFinal = listOf(notFinal.entry, contradictedNotFinal)))

        val kept = assertNotNull(archive.kept[account])
        assertEquals(listOf(final), kept.rows, "only the row of a finalized block")
        assertEquals(21_179_999, kept.top, "below every verdict that may still be undone")
        assertEquals(emptyList(), kept.recheck, "read again from the index, not checked again as it was")
    }

    @Test
    fun anEntryIsKeptOnceARowBeforeLeftOutBeforeWaiting() = runTest {
        val e = entry(21_150_000, 2)

        val out = AddressIndexLeftOutEntries(unverified = listOf(e))
        keepPlain(rows = listOf(row(21_150_000, 2)), recheck = listOf(e), out = out)

        val kept = assertNotNull(archive.kept[account])
        assertEquals(1, kept.rows.size)
        assertEquals(emptyList(), kept.recheck)
        assertEquals(emptyList(), kept.unverified)
    }

    @Test
    fun aListRestingOnAnotherKeyIsNeitherKeptNorGivenBack() = runTest {
        val otherKeys = coverage.copy(importer = other)
        assertFalse(keepPlain(on = otherKeys))
        assertNull(archive.kept[account])

        keepPlain(on = otherKeys, with = KeepAddressIndex(archive, wallet, WalletChanges(), other))

        assertNull(keep.restore(account), "a list kept under the key pinned before starts again from the index")
    }

    @Test
    fun aListOfAnAccountNoLongerInTheWalletIsNotWrittenBack() = runTest {
        wallet.accounts.value = emptyList()

        assertFalse(keepPlain())

        assertNull(archive.kept[account], "a page finished after the removal writes nothing back")
    }

    @Test
    fun aForgottenListIsNoLongerGivenBack() = runTest {
        keepPlain()

        assertTrue(keep.forget(account))

        assertNull(keep.restore(account))
    }
}
