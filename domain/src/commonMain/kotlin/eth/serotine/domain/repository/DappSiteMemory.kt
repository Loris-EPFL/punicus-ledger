package eth.serotine.domain.repository

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AppHost
import kotlinx.coroutines.flow.Flow

/**
 * The accounts last approved for each dApp site (issue #252; docs/walletconnect.md, *Per-app addresses*, *Remembered
 * per site*), so that a host of the same site connecting again finds them preselected on screen D3 (WalletBeat's
 * per-app account persistence). A site is a host's registrable domain ([AppHost.site]). App-private data, like a
 * session's state, and not key material: nothing here signs. Not restored with the phrase: after a restore,
 * connecting again derives the per-app address again, so nothing of it needs a backup. :data keeps it in a file.
 */
interface DappSiteMemory {

    /**
     * Each site's accounts last approved, in the order they were chosen, re-emitted on each change. Empty while
     * they cannot be read: nothing is then preselected, and nothing else depends on them. Never throws.
     */
    fun observe(): Flow<Map<AppHost, List<AccountId>>>

    /**
     * Remembers [accounts] as those last approved for [site], in place of any before. [site] is used as it is: the
     * caller passes a site, never a host of one. False when it could not be saved. Never throws.
     */
    suspend fun remember(site: AppHost, accounts: List<AccountId>): Boolean
}
