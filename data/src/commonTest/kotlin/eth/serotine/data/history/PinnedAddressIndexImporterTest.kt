package eth.serotine.data.history

import eth.serotine.domain.model.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [PinnedAddressIndexImporter]: the keys a release pins, one per network. */
class PinnedAddressIndexImporterTest {

    @Test
    fun theReleasePinsTheOwnersImporterKeyForEachNetworkThatHasOne() {
        assertEquals(
            "0xb11b5f07feb04076c21092a64eb827f418087241",
            PinnedAddressIndexImporter.KEYS.of(Network.ETHEREUM)?.hex,
            "owner's choice, 2026-11-24, #359: the key of the one-year Ethereum mainnet index",
        )
        assertEquals(
            "0xe7795b93916b361f1af8610a16ef8e1190319335",
            PinnedAddressIndexImporter.KEYS.of(Network.GNOSIS)?.hex,
            "owner's choice, 2026-11-25: the key of the one-year Gnosis Chain index",
        )
        assertNull(PinnedAddressIndexImporter.KEYS.of(Network.SEPOLIA), "Sepolia has no index pinned")
    }
}
