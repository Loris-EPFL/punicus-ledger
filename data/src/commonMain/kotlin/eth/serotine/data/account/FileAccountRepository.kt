package eth.serotine.data.account

import eth.serotine.data.files.NewerContent
import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppHost
import eth.serotine.domain.model.AppHostResult
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PerAppAccount
import eth.serotine.domain.model.PerAppPath
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [AccountRepository] kept in the file at [path], so the accounts survive a restart:
 * addresses, kinds, labels and how each is held, never key material. [path] is a plain
 * string, so no file-system library type leaves this module.
 *
 * The file is a [VersionedJsonFile], which says how it is written and how a file that cannot
 * be used is handled, never destroying or hiding the stored list: a newer format or one that
 * cannot be read is [AccountList.Unreadable], and nothing can be added until it can be read;
 * a damaged one is set aside, and the empty list that follows says so
 * ([AccountList.Loaded.damagedListSetAside]).
 *
 * Format 2 records each account's custody. A format-1 file, written before watched accounts
 * existed, held only accounts added from a recovery phrase, so its entries read as
 * [AccountCustody.InKeyStore]; the next change writes the file as format 2. Format 3 records
 * the ENS name an account's name follows ([Account.ensName]); an entry without one, as every
 * entry of an older file, follows none. Format 4 records each account's network by its chain
 * id ([Account.network]); every entry of an older file was written when Punicus worked on
 * Sepolia alone, so it reads as a Sepolia account (docs/networks.md, *Files*). The next change
 * writes the file as the current format. Format 5 records what makes an account a per-app address
 * ([Account.perApp]: its site, path and phrase; issue #252); an entry without it, as every entry of an older file,
 * is an ordinary account. Format 6 records Ledger custody and its public path. Each write uses
 * the oldest of formats 4–6 that can represent all the accounts.
 * An entry whose chain id this version does not know was
 * written by a later version that works on more networks: the list is then
 * [AccountList.Reason.NEWER_FORMAT], left untouched, and the user is asked to update.
 */
class FileAccountRepository(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AccountRepository {

    private val file = VersionedJsonFile<List<Account>>(
        path,
        dispatcher,
        what = "account list",
        formatVersion = FORMAT_LEDGER,
        empty = emptyList(),
        decode = { _, text ->
            val stored = STRICT.decodeFromString(StoredAccounts.serializer(), text)
            stored.accounts.map { it.toAccount(stored.version) }.distinctBy { it.id }
        },
        encode = { accounts ->
            val version = when {
                accounts.any { it.custody is AccountCustody.Ledger } -> FORMAT_LEDGER
                accounts.any { it.perApp != null } -> FORMAT_PER_APP
                else -> FORMAT_BEFORE_PER_APP
            }
            val stored = StoredAccounts(version, accounts.map(::StoredAccount))
            STRICT.encodeToString(StoredAccounts.serializer(), stored)
        },
    )

    override fun observeAccounts(): Flow<AccountList> = file.observe().map { read ->
        when (read) {
            is VersionedJsonFile.Read.Loaded -> AccountList.Loaded(read.value, read.damagedSetAside)
            is VersionedJsonFile.Read.Unreadable -> AccountList.Unreadable(
                if (read.newerFormat) AccountList.Reason.NEWER_FORMAT else AccountList.Reason.READ_FAILED,
            )
        }
    }

    override suspend fun add(account: Account): AccountAdd = file.change(AccountAdd.NotSaved) { accounts ->
        if (accounts.any { it.id == account.id }) {
            Decision(AccountAdd.AlreadyPresent)
        } else {
            Decision(AccountAdd.Added, accounts + account)
        }
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

    /** Replaces the entry [id] in place; NotFound when absent, NotSaved when [update] refuses. */
    private suspend fun updateEntry(id: AccountId, update: (Account) -> Account?): AccountChange =
        file.change(AccountChange.NotSaved) { accounts ->
            val index = accounts.indexOfFirst { it.id == id }
            if (index < 0) {
                Decision(AccountChange.NotFound)
            } else {
                val updated = update(accounts[index]) ?: return@change Decision(AccountChange.NotSaved)
                Decision(AccountChange.Changed, accounts.toMutableList().apply { set(index, updated) })
            }
        }

    override suspend fun remove(id: AccountId): AccountChange = file.change(AccountChange.NotSaved) { accounts ->
        if (accounts.none { it.id == id }) {
            Decision(AccountChange.NotFound)
        } else {
            Decision(AccountChange.Changed, accounts.filterNot { it.id == id })
        }
    }

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredAccounts(val version: Int, val accounts: List<StoredAccount>)

    @Serializable
    private class StoredAccount(
        val address: String,
        val kind: String,
        val label: String,
        /** Absent in format 1, whose accounts all came from a recovery phrase. */
        val custody: String? = null,
        /** [Account.ensName]; absent when the name follows none, and before format 3. */
        val ens: String? = null,
        /** [Account.network]'s chain id; absent before format 4, whose accounts are all Sepolia's. */
        val chain: Long? = null,
        /** [Account.perApp]; absent for an ordinary account, and before format 5. */
        val perApp: StoredPerApp? = null,
        /** Public derivation path, present only for Ledger custody in format 6. */
        val ledgerPath: String? = null,
    ) {
        constructor(account: Account) : this(
            account.address.hex,
            account.kind.name,
            account.label,
            when (account.custody) {
                AccountCustody.Watched -> WATCHED
                AccountCustody.InKeyStore -> IN_KEY_STORE
                is AccountCustody.Ledger -> LEDGER
            },
            account.ensName,
            account.network.chainId,
            account.perApp?.let(::StoredPerApp),
            (account.custody as? AccountCustody.Ledger)?.path?.value,
        )

        /**
         * Throws on anything [Account] would not accept, in a file of [version]: the whole file is
         * then damaged; and [NewerContent] for an entry on a network this version does not know.
         */
        fun toAccount(version: Int): Account = Account(
            when {
                version < 4 && chain == null -> Network.SEPOLIA
                chain == null -> error("an account without a network")
                else -> Network.ofChainId(chain) ?: throw NewerContent("an account on chain $chain")
            },
            Address.of(address),
            AccountKind.valueOf(kind),
            label,
            toCustody(version),
            ens,
            perApp?.toMark(),
        )

        private fun toCustody(version: Int): AccountCustody {
            require(ledgerPath == null || custody == LEDGER) { "a Ledger path without Ledger custody" }
            return when {
                version == 1 && custody == null -> AccountCustody.InKeyStore
                custody == WATCHED -> AccountCustody.Watched
                custody == IN_KEY_STORE -> AccountCustody.InKeyStore
                version >= FORMAT_LEDGER && custody == LEDGER -> AccountCustody.Ledger(
                    ledgerPath?.let { LedgerPath.parse(it) } ?: error("a Ledger account without a valid path"),
                )
                else -> error("an account without a known custody")
            }
        }
    }

    /**
     * A per-app address's mark: its [site], its path `m/44'/60'/[a]'/[b]'/[c]'` and its phrase, named by the address
     * of the phrase's first account ([phraseAccount], a public value).
     */
    @Serializable
    private class StoredPerApp(
        val site: String,
        val a: Int,
        val b: Int,
        val c: Int,
        @SerialName("phrase") val phraseAccount: String,
    ) {
        constructor(mark: PerAppAccount) :
            this(mark.site.name, mark.path.account, mark.path.change, mark.path.index, mark.phrase.hex)

        /** Throws on anything [PerAppAccount] would not accept: the whole file is then damaged. */
        fun toMark(): PerAppAccount = PerAppAccount(
            (AppHost.parse(site) as? AppHostResult.Host)?.host ?: error("a per-app address without a site"),
            PerAppPath(a, b, c),
            Address.of(phraseAccount),
        )
    }

    private companion object {
        const val FORMAT_LEDGER = 6
        const val FORMAT_PER_APP = 5

        /** The format written while no entry needs format 5's per-app mark. */
        const val FORMAT_BEFORE_PER_APP = 4
        const val WATCHED = "watched"
        const val IN_KEY_STORE = "key-store"
        const val LEDGER = "ledger"
        val STRICT = Json
    }
}
