package eth.serotine.domain.repository

import eth.serotine.domain.model.AddressIndexImporters

/**
 * The port for the engine's own setting of the addr1 reader (issue #334; Myotis's `setAddressHistoryConfigJson`): the
 * importer key whose index the engine may read on each network, or none, which refuses every read. The keys are only
 * ever those pinned in the release, and only while the user's experimental setting is on for them
 * (`eth.serotine.domain.usecase.FollowAddressIndexSetting`); the Swarm network it joins is the adapter's, fixed by
 * the host. Never throws.
 */
interface AddressIndexReaderSettings {
    /**
     * Lets the engine read, on each network of [importers], the index its key signs, and nothing on any other network
     * ([AddressIndexImporters.NONE]: nothing at all). True once taken, or kept to be applied as soon as the engine
     * exists; false when the engine refused it.
     */
    suspend fun apply(importers: AddressIndexImporters): Boolean
}
