package eth.serotine.domain.repository

import eth.serotine.domain.model.Address

/**
 * The port for the engine's own setting of the addr1 reader (issue #334; Myotis's `setAddressHistoryConfigJson`): the
 * importer key whose index the engine may read, or none, which refuses every read. Engine-wide, not per network. The
 * key is only ever the one pinned in the release, and only while the user's experimental setting is on for it
 * (`eth.serotine.domain.usecase.FollowAddressIndexSetting`); the Swarm network it joins is the adapter's, fixed by
 * the host. Never throws.
 */
interface AddressIndexReaderSettings {
    /**
     * Lets the engine read the index of [importer], or nothing (null). True once taken, or kept to be applied as soon
     * as the engine exists; false when the engine refused it.
     */
    suspend fun apply(importer: Address?): Boolean
}
