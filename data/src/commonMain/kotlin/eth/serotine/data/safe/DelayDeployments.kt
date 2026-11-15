package eth.serotine.data.safe

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.DelayGeneration

/**
 * The Zodiac Delay modifier mastercopies a Gnosis Pay account's Delay is recognised by, per
 * network (docs/gnosis-pay.md, *The mastercopies*). A Delay is a minimal proxy (EIP-1167, as
 * Zodiac's `ModuleProxyFactory` at `0x000000000000aDdB49795b0f9bA5BC298cDda236` deploys one) of
 * one of them: [proxyCode] is that proxy's runtime code, which delegates every call to the
 * mastercopy and holds no logic of its own, so the mastercopy's code is what the module does.
 * The mastercopies are pinned by address, where each holds live code that cannot change; any
 * other proxy, or any other code, is no Delay read here.
 *
 * **Gnosis Chain.** Both are the ones Gnosis Pay's own account-kit (github.com/gnosispay/account-kit,
 * `src/deployments.ts`) set up its accounts with: `0x4A97…F9f5` until 2026-06-03, and `0x22d9…1ef0`
 * since its commit `f4b9287` ("Update modifier addresses", 2026-06-03), two days after the exploit
 * of 2026-06-01 that went through the Delay module. Read on 2026-11-15 on Gnosis Chain:
 * - `0x22d9…1ef0` holds 17331 bytes of code, an exact match on Sourcify of `contracts/Delay.sol:Delay`
 *   (zodiac-modifier-delay over `@gnosis.pm/zodiac`'s `Modifier`, whose `moduleOnly` also takes a
 *   signature appended to the calldata, which Punicus never sends). Accounts set up since carry
 *   proxies of it: `0xfc85bcbc2ce6aec1be9d94c77c872269ebddb6c7`, the Delay of the Safe
 *   `0x188416e7d22a45e1c5f69c628d9c05bee54bd4fb` (Safe 1.3.0, its one owner the sentinel `0x…02`,
 *   the Roles and Delay modules on; avatar, target and owner the Safe; a cooldown of 90000 s and
 *   an expiration of 111600 s, where most Gnosis Pay accounts have 180 s and 1800 s).
 * - `0x4A97…F9f5` holds 17319 bytes: the retired one, recognised and never sent through (owner's
 *   decisions, 2026-11-15).
 * Those reads were a public node's (rpc.gnosischain.com) and Sourcify's, a debugging source only
 * (CLAUDE.md, *Data sources*): the light client proves a module's code before it is a Delay.
 *
 * Ethereum and Sepolia pin none: Gnosis Pay runs on Gnosis Chain alone.
 */
internal object DelayDeployments {

    /** The pinned mastercopies of [network], by address, with the generation each is. */
    fun of(network: Network): Map<Address, DelayGeneration> = when (network) {
        Network.GNOSIS -> GNOSIS
        Network.ETHEREUM, Network.SEPOLIA -> emptyMap()
    }

    /**
     * The runtime code of a minimal proxy of [mastercopy], lower-case hex without `0x`: what
     * `ModuleProxyFactory.deployModule` deploys (EIP-1167's 45 bytes).
     */
    fun proxyCode(mastercopy: Address): String =
        "363d3d373d3d3d363d73" + mastercopy.hex.removePrefix("0x").lowercase() + "5af43d82803e903d91602b57fd5bf3"

    private val GNOSIS: Map<Address, DelayGeneration> = mapOf(
        Address.of("0x22d903fd45F441F51bcad198D14eBa8a75EA1ef0") to DelayGeneration.CURRENT,
        Address.of("0x4A97E65188A950Dd4b0f21F9b5434dAeE0BBF9f5") to DelayGeneration.RETIRED,
    )
}
