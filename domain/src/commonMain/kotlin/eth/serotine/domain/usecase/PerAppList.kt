package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppHost
import eth.serotine.domain.model.AppHostResult
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PerAppPath
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.PerAppKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * One per-app address held here, as screen D9 lists it (issue #252; docs/walletconnect.md, *Every per-app address is
 * listed*): its [address], the [site] it was made for, its [path] below the seed and the [phrase] it comes from (the
 * address of that phrase's first account), with its [accounts] in the wallet, one per network, in list order. Public
 * values only.
 */
data class PerAppEntry(
    val address: Address,
    val site: AppHost,
    val path: PerAppPath,
    val phrase: Address,
    val accounts: List<Account>,
)

/**
 * What screen D9 and the Per-app tab show: every per-app address held here ([entries], in the order of their first
 * account in the list), the phrases one can be restored from ([sources], [phraseSources]), the networks that are on
 * ([enabled]), which a restored address goes on, and [knownBy]: for each phrase whose name is known, the address of
 * the account it is known by (docs/wallet-ux.md, 4), the one that has its Per-app tab.
 */
data class PerAppOverview(
    val entries: List<PerAppEntry>,
    val sources: List<PhraseSource>,
    val enabled: Set<Network>,
    val knownBy: Map<Address, Address> = emptyMap(),
)

/** The per-app addresses held here and the phrases to restore one from (screen D9), re-emitted as either changes. */
class ObservePerAppAddresses(
    private val accounts: AccountRepository,
    private val keys: PerAppKeys,
    private val networks: NetworkSettings,
) {
    operator fun invoke(): Flow<PerAppOverview> {
        val listedWithPhrases = accounts.observeAccounts()
            .map { list -> (list as? AccountList.Loaded)?.accounts.orEmpty() }
            .distinctUntilChanged()
            .map { listed -> listed to keys.phrases() }
        return combine(listedWithPhrases, networks.observe()) { (listed, phrases), enabled ->
            val sources = phraseSources(listed, phrases)
            PerAppOverview(perAppEntries(listed), sources, enabled, phraseAccounts(listed, sources))
        }.distinctUntilChanged()
    }
}

/**
 * The account each phrase of [sources] is known by (docs/wallet-ux.md, 4): its first account (`m/44'/60'/0'/0/0`,
 * whose address names the phrase) while that one is in the wallet with its key held here, else the phrase's account
 * listed first that is not a per-app address. A phrase whose name is not known (an envelope sealed before part 2 of
 * #252), or held here by per-app addresses alone, is known by none.
 */
fun phraseAccounts(listed: List<Account>, sources: List<PhraseSource>): Map<Address, Address> {
    val held = listed.filter { it.kind == AccountKind.EOA && it.custody == AccountCustody.InKeyStore }
        .map { it.address }.toSet()
    // A per-app address is never the account its phrase is known by: its tab would list itself among its siblings.
    return sources.mapNotNull { source ->
        val phrase = source.phrase ?: return@mapNotNull null
        when {
            phrase in held -> phrase to phrase
            source.from.perApp == null -> phrase to source.from.address
            else -> null
        }
    }.toMap()
}

/** The per-app addresses among [listed], each once with its accounts on every network ([PerAppEntry]). */
fun perAppEntries(listed: List<Account>): List<PerAppEntry> =
    listed.filter { it.perApp != null }
        .groupBy { it.address }
        .map { (address, held) ->
            val mark = requireNotNull(held.first().perApp)
            PerAppEntry(address, mark.site, mark.path, mark.phrase, held)
        }

/**
 * The networks a restored per-app address goes on (docs/walletconnect.md, D9): every network that is on, of one kind
 * of funds, real funds when one of them is on, else Sepolia. As D3 does when nothing decides the kind
 * ([perAppNetworks]).
 */
fun restoreNetworks(enabled: Set<Network>): List<Network> {
    val open = Network.entries.filter { it in enabled }
    val testnet = open.none { !it.testnet }
    return open.filter { it.testnet == testnet }
}

/**
 * Restores the per-app address of a site on request (screen D9, "Restore a per-app address"; owner's request,
 * 2026-11-14): [hostOrSite] as the user typed it (a host, a site or a web address), the phrase that [from]'s envelope
 * holds, and the wallet password. The address is derived again, key and all, into the key store and added on
 * [restoreNetworks] ([MakePerAppAddress]), without waiting for the site to connect. The private key is never shown
 * or exported.
 */
class RestorePerAppAddress(
    private val make: MakePerAppAddress,
    private val networks: NetworkSettings,
) {
    suspend operator fun invoke(from: AccountId, hostOrSite: String, password: WalletPassword): PerAppRestoring {
        val text = hostOrSite.trim()
        // A web address as one; a host or a site as the web address it names, so that a port, a path, a query or a
        // fragment typed after it is dropped as a dApp's URL has it ("app.uniswap.org/swap?x=1").
        val host = AppHost.ofUrl(if ("://" in text) text else "https://$text")
        if (host !is AppHostResult.Host) return PerAppRestoring.NoHost(host)
        val on = restoreNetworks(networks.observe().first())
        return PerAppRestoring.Done(host.host.site, make(from, host.host, on, password))
    }
}

/** The outcome of [RestorePerAppAddress]. */
sealed interface PerAppRestoring {

    /** What was typed names no host it can be made for ([reason]); nothing changed. */
    data class NoHost(val reason: AppHostResult) : PerAppRestoring

    /** It was made for [site], with [making] saying how that went. */
    data class Done(val site: AppHost, val making: PerAppMaking) : PerAppRestoring
}
