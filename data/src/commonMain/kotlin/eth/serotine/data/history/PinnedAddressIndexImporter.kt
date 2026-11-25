package eth.serotine.data.history

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexImporters
import eth.serotine.domain.model.Network

/**
 * The importer keys of the addr1 index pinned in this release, one per network at most: on each network, the one key
 * whose index the list may rest on, named by the experimental setting (owner's decision, 2026-11-21, on #335: pinned
 * in the release, never typed by the user). Which key is pinned is the owner's choice, named in the PR that pins it.
 * Until 2026-11-24 none was pinned (owner's decision, 2026-11-21).
 */
object PinnedAddressIndexImporter {
    /**
     * Ethereum's: `0xb11B…7241` (owner's choice, 2026-11-24, #359), the key that signs the one-year index of Ethereum
     * mainnet on Swarm mainnet (blocks 23,838,784 to 26,468,607 at its first publication, `TRACE` entries included).
     */
    val ETHEREUM: Address = Address.of("0xb11B5F07FEb04076c21092A64eb827f418087241")

    /**
     * Gnosis Chain's: `0xE779…9335` (owner's choice, 2026-11-25), the key that signs the one-year index of Gnosis
     * Chain on Swarm mainnet (blocks 43,329,216 to 49,465,471 at its first publication, `TRACE` entries included).
     */
    val GNOSIS: Address = Address.of("0xE7795B93916b361F1aF8610A16ef8E1190319335")

    /** The keys pinned in this release, by network; Sepolia has none. */
    val KEYS: AddressIndexImporters = AddressIndexImporters(
        mapOf(Network.ETHEREUM to ETHEREUM, Network.GNOSIS to GNOSIS),
    )
}
