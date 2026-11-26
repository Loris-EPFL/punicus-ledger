package eth.serotine.data.account

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.PerAppAccount
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The accounts of this process, held in memory: an [AccountRepository] for tests and
 * previews, which nothing outlives. The hosts use [FileAccountRepository]. Safe to call
 * from any thread; can refuse a custody change, but never fails with a storage error.
 *
 * The port's one-entry-per-[AccountId] contract holds for [initial] too: a duplicate in it
 * keeps the first entry, as [add] does.
 */
class InMemoryAccountRepository(initial: List<Account> = emptyList()) : AccountRepository {

    private val accounts = MutableStateFlow(initial.distinctBy { it.id })

    override fun observeAccounts(): Flow<AccountList> = accounts.map { AccountList.Loaded(it) }

    override suspend fun add(account: Account): AccountAdd {
        var added = false
        accounts.update { current ->
            // Recomputed on every retry of update's compare-and-set, so it describes the
            // list that was actually written.
            added = current.none { it.id == account.id }
            if (added) current + account else current
        }
        return if (added) AccountAdd.Added else AccountAdd.AlreadyPresent
    }

    override suspend fun rename(id: AccountId, label: String): AccountChange =
        updateEntry(id) { it.copy(label = label, ensName = null) }

    override suspend fun setCustody(id: AccountId, custody: AccountCustody): AccountChange =
        updateEntry(id) {
            if (it.custody is AccountCustody.Ledger || custody is AccountCustody.Ledger) {
                null
            } else {
                it.copy(custody = custody)
            }
        }

    override suspend fun markPerApp(id: AccountId, mark: PerAppAccount): AccountChange =
        updateEntry(id) {
            if (it.custody is AccountCustody.Ledger) null else it.copy(custody = AccountCustody.InKeyStore, perApp = mark)
        }

    override suspend fun remove(id: AccountId): AccountChange =
        changeEntry(id) { current, index -> current.toMutableList().apply { removeAt(index) } }

    private fun updateEntry(id: AccountId, update: (Account) -> Account?): AccountChange =
        changeEntry(id) { current, index ->
            val updated = update(current[index]) ?: return@changeEntry null
            current.toMutableList().apply { set(index, updated) }
        }

    private fun changeEntry(id: AccountId, change: (List<Account>, Int) -> List<Account>?): AccountChange {
        var answer: AccountChange = AccountChange.NotFound
        accounts.update { current ->
            val index = current.indexOfFirst { it.id == id }
            val next = if (index < 0) null else change(current, index)
            answer = when {
                index < 0 -> AccountChange.NotFound
                next == null -> AccountChange.NotSaved
                else -> AccountChange.Changed
            }
            next ?: current
        }
        return answer
    }
}
