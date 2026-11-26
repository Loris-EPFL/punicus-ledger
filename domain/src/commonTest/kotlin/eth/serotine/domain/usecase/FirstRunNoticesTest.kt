package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.FirstRunNotice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [ObserveFirstRunNotice] and [DismissFirstRunNotice] over fake ports (owner's request, 2026-11-26). */
class FirstRunNoticesTest {

    private class MemoryNotice(dismissed: Boolean = false) : FirstRunNotice {
        val dismissed = MutableStateFlow(dismissed)

        override fun observe(): Flow<Boolean> = dismissed

        override suspend fun dismiss(): Boolean {
            dismissed.value = true
            return true
        }
    }

    /** An account list that reads as [list], once. */
    private class FixedAccounts(private val list: AccountList) : AccountRepository by ListAccountRepository() {
        override fun observeAccounts(): Flow<AccountList> = flowOf(list)
    }

    private val alice = Account(
        Network.ETHEREUM,
        Address.of("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"),
        AccountKind.EOA,
        "Alice",
        AccountCustody.Watched,
    )

    @Test
    fun aStartWithNoAccountAndNoDismissalIsAFirstStartDecidedOnce() = runTest {
        val notice = MemoryNotice()

        assertEquals(listOf(true), ObserveFirstRunNotice(notice, ListAccountRepository())().toList(), "one decision")
        assertFalse(notice.dismissed.value, "nothing recorded until OK")

        assertTrue(DismissFirstRunNotice(notice)())
        assertEquals(listOf(false), ObserveFirstRunNotice(notice, ListAccountRepository())().toList())
    }

    @Test
    fun anInstallThatHoldsOrHeldAccountsIsNoFirstStartAndRecordsItQuietly() = runTest {
        val lists = listOf(AccountList.Loaded(listOf(alice)), AccountList.Loaded(emptyList(), damagedListSetAside = true))
        for (list in lists) {
            val notice = MemoryNotice()

            assertEquals(listOf(false), ObserveFirstRunNotice(notice, FixedAccounts(list))().toList(), "$list")
            assertTrue(notice.dismissed.value, "recorded, so it is never shown on this install: $list")
        }
    }

    @Test
    fun anAccountListThatCannotBeReadShowsNothingAndRecordsNothing() = runTest {
        val notice = MemoryNotice()
        val unreadable = ListAccountRepository().apply { unreadable = true }

        assertEquals(listOf(false), ObserveFirstRunNotice(notice, unreadable)().toList())
        assertFalse(notice.dismissed.value)
    }

    @Test
    fun noneIsDismissedAlready() = runTest {
        assertEquals(listOf(false), ObserveFirstRunNotice(FirstRunNotice.None, ListAccountRepository())().toList())
    }
}
