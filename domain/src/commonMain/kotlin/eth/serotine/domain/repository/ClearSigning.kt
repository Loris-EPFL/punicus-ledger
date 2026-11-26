package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSigningCodeReads
import eth.serotine.domain.model.ClearSigningDeployment
import eth.serotine.domain.model.CodePin
import eth.serotine.domain.model.Network

/**
 * The built-in clear-signing catalog (docs/clear-signing.md; `.claude/rules/data-sources.md`, *The
 * clear-signing descriptors are built in*): the curated deployments with their code pins and descriptors,
 * pinned at build time. :data implements it over the generated catalog; nothing is fetched.
 */
interface ClearSigningCatalog {
    /** The built-in deployment at [address] on [network], or null when the catalog has none there. */
    fun deployment(network: Network, address: Address): ClearSigningDeployment?
}

/**
 * The port for the code a descriptor is bound to (docs/clear-signing.md, A4), read through the embedded light
 * client: the code at an address, and, as its [CodePin] says, the proxy pointer and the code it points at.
 * :data implements it over the Myotis engine; nothing else may answer it (CLAUDE.md, *Trust*).
 */
interface ContractCodes {
    /**
     * What is at [address] on [network], read verified at a fresh head: its code's hash, and for a proxy ([pin])
     * the word its pointer holds and the hash of the code that word names. A [network] no adapter serves is
     * refused ([ReadUnavailable.NO_ENGINE]). Never fails.
     */
    suspend fun read(network: Network, address: Address, pin: CodePin): CodeReading
}

/** What [ContractCodes.read] found. */
sealed interface CodeReading {
    /** Read verified. */
    data class Read(val reads: ClearSigningCodeReads) : CodeReading

    /** Not read, and why. */
    data class Unavailable(val reason: ReadUnavailable) : CodeReading
}
