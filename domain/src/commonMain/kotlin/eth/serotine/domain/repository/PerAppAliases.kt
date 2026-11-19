package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppHost
import kotlinx.coroutines.flow.Flow

/**
 * The aliases the user linked (issue #252; docs/walletconnect.md, *Aliases, for a dApp that moved to another site*):
 * each a site that sees the per-app address of another site, after the user was asked and agreed. A site is a host's
 * registrable domain ([AppHost.site]). App-private data, like a session's state, and not key material: nothing here
 * signs, and nothing of it is restored with the phrase. :data keeps it in a file.
 */
interface PerAppAliases {

    /**
     * Each aliased site with the per-app address it sees, re-emitted on each change. Empty while they cannot be read:
     * no other site's address is then offered to any site, which is the safe side. Never throws.
     */
    fun observe(): Flow<Map<AppHost, Address>>

    /**
     * Links [site] to the per-app address [address], in place of any it had. [site] is used as it is: the caller
     * passes a site, never a host of one. False when it could not be saved. Never throws.
     */
    suspend fun link(site: AppHost, address: Address): Boolean

    /** Removes [site]'s alias; true also when it had none. False when it could not be saved. Never throws. */
    suspend fun unlink(site: AppHost): Boolean
}
