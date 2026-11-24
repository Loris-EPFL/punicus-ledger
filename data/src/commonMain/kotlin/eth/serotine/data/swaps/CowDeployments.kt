package eth.serotine.data.swaps

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowDeployment
import eth.serotine.domain.model.Network

/**
 * A CoW Protocol deployment as it is pinned: its [deployment], and the runtime code hash each of
 * its two contracts must have ([codeHashes], lower-case hex without `0x`), which the engine adapter
 * proves before the deployment is a venue (`VerifiedCow`).
 */
internal class PinnedCow(val deployment: CowDeployment, val codeHashes: Map<Address, String>) {
    init {
        require(codeHashes.keys == setOf(deployment.settlement, deployment.vaultRelayer)) {
            "a code hash for each pinned contract, and no other"
        }
    }
}

/**
 * The CoW Protocol deployments orders are signed for, per network (docs/swaps.md, *An order, step
 * by step*).
 *
 * The addresses are CoW's, the same on every network it runs on (docs.cow.fi, *Core contracts*,
 * and cowprotocol/contracts' `networks.json`), read 2026-10-22. The code hashes were read on
 * 2026-10-22 at block 11,969,176 with Foundry's `cast codehash` against two public Sepolia RPC
 * nodes, which agreed, a debugging source only (CLAUDE.md, *Data sources*): what it says is what
 * is expected, and the app proves each hash through the light client before quoting anything, so a
 * wrong pin makes CoW unavailable, never a wrong order. Checked the same day: the settlement's
 * code dispatches every selector the domain calls (`domainSeparator`, `filledAmount`,
 * `invalidateOrder`) and `settle`; its `domainSeparator()` is the one the domain computes for
 * Sepolia; its `vaultRelayer()` is the relayer pinned here. Neither contract is a proxy, so
 * neither can change its code; the settlement's immutables (its authenticator, vault, relayer
 * and domain separator) are in that code, so its hash pins them too.
 *
 * Ethereum's and Gnosis Chain's are the same two addresses. Their code hashes were read on
 * 2026-11-23 (`eth_getProof`'s code hash), at Ethereum block 26,475,165 and Gnosis block
 * 49,421,347, each through two public nodes, which agreed, a debugging source only, as above. The
 * vault relayer's code is Sepolia's, byte for byte; the settlement's differs per network, since its
 * domain separator, an immutable, holds the chain id. Checked the same day on each: its
 * `domainSeparator()` is the one the domain computes for that network, and its `vaultRelayer()` the
 * relayer pinned here.
 */
internal object CowDeployments {

    private val SETTLEMENT = Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41")
    private val VAULT_RELAYER = Address.of("0xc92e8bdf79f0507f65a392b0ab4667716bfe0110")
    private const val VAULT_RELAYER_CODE = "500097799c1379a3728ed70b17de4132de2c07f6937b041c361deaade22b6a5e"

    private val ETHEREUM = pinned(
        settlementCode = "744d58584e38d214eb190629f131d5cf8b8703bd68e04452f9692177c37c4bc9",
        wrappedNative = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",
    )
    private val GNOSIS = pinned(
        settlementCode = "68963e5b27aadd4ee70ecd933fac9312fe5f527390b88ae6092c68937b80f5e2",
        wrappedNative = "0xe91d153e0b41518a2ce8dd3d7944fa863463a97d",
    )
    private val SEPOLIA = pinned(
        settlementCode = "9fbace363dc778e25fecb202c12981d916faea80c9aab8167aeeedcaed84df53",
        wrappedNative = "0xfff9976782d46cc05630d1f6ebab18b2324d6b14",
    )

    /** The deployment pinned on [network], or null when none is. */
    fun of(network: Network): PinnedCow? = when (network) {
        Network.ETHEREUM -> ETHEREUM
        Network.GNOSIS -> GNOSIS
        Network.SEPOLIA -> SEPOLIA
    }

    /**
     * CoW's two contracts at their addresses, the same on every network: the settlement with
     * [settlementCode], whose domain separator holds the chain id, and the relayer, whose code is
     * the same everywhere; and the network's [wrappedNative] (WETH, WXDAI), the wrapped currency
     * CoW names for it (docs.cow.fi), the WETH9 the Uniswap pins name too.
     */
    private fun pinned(settlementCode: String, wrappedNative: String) = PinnedCow(
        CowDeployment(SETTLEMENT, VAULT_RELAYER, wrappedNative = Address.of(wrappedNative)),
        mapOf(SETTLEMENT to settlementCode, VAULT_RELAYER to VAULT_RELAYER_CODE),
    )
}
