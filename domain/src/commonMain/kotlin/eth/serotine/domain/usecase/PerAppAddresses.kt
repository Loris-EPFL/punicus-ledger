package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppHost
import eth.serotine.domain.model.AppHostResult
import eth.serotine.domain.model.AppIsolation
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PerAppAccount
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.DappProposal
import eth.serotine.domain.repository.DappSiteMemory
import eth.serotine.domain.repository.MemoryStep
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.PerAppDerivation
import eth.serotine.domain.repository.PerAppKeys
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * A recovery phrase held here that a per-app address can be made from (docs/walletconnect.md, *Per-app addresses*,
 * *Which phrase*): [from], the first account of it in the wallet's list whose key is held here, which names it on
 * screen D3 and whose envelope is opened to derive; [phrase], the address of the phrase's first account, or null for
 * an account whose envelope names no phrase (sealed before part 2 of #252), which then stands for a phrase of its
 * own.
 */
data class PhraseSource(val phrase: Address?, val from: Account)

/**
 * What screen D3 needs to offer a dApp a per-app address (issue #252): the [host] the dApp states, or why it has
 * none; its [site]; the phrases to make it from ([sources], in list order, the first the default); the per-app
 * accounts already made for the site ([made], on any network); the accounts last approved for the site
 * ([remembered]); the networks that are on ([enabled]); and [collision], another site of a per-app address held here
 * whose path is this site's, which refuses the new address.
 */
data class PerAppChoice(
    val host: AppHostResult,
    val site: AppHost?,
    val sources: List<PhraseSource>,
    val made: List<Account>,
    val remembered: List<AccountId>,
    val enabled: Set<Network>,
    val collision: AppHost?,
)

/** The phrases of the wallet's accounts held here, each named by its first account in [listed] ([PhraseSource]). */
fun phraseSources(listed: List<Account>, phrases: Map<Address, Address>): List<PhraseSource> {
    // An older envelope's further account, whose phrase is learnt only when it is opened, stands for one of its own:
    // it may be one listed already, which then shows twice, and either gives the same addresses.
    val held = listed.filter { it.kind == AccountKind.EOA && it.custody == AccountCustody.InKeyStore }
    val seen = HashSet<Address>()
    return held.mapNotNull { account ->
        // A per-app account records its phrase itself; an older envelope's further account names none.
        val phrase = phrases[account.address] ?: account.perApp?.phrase
        when {
            phrase == null -> if (seen.add(account.address)) PhraseSource(null, account) else null
            seen.add(phrase) -> PhraseSource(phrase, account)
            else -> null
        }
    }
}

/**
 * The networks a per-app address made for [proposal] goes on, beside the accounts [chosen]: those it asks for that
 * are [enabled], of one kind of funds, and where the site has no per-app account yet ([made]). The kind is that of
 * the accounts chosen; with none, that of the networks it requires; with neither, real funds where it asks for a
 * network of them that is on, else testnet. Empty when none is left, so nothing is made.
 */
fun perAppNetworks(
    proposal: DappProposal,
    enabled: Set<Network>,
    chosen: Collection<AccountId>,
    made: Collection<Account>,
): List<Network> {
    val open = Network.entries.filter { it in proposal.askedNetworks && it in enabled }
    val testnet = chosen.map { it.network.testnet }.distinct().singleOrNull()
        ?: proposal.requiredNetworks.map { it.testnet }.distinct().singleOrNull()
        ?: open.none { !it.testnet }
    val there = made.map { it.network }.toSet()
    return open.filter { it.testnet == testnet && it !in there }
}

/**
 * The per-app choice for the dApp stating [url] (screen D3), re-emitted as the wallet and the memory change. The
 * envelopes are read for their phrases once per change of the account list, not on every emission.
 */
class ObservePerAppChoice(
    private val accounts: AccountRepository,
    private val keys: PerAppKeys,
    private val sites: DappSiteMemory,
    private val networks: NetworkSettings,
) {
    operator fun invoke(url: String): Flow<PerAppChoice> {
        val host = AppHost.ofUrl(url)
        val site = (host as? AppHostResult.Host)?.host?.site
        val listedWithPhrases = accounts.observeAccounts()
            .map { list -> (list as? AccountList.Loaded)?.accounts.orEmpty() }
            .distinctUntilChanged()
            .map { listed -> listed to keys.phrases() }
        return combine(listedWithPhrases, sites.observe(), networks.observe()) { (listed, phrases), memory, enabled ->
            val perApp = listed.mapNotNull { it.perApp }
            PerAppChoice(
                host = host,
                site = site,
                sources = phraseSources(listed, phrases),
                made = if (site == null) emptyList() else listed.filter { it.perApp?.site == site },
                remembered = site?.let { memory[it] }.orEmpty(),
                enabled = enabled,
                collision = site?.let { AppIsolation.collision(it, perApp.map { mark -> mark.site }) },
            )
        }.distinctUntilChanged()
    }
}

/**
 * Makes the per-app address of [host]'s site (issue #252; docs/walletconnect.md, *Per-app addresses*): derived from
 * the phrase [from]'s envelope holds, opened with the wallet password ([PerAppKeys.derivePerApp]), and added on each
 * of the networks given as an [AccountKind.EOA] held in the key store, named after its site and marked as a per-app
 * address ([PerAppAccount]); an entry of that address already listed and not marked so (watched, say) is replaced by
 * the marked one, keeping its name. A site whose path is that of another site's per-app address in the account list,
 * of any phrase, is refused before anything is derived ([AppIsolation.collision]). An envelope left in the key store
 * without its account cannot be weighed, since its site is not stored: one of the same path is taken for the same
 * site, as making it again after a removal needs.
 *
 * The key store first, then the account list, as one step a cancelled caller does not cut in half, under the app's
 * [WalletChanges], as [DeriveAccount] does. The password goes to the key store alone.
 */
class MakePerAppAddress(
    private val keys: PerAppKeys,
    private val accounts: AccountRepository,
    /** The app's one [WalletChanges]. */
    private val changes: WalletChanges,
) {
    suspend operator fun invoke(
        from: AccountId,
        host: AppHost,
        networks: List<Network>,
        password: WalletPassword,
    ): PerAppMaking = withContext(NonCancellable) { changes.serialized { make(from, host, networks, password) } }

    private suspend fun make(
        from: AccountId,
        host: AppHost,
        networks: List<Network>,
        password: WalletPassword,
    ): PerAppMaking {
        if (networks.isEmpty()) return PerAppMaking.NoNetwork
        val listed = when (val list = accounts.observeAccounts().first()) {
            is AccountList.Unreadable -> return PerAppMaking.ListUnreadable
            is AccountList.Loaded -> list.accounts
        }
        val source = listed.firstOrNull { it.id == from } ?: return PerAppMaking.NoKey
        if (source.kind != AccountKind.EOA || source.custody != AccountCustody.InKeyStore) return PerAppMaking.NoKey
        val site = host.site
        AppIsolation.collision(site, listed.mapNotNull { it.perApp?.site })?.let { return PerAppMaking.Collision(it) }
        val stored = when (val derived = keys.derivePerApp(from.address, host, password)) {
            is PerAppDerivation.Stored -> derived
            PerAppDerivation.NoKey -> return PerAppMaking.NoKey
            PerAppDerivation.WrongPassword -> return PerAppMaking.WrongPassword
            PerAppDerivation.Unavailable -> return PerAppMaking.Unavailable
            PerAppDerivation.NotSaved -> return PerAppMaking.KeyNotSaved
            is PerAppDerivation.NotEnoughMemory -> return PerAppMaking.NotEnoughMemory(derived.step)
        }
        val mark = PerAppAccount(site, stored.path, stored.phrase)
        val made = networks.distinct().map { network ->
            val account = Account(network, stored.address, AccountKind.EOA, site.name, AccountCustody.InKeyStore,
                perApp = mark)
            if (!addMarked(account)) return PerAppMaking.ListNotSaved
            account.id
        }
        return PerAppMaking.Made(stored.address, made, stored.steppedDown)
    }
}

/**
 * [account] in the list, marked: added, or kept when it is there marked already; an entry of its address there not
 * marked so (the user's own per-app address watched before, say) is replaced by it, under the entry's name. False when
 * the list could not be saved.
 */
private suspend fun AccountRepository.addMarked(account: Account): Boolean {
    repeat(MARK_ATTEMPTS) {
        when (add(account)) {
            AccountAdd.Added -> return true
            AccountAdd.NotSaved -> return false
            AccountAdd.AlreadyPresent -> {
                val existing = (observeAccounts().first() as? AccountList.Loaded)?.accounts
                    ?.firstOrNull { it.id == account.id }
                    ?: return@repeat // removed meanwhile: add again
                if (existing.perApp == account.perApp && existing.custody == AccountCustody.InKeyStore) return true
                when (remove(account.id)) {
                    AccountChange.Changed, AccountChange.NotFound -> Unit
                    AccountChange.NotSaved -> return false
                }
                val named = account.copy(label = existing.label, ensName = existing.ensName)
                if (add(named) == AccountAdd.NotSaved) return false
                return true
            }
        }
    }
    return false
}

/** How often [addMarked] tries again when another writer changes the entry meanwhile. */
private const val MARK_ATTEMPTS = 3

/** The outcome of [MakePerAppAddress]. */
sealed interface PerAppMaking {

    /**
     * [address] is in the wallet as each of [accounts], held in the key store. [steppedDown] when its envelope was
     * sealed at a lighter password cost than this device's own, which the user is told.
     */
    data class Made(val address: Address, val accounts: List<AccountId>, val steppedDown: Boolean = false) :
        PerAppMaking

    /** No network to make it on was given; nothing changed. */
    data object NoNetwork : PerAppMaking

    /** The account to make it from is not in the wallet, or its key is not held here; nothing changed. */
    data object NoKey : PerAppMaking

    /** That is not the wallet password; nothing changed. */
    data object WrongPassword : PerAppMaking

    /** The key store cannot open the phrase's envelope on this device; nothing changed. */
    data object Unavailable : PerAppMaking

    /** The new key could not be stored; nothing changed. */
    data object KeyNotSaved : PerAppMaking

    /** Not enough free memory at [step]; nothing changed. */
    data class NotEnoughMemory(val step: MemoryStep) : PerAppMaking

    /** [other], a site with a per-app address here, has this site's path: refused, nothing changed. */
    data class Collision(val other: AppHost) : PerAppMaking

    /** The account list cannot be read; nothing changed. */
    data object ListUnreadable : PerAppMaking

    /**
     * The key is stored, but the account list could not be saved, so its account is not in it (or not on every
     * network). Making it again adds it: the same site gives the same address.
     */
    data object ListNotSaved : PerAppMaking
}
