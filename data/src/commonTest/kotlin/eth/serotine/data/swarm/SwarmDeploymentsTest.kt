package eth.serotine.data.swarm

import eth.serotine.data.engine.codeHash
import eth.serotine.domain.model.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SwarmDeploymentsTest {

    @Test
    fun onlyGnosisChainPinsBeesContracts() {
        assertNotNull(SwarmDeployments.of(Network.GNOSIS))
        assertNull(SwarmDeployments.of(Network.ETHEREUM))
        assertNull(SwarmDeployments.of(Network.SEPOLIA))
    }

    @Test
    fun theChequebookPinIsTheFactorysProxyOfItsMasterCopy() {
        // EIP-1167's minimal proxy of the factory's `master()`, 0x3b6a…6715: what eth_getCode of
        // the chequebook 0x614f…7b77 returned on 2026-11-23.
        val pins = checkNotNull(SwarmDeployments.of(Network.GNOSIS))
        val master = pins.chequebookMaster.hex.removePrefix("0x").lowercase()
        val proxy = "363d3d373d3d3d363d73" + master + "5af43d82803e903d91602b57fd5bf3"
        assertEquals("3b6a7f0bdb6663007f95dbd7fddef815f7596715", master)
        assertEquals(pins.chequebookCodeHash, codeHash(proxy))
    }
}
