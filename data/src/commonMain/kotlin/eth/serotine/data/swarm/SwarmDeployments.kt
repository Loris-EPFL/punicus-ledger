package eth.serotine.data.swarm

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network

/**
 * Bee's contracts on one network, as they are pinned (docs/swarm.md, *The contracts*): each by
 * its address and the keccak-256 of its runtime code (lower-case hex without `0x`), which the
 * engine adapter proves before anything of it is read. A wrong pin makes Swarm unavailable,
 * never wrong.
 */
internal class PinnedSwarm(
    /** xBZZ, the token everything is paid in: a bridged-token proxy. */
    val bzz: Address,
    val bzzProxyCodeHash: String,
    /** The implementation xBZZ's proxy names in its EIP-1967 slot, and its code's hash. */
    val bzzImplementation: Address,
    val bzzImplementationCodeHash: String,
    /** The PostageStamp, where postage batches are bought, topped up and diluted. */
    val postageStamp: Address,
    val postageStampCodeHash: String,
    /** The chequebook factory (SimpleSwapFactory), whose `deployedContracts` proves a chequebook its own. */
    val chequebookFactory: Address,
    val chequebookFactoryCodeHash: String,
    /** The hash of a chequebook's code: the factory's EIP-1167 proxy of its master copy. */
    val chequebookCodeHash: String,
)

/**
 * Bee's contracts per network: Gnosis Chain alone (docs/swarm.md; Swarm's testnet on Sepolia is
 * open, the owner's to decide).
 *
 * The addresses are Bee v2.8.3's (2026-10-15) `pkg/config/chain.go`, from go-storage-incentives-abi
 * v0.9.4 (`abi/abi_mainnet.go`) and go-sw3-abi v0.6.9; storage-incentives' `mainnet_deployed.json`
 * agrees. The code hashes were read on 2026-11-23 near Gnosis block 49,423,000 with `eth_getCode`
 * against a public Gnosis node, a debugging source only (CLAUDE.md, *Data sources*): what it says
 * is what is expected, and the app proves each hash through the light client before reading on.
 * Checked the same day: the factory's `ERC20Address()` is xBZZ and its `master()`
 * `0x3b6a7f0bdb6663007f95dbd7fddef815f7596715`, whose EIP-1167 proxy is the chequebook code
 * pinned here, the hash Bee itself accepts (`AcceptedChequebookBytecodeHashes`); the
 * PostageStamp's `bzzToken()` is xBZZ; xBZZ's EIP-1967 implementation slot names
 * `0xf8d1677c8a0c961938bf2f9adc3f3cfda759a9d9`, and its `symbol()` is "BZZ" and `decimals()` 16.
 * Neither the PostageStamp nor the factory is a proxy, so neither can change its code.
 */
internal object SwarmDeployments {

    /** The contracts pinned on [network], or null when none are. */
    fun of(network: Network): PinnedSwarm? = when (network) {
        Network.GNOSIS -> GNOSIS
        Network.ETHEREUM, Network.SEPOLIA -> null
    }

    private val GNOSIS = PinnedSwarm(
        bzz = Address.of("0xdbf3ea6f5bee45c02255b2c26a16f300502f68da"),
        bzzProxyCodeHash = "3cc6bb329fa948d79cd1e0b616a4a0cc1faff6341730042d5fa5adfef76901e5",
        bzzImplementation = Address.of("0xf8d1677c8a0c961938bf2f9adc3f3cfda759a9d9"),
        bzzImplementationCodeHash = "3c42cd875bf3a7c6ef70a1e3ff64377f4e9d35aa8db0695f6724a4f94e1f5c3f",
        postageStamp = Address.of("0x45a1502382541cd610cc9068e88727426b696293"),
        postageStampCodeHash = "d9078a1ac32fa871bdb11be9d933a1b94e5081882565c5c6dd8a1b1cd3057145",
        chequebookFactory = Address.of("0xc2d5a532cf69aa9a1378737d8ccdef884b6e7420"),
        chequebookFactoryCodeHash = "892333f95702916f17b7244e64a91ff89b0e789e208d681e7d7833b909b17065",
        chequebookCodeHash = "81d3de06cadb0970fc653f24cef4689243f9a3d702236370ecf4613673048145",
    )
}
