package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.PrimaryName
import eth.serotine.domain.repository.SeenEnsNameList
import eth.serotine.domain.repository.SeenEnsNames
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest

/**
 * The ENS names that point to an account (issue #274), apart from the names it holds, which its NFTs
 * list: [primary], the name its reverse record sets, resolved back to it; and [pointing], each name the
 * wallet has seen point to it that still does, in the order first seen. Both are facts about the names'
 * records, not about who holds them. [checked] is false until every name has had a verified answer.
 */
data class AccountNames(val primary: String?, val pointing: List<String>, val checked: Boolean) {
    companion object {
        /** Nothing to show: an account on a network without ENS, or one not read yet. */
        val NONE = AccountNames(null, emptyList(), checked = false)
    }
}

/**
 * What names point to an account (issue #274): its primary name ([NameResolver.primaryName]) and the
 * names seen pointing to it ([SeenEnsNames], the latest [ObserveNfts.MAX_SEEN_NAMES]), each resolved
 * again ([NameResolver.resolve], finalized state) at once and then every [interval] while observed, or
 * every [retryDelay] until each has had a verified answer. A name that no longer resolves to the account
 * drops out; one with no verified answer this time keeps the last verified one, and is left out until it
 * has one. On a network without ENS (Gnosis Chain) there are none. Nothing here says the account holds
 * a name.
 */
class ObserveAccountNames(
    private val names: NameResolver,
    private val seen: SeenEnsNames,
    private val interval: Duration = 10.minutes,
    private val retryDelay: Duration = 30.seconds,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(account: AccountId): Flow<AccountNames> {
        if (!account.network.ensNames) return flowOf(AccountNames.NONE)
        return seen.observe(account)
            .map { list ->
                val loaded = (list as? SeenEnsNameList.Loaded)?.names.orEmpty()
                loaded.map { it.name }.takeLast(ObserveNfts.MAX_SEEN_NAMES)
            }
            .onStart { emit(emptyList()) }
            .distinctUntilChanged()
            .transformLatest { candidates ->
                // The last verified answer of each, which an unverifiable reading after it leaves standing.
                var primary: PrimaryName? = null
                val points = mutableMapOf<String, Boolean>()
                while (true) {
                    when (val read = names.primaryName(account.network, account.address)) {
                        is PrimaryName.Unavailable -> Unit
                        else -> primary = read
                    }
                    for (name in candidates) {
                        when (val resolved = names.resolve(account.network, name)) {
                            is NameResolution.Resolved -> points[name] = resolved.address == account.address
                            NameResolution.NoAddress, NameResolution.NotAName, NameResolution.OffchainOnly ->
                                points[name] = false
                            is NameResolution.Unavailable -> Unit
                        }
                    }
                    val shownPrimary = (primary as? PrimaryName.Named)?.name
                    val checked = primary != null && candidates.all { it in points }
                    emit(
                        AccountNames(
                            shownPrimary,
                            candidates.filter { points[it] == true && it != shownPrimary },
                            checked,
                        ),
                    )
                    delay(if (checked) interval else retryDelay)
                }
            }
            .distinctUntilChanged()
    }
}
