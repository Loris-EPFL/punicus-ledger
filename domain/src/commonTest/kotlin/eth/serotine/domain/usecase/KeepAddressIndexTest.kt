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
    private val keep = KeepAddressIndex(archive, wallet, importer)
    private val none = AddressIndexLeftOut()

    private fun row(block: Long, slot: Int, final: Boolean = true) = AddressIndexRow(
        entry = AddressIndexEntry(block, slot, setOf(FROM)),
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

    @Test
    fun onlyRowsOfFinalizedBlocksAreKeptAsRowsTheOthersAreCheckedAgain() = runTest {
        val final = row(21_150_000, 2)
        val notFinal = row(21_190_000, 1, final = false)
        val unread = AddressIndexEntry(21_140_000, 3, setOf(FROM))

        assertTrue(
            keep.keep(
                account,
                coverage,
                top = 21_200_000,
                rows = listOf(final, notFinal),
                recheck = listOf(unread),
                leftOut = AddressIndexLeftOut(unverified = 1, contradicted = 2, traceOnly = 3, unavailable = 4),
                next = continuation("7:21140000:3"),
            ),
        )

        val kept = assertNotNull(archive.kept[account])
        assertEquals(listOf(final), kept.rows)
        assertEquals(setOf(unread, notFinal.entry), kept.recheck.toSet())
        assertEquals(listOf(1, 2, 3), listOf(kept.unverified, kept.contradicted, kept.traceOnly))
        assertEquals(AddressIndexCursor("7:21140000:3"), kept.next)
        assertEquals(21_200_000, kept.top)
    }

    @Test
    fun aKeptListIsGivenBackWithItsContinuationAndCounts() = runTest {
        val next = continuation("7:21140000:3")
        val leftOut = AddressIndexLeftOut(contradicted = 1)
        keep.keep(account, coverage, 21_200_000, listOf(row(21_150_000, 2)), emptyList(), leftOut, next)

        val resume = assertNotNull(keep.restore(account))

        assertEquals(listOf(row(21_150_000, 2)), resume.kept.rows)
        assertEquals(next, resume.next)
        assertEquals(AddressIndexLeftOut(contradicted = 1), resume.leftOut)
    }

    @Test
    fun aListRestingOnAnotherKeyIsNeitherKeptNorGivenBack() = runTest {
        val otherKeys = coverage.copy(importer = other)
        assertFalse(keep.keep(account, otherKeys, 1, emptyList(), emptyList(), none, null))
        assertNull(archive.kept[account])

        KeepAddressIndex(archive, wallet, other).keep(account, otherKeys, 1, emptyList(), emptyList(), none, null)

        assertNull(keep.restore(account), "a list kept under the key pinned before starts again from the index")
    }

    @Test
    fun aForgottenListIsNoLongerGivenBack() = runTest {
        keep.keep(account, coverage, 1, emptyList(), emptyList(), none, null)

        assertTrue(keep.forget(account))

        assertNull(keep.restore(account))
    }

    @Test
    fun aListOfAnAccountNoLongerInTheWalletIsNotWrittenBack() = runTest {
        wallet.accounts.value = emptyList()

        assertFalse(keep.keep(account, coverage, 1, emptyList(), emptyList(), none, null))

        assertNull(archive.kept[account], "a page finished after the removal writes nothing back")
    }
}
