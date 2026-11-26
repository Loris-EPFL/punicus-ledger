package eth.serotine.domain.repository

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.VerifiedCode

/**
 * The built-in verified-ABI catalog (docs/clear-signing.md, *Phase C*; `.claude/rules/data-sources.md`,
 * *Verified ABIs are built in*): phase C's list, keyed by network and runtime code hash, pinned at build time.
 * :data implements it over the generated catalog; nothing is fetched.
 */
interface VerifiedAbiCatalog {
    /** What the catalog holds for code hashing to [codeHash] (`0x` and 64 lower-case hex) on [network], or null. */
    fun byCodeHash(network: Network, codeHash: String): VerifiedCode?
}
