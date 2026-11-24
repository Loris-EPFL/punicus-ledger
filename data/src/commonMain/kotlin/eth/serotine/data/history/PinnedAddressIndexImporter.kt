package eth.serotine.data.history

import eth.serotine.domain.model.Address

/**
 * The importer key of the addr1 index pinned in this release: the one key whose index the list may rest on, named by
 * the experimental setting (owner's decision, 2026-11-21, on #335: pinned in the release, never typed by the user).
 * Which key is pinned is the owner's choice, named in the PR that pins it: `0xb11B…7241` (owner's choice, 2026-11-24,
 * #359), the key that signs the one-year index of Ethereum mainnet on Swarm mainnet (blocks 23,838,784 to 26,468,607
 * at its first publication, `TRACE` entries included). Until 2026-11-24 none was pinned (owner's decision,
 * 2026-11-21).
 */
object PinnedAddressIndexImporter {
    /** The key pinned in this release, or null: none is. */
    val KEY: Address? = Address.of("0xb11B5F07FEb04076c21092A64eb827f418087241")
}
