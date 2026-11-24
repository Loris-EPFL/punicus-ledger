package eth.serotine.data.swaps

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UniswapV3Deployment

/**
 * A Uniswap v3 deployment as it is pinned: its [deployment], and the runtime code hash each of its
 * four contracts must have ([codeHashes], lower-case hex without `0x`), which the engine adapter
 * proves before the deployment is a venue (`VerifiedUniswapV3`).
 */
internal class PinnedUniswapV3(val deployment: UniswapV3Deployment, val codeHashes: Map<Address, String>) {
    init {
        val contracts = with(deployment) { setOf(factory, quoterV2, swapRouter02, weth9) }
        require(codeHashes.keys == contracts) { "a code hash for each pinned contract, and no other" }
    }
}

/**
 * The Uniswap v3 deployments swaps go through, per network (docs/swaps.md, *Pins*).
 *
 * Sepolia's addresses are the deployment table of Uniswap's developer docs
 * (docs.uniswap.org, *Ethereum Deployments*, Sepolia), read 2026-10-18. The code hashes were read
 * on 2026-10-19 at block 11,947,882 with Foundry's `cast codehash` against a public Sepolia RPC
 * node, a debugging source only (CLAUDE.md, *Data sources*): what it says is what is expected, and
 * the app proves each hash through the light client before quoting anything, so a wrong pin makes
 * Uniswap unavailable, never a wrong swap. Each code was checked to be what it is pinned as: it
 * dispatches the selectors the domain calls (the factory `getPool`, `createPool` and `owner`;
 * QuoterV2 both quotes; SwapRouter02 `multicall`, `exactInputSingle`, `exactInput` and
 * `unwrapWETH9`; WETH9 `deposit` and `withdraw`), and QuoterV2's hash was the same from a second
 * node. None of the four is a proxy, so none can change its code.
 *
 * On Sepolia USDC, LINK and UNI have a pool with WETH at each of the four tiers, the 0.01 % tier
 * included (`feeAmountTickSpacing(100)` is 1), and USDC/LINK, USDC/UNI and LINK/UNI pools of their
 * own, and a quote touches 9 to 16 storage slots of 3 to 6 contracts (`eth_createAccessList` of
 * QuoterV2's calls), each a proof the engine fetches, read the same day. Every built-in token
 * added since has at least one WETH pool with liquidity (docs/swaps.md, *The cost*).
 *
 * Ethereum's are the canonical deployment of the same table (*Ethereum Deployments*, Mainnet):
 * the v3 factory, QuoterV2, SwapRouter02 and WETH9. Their code hashes were read on 2026-10-23
 * at mainnet block 26,256,227 (`eth_getProof`'s code hash; WETH9's as the hash of its code)
 * through two public nodes, which agreed, a debugging source only, as above: what is expected,
 * which the light client proves before any quote (`MyotisHostEthereumTest` does so on the real
 * chain). None of the four is a proxy.
 *
 * Gnosis Chain's are the deployment Uniswap governance made there, as Uniswap's deployment list
 * names it (developers.uniswap.org, `deployments.json`, chain 100), read 2026-11-23: the v3
 * factory, QuoterV2 and SwapRouter02, with WXDAI, the wrapped xDAI both of the latter two name as
 * their `WETH9()`, in WETH9's place (both also name this factory as their `factory()`). Their code
 * hashes were read the same day at Gnosis block 49,421,347 (`eth_getProof`'s code hash) through
 * two public nodes, which agreed, a debugging source only, as above: what is expected, which the
 * light client proves before any quote. Each code was checked to dispatch the selectors the domain
 * calls, as Sepolia's were. None of the four is a proxy. Uniswap v3 holds little on Gnosis Chain:
 * that day only the WXDAI/USDC.e, USDC.e/EURe and USDC.e/sDAI pools at 0.01 % held funds that
 * matter, so most pairs find no route there, and say so (docs/networks.md, open question 7).
 */
internal object UniswapDeployments {

    /** The deployment pinned on [network], or null when none is. */
    fun of(network: Network): PinnedUniswapV3? = when (network) {
        Network.ETHEREUM -> ETHEREUM
        Network.GNOSIS -> GNOSIS
        Network.SEPOLIA -> SEPOLIA
    }

    private val GNOSIS = pinned(
        factory = "0xe32f7dd7e3f098d518ff19a22d5f028e076489b1" to
            "1c34570f49479aaf31c55a3aa8209b64b3028b5d1bf8380ba887efb42e22875f",
        quoterV2 = "0x7e9cb3499a6cee3babe5c8a3d328ea7fd36578f4" to
            "3092cffe44375ae19008a0e6d2a6f42ee73d628ba0ce10bb7173682e97970a4a",
        swapRouter02 = "0xc6d25285d5c5b62b7ca26d6092751a145d50e9be" to
            "4d7621886df22214940ed9e069ac6e09b4dc0b30f03e30fe72eb9e97f622f40d",
        weth9 = "0xe91d153e0b41518a2ce8dd3d7944fa863463a97d" to
            "dfff0c54be05b5df7dc8f015f8c813825344770fee1ba1202130a4652b529ca9",
    )

    private val ETHEREUM = pinned(
        factory = "0x1f98431c8ad98523631ae4a59f267346ea31f984" to
            "4d7b8525cd5d14343fa67a732fba5b24cddba11620ca88392f4ec6c52f91fd69",
        quoterV2 = "0x61ffe014ba17989e743c5f6cb21bf9697530b21e" to
            "06148f47d0f41a68d3bc970030a7150e5d608cfbc28d372440a2e41ce543d92b",
        swapRouter02 = "0x68b3465833fb72a70ecdf485e0e4c7bd8665fc45" to
            "6ec798e80f3a19de650826338677604e54d6664f44a33b53a20b22b1939f402e",
        weth9 = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2" to
            "d0a06b12ac47863b5c7be4185c2deaad1c61557033f56c7d4ea74429cbb25e23",
    )

    private val SEPOLIA = pinned(
        factory = "0x0227628f3f023bb0b980b67d528571c95c6dac1c" to
            "acb5afea1f8877239fadd30358add13f2f9d4fb80175402c686d392295224fef",
        quoterV2 = "0xed1f6473345f45b75f8179591dd5ba1888cf2fb3" to
            "e4068c0e5f3f2d9980793dd4314d2222dca27d03fd999cb5dca580fd90ceea0e",
        swapRouter02 = "0x3bfa4769fb09eefc5a80d6e87c3b9c650f7ae48e" to
            "e7f98ee73dfe6d5c96cbf8936920f496b1b82f24326d6a415b4144a2252271de",
        weth9 = "0xfff9976782d46cc05630d1f6ebab18b2324d6b14" to
            "c864e10689f2da18833652a3b075d43106e87f0f90d95ee64f6f0b33bc026083",
    )

    private fun pinned(
        factory: Pair<String, String>,
        quoterV2: Pair<String, String>,
        swapRouter02: Pair<String, String>,
        weth9: Pair<String, String>,
    ): PinnedUniswapV3 {
        val pins = listOf(factory, quoterV2, swapRouter02, weth9)
            .map { (address, hash) -> Address.of(address) to hash }
        val (f, q, r, w) = pins.map { it.first }
        return PinnedUniswapV3(UniswapV3Deployment(f, q, r, w), pins.toMap())
    }
}
