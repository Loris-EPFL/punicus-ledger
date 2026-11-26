package eth.serotine.data.history

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexImporters
import eth.serotine.domain.model.Network

/**
 * The importer keys of the addr1 index pinned in this release, one per network that has an index: on each, the one key
 * whose index the list may rest on, named by the experimental setting (owner's decision, 2026-11-21, on #335: pinned
 * in the release, never typed by the user). Which key is pinned is the owner's choice, named in the PR that pins it:
 * - **Ethereum:** `0xb11B…7241` (owner's choice, 2026-11-24, #359), the key that signs the one-year index of Ethereum
 *   mainnet on Swarm mainnet (blocks 23,838,784 to 26,468,607 at its first publication, `TRACE` entries included).
 * - **Gnosis Chain:** `0xE779…9335` (owner's choice, 2026-11-26, #393, on the request of that day: "show addr1
 *   results for Gnosis chain as well"), the key that signs the one-year index of Gnosis Chain (chain 100) on Swarm
 *   mainnet, from block 43,329,216 (`TRACE` entries included). Read under its own key from Myotis 0.1.25 on (Myotis
 *   #933).
 *
 * Sepolia has none. Until 2026-11-24 none was pinned (owner's decision, 2026-11-21).
 */
object PinnedAddressIndexImporter {
    /** The keys pinned in this release, by network; a network left out has no addr1 history. */
    val KEYS: AddressIndexImporters = AddressIndexImporters(
        mapOf(
            Network.ETHEREUM to Address.of("0xb11B5F07FEb04076c21092A64eb827f418087241"),
            Network.GNOSIS to Address.of("0xE7795B93916b361F1aF8610A16ef8E1190319335"),
        ),
    )
}
