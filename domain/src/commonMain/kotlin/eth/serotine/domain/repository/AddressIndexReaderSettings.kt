package eth.serotine.domain.repository

import eth.serotine.domain.model.AddressIndexImporters

/**
 * The port for the engine's own setting of the addr1 reader (issue #334; Myotis's `setAddressHistoryConfigJson`): the
 * importer key whose index the engine may read on each network, or none, which refuses every read. Engine-wide, one
 * key per network (Myotis #933, from 0.1.25 on): a read on a network without a key is refused. The keys are only ever
 * the ones pinned in the release, and only while the user's experimental setting is on for them
 * (`eth.serotine.domain.usecase.FollowAddressIndexSetting`); the Swarm network it joins is the adapter's, fixed by
 * the host. Never throws.
 */
interface AddressIndexReaderSettings {
    /**
     * Lets the engine read, on each network of [importers], the index of that network's key, and nothing on any other
     * network ([AddressIndexImporters.NONE]: nothing at all). True once taken, or kept to be applied as soon as the
     * engine exists; false when the engine refused it.
     */
    suspend fun apply(importers: AddressIndexImporters): Boolean
}
